package com.tvplayair.receiver.video

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import com.tvplayair.receiver.session.NativeSharedMemory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

data class MirrorVideoConfig(
    val mimeType: String = MediaFormat.MIMETYPE_VIDEO_HEVC,
    val width: Int = 3840,
    val height: Int = 2160
)

/**
 * Low-latency MediaCodec mirror decoder consuming NAL packets from the video
 * SharedRingBuffer into a single reusable DirectByteBuffer (zero per-frame allocation).
 */
class MirrorDecoder(
    private val surfaceProvider: () -> Surface?
) : MirrorVideoEngine {

    companion object {
        private const val TAG = "MirrorDecoder"
        private const val MAX_PACKET_BYTES = 480 * 1024
        private const val DEQUEUE_TIMEOUT_US = 4_000L
        private const val IDLE_POLL_SLEEP_MS = 2L
    }

    val frameGate = MirrorFrameGate()

    private val packetBuffer: ByteBuffer =
        ByteBuffer.allocateDirect(MAX_PACKET_BYTES).order(ByteOrder.LITTLE_ENDIAN)
    private val running = AtomicBoolean(false)
    private val generationId = AtomicInteger(0)

    @Volatile
    private var config = MirrorVideoConfig()
    private var codec: MediaCodec? = null
    private var workerThread: Thread? = null

    fun setGeneration(generation: Int) {
        generationId.set(generation)
    }

    fun updateConfig(newConfig: MirrorVideoConfig) {
        if (config != newConfig) {
            config = newConfig
            frameGate.clearAll()
        }
    }

    @Synchronized
    override fun start(): Boolean {
        if (running.get()) return true
        val surface = surfaceProvider()
        if (surface == null || !surface.isValid) {
            Log.w(TAG, "Mirror surface unavailable; deferring MediaCodec start")
            return false
        }

        frameGate.resetGate()
        val createdCodec = createAndConfigureCodec(surface, config) ?: return false
        codec = createdCodec
        reinjectCachedCodecConfig(createdCodec)

        running.set(true)
        workerThread = Thread(::decodeLoop, "TVPlayAir-MirrorDecoder").apply { start() }
        return true
    }

    @Synchronized
    override fun stopAndRelease() {
        running.set(false)
        workerThread?.let { thread ->
            thread.interrupt()
            runCatching { thread.join(1_000L) }
        }
        workerThread = null

        codec?.let { activeCodec ->
            runCatching { activeCodec.stop() }
            runCatching { activeCodec.release() }
        }
        codec = null
    }

    private fun createAndConfigureCodec(surface: Surface, cfg: MirrorVideoConfig): MediaCodec? {
        val format = MediaFormat.createVideoFormat(cfg.mimeType, cfg.width, cfg.height)
        // Safe low-latency fallback on API 29+; never force KEY_OPERATING_RATE = 32767.
        format.setInteger(MediaFormat.KEY_PRIORITY, 0)

        val decoder = runCatching {
            val codecName = MediaCodecList(MediaCodecList.REGULAR_CODECS)
                .findDecoderForFormat(format)
            if (codecName != null) {
                MediaCodec.createByCodecName(codecName)
            } else {
                MediaCodec.createDecoderByType(cfg.mimeType)
            }
        }.onFailure {
            Log.e(TAG, "Failed to create MediaCodec for ${cfg.mimeType}", it)
        }.getOrNull() ?: return null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val lowLatencySupported = runCatching {
                decoder.codecInfo
                    .getCapabilitiesForType(cfg.mimeType)
                    .isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
            }.getOrDefault(false)
            if (lowLatencySupported) {
                format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            Log.i(
                TAG,
                "Configured ${decoder.name} (${cfg.mimeType} ${cfg.width}x${cfg.height}) " +
                    "lowLatency=$lowLatencySupported"
            )
        } else {
            Log.i(
                TAG,
                "Configured ${decoder.name} (${cfg.mimeType} ${cfg.width}x${cfg.height}) " +
                    "API ${Build.VERSION.SDK_INT} priority=0"
            )
        }

        return runCatching {
            decoder.configure(format, surface, null, 0)
            decoder.start()
            decoder
        }.onFailure {
            Log.e(TAG, "Failed to configure/start MediaCodec", it)
            runCatching { decoder.release() }
        }.getOrNull()
    }

    private fun reinjectCachedCodecConfig(activeCodec: MediaCodec) {
        val cached = frameGate.getCachedCodecConfig() ?: return
        val inputIndex = runCatching { activeCodec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US) }
            .getOrDefault(-1)
        if (inputIndex < 0) {
            Log.w(TAG, "Could not dequeue input buffer to reinject cached SPS/PPS/VPS")
            return
        }
        val inputBuffer = activeCodec.getInputBuffer(inputIndex) ?: return
        inputBuffer.clear()
        if (cached.size <= inputBuffer.capacity()) {
            inputBuffer.put(cached)
            activeCodec.queueInputBuffer(
                inputIndex,
                0,
                cached.size,
                0L,
                MediaCodec.BUFFER_FLAG_CODEC_CONFIG
            )
            Log.i(TAG, "Reinjected cached SPS/PPS/VPS (${cached.size} bytes)")
        }
    }

    private fun decodeLoop() {
        val bufferInfo = MediaCodec.BufferInfo()
        val activeCodec = codec ?: return

        while (running.get()) {
            drainOutputBuffers(activeCodec, bufferInfo)

            val readBytes = NativeSharedMemory.nativeReadVideoPacket(
                packetBuffer,
                generationId.get()
            )
            if (readBytes == -1) {
                // Stale generation skipped by ring; retry immediately.
                continue
            }
            if (readBytes <= MirrorFrameGate.PACKET_HEADER_BYTES) {
                if (readBytes == 0) {
                    runCatching { Thread.sleep(IDLE_POLL_SLEEP_MS) }
                }
                continue
            }

            packetBuffer.position(0)
            val ptsUs = packetBuffer.long
            val flags = packetBuffer.int
            val payloadSize = packetBuffer.int
            val availablePayload = readBytes - MirrorFrameGate.PACKET_HEADER_BYTES
            if (payloadSize <= 0 || payloadSize > availablePayload) {
                continue
            }

            if ((flags and MirrorFrameGate.FLAG_CODEC_CONFIG) != 0) {
                val configBytes = ByteArray(payloadSize)
                packetBuffer.position(MirrorFrameGate.PACKET_HEADER_BYTES)
                packetBuffer.get(configBytes)
                frameGate.onCodecConfig(configBytes)
            }

            if (!frameGate.shouldSubmitFrame(flags)) {
                continue
            }

            val inputIndex = runCatching { activeCodec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US) }
                .getOrDefault(-1)
            if (inputIndex < 0) continue

            val inputBuffer = activeCodec.getInputBuffer(inputIndex) ?: continue
            inputBuffer.clear()
            if (payloadSize > inputBuffer.capacity()) {
                activeCodec.queueInputBuffer(inputIndex, 0, 0, 0L, 0)
                continue
            }

            packetBuffer.limit(MirrorFrameGate.PACKET_HEADER_BYTES + payloadSize)
            packetBuffer.position(MirrorFrameGate.PACKET_HEADER_BYTES)
            inputBuffer.put(packetBuffer)
            packetBuffer.limit(packetBuffer.capacity())

            var codecFlags = 0
            if ((flags and MirrorFrameGate.FLAG_CODEC_CONFIG) != 0) {
                codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_CODEC_CONFIG
            }
            if ((flags and MirrorFrameGate.FLAG_KEY_FRAME) != 0) {
                codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_KEY_FRAME
            }

            runCatching {
                activeCodec.queueInputBuffer(inputIndex, 0, payloadSize, ptsUs, codecFlags)
            }.onFailure {
                Log.w(TAG, "queueInputBuffer failed", it)
            }
        }
    }

    private fun drainOutputBuffers(activeCodec: MediaCodec, bufferInfo: MediaCodec.BufferInfo) {
        while (running.get()) {
            val outIndex = runCatching { activeCodec.dequeueOutputBuffer(bufferInfo, 0L) }
                .getOrDefault(MediaCodec.INFO_TRY_AGAIN_LATER)
            if (outIndex >= 0) {
                runCatching { activeCodec.releaseOutputBuffer(outIndex, true) }
            } else {
                break
            }
        }
    }
}
