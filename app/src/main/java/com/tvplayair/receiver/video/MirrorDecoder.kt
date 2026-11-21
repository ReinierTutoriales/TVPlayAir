package com.tvplayair.receiver.video

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import com.tvplayair.receiver.session.NativeSharedMemory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

data class MirrorVideoConfig(
    val mimeType: String = MediaFormat.MIMETYPE_VIDEO_AVC,
    val width: Int = 1920,
    val height: Int = 1080
)

/**
 * Low-latency MediaCodec mirror decoder consuming NAL packets from the video
 * SharedRingBuffer into a single reusable DirectByteBuffer (zero per-frame allocation).
 */
class MirrorDecoder(
    private val surfaceProvider: () -> Surface?,
    private val onVideoSizeChanged: ((Int, Int) -> Unit)? = null
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
    private val renderListenerHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var config = MirrorVideoConfig()
    private var codec: MediaCodec? = null
    private var workerThread: Thread? = null

    fun setGeneration(generation: Int) {
        generationId.set(generation)
    }

    fun updateConfig(newConfig: MirrorVideoConfig): Boolean {
        if (config == newConfig) return false
        val mimeChanged = config.mimeType != newConfig.mimeType
        stopAndRelease()
        config = newConfig
        if (mimeChanged) {
            frameGate.clearAll()
        } else {
            frameGate.resetGate()
        }
        return true
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
        createdCodec.setOnFrameRenderedListener({ activeCodec, _, _ ->
            if (codec === activeCodec) {
                Log.i(TAG, "Mirror first frame rendered (${config.mimeType})")
                activeCodec.setOnFrameRenderedListener(null, null)
            }
        }, renderListenerHandler)
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
        format.setInteger(MediaFormat.KEY_MAX_WIDTH, maxOf(cfg.width, 1920))
        format.setInteger(MediaFormat.KEY_MAX_HEIGHT, maxOf(cfg.height, 1920))
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

            var inputIndex = -1
            var attempts = 0
            while (running.get() && attempts < 25) {
                inputIndex = runCatching { activeCodec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US) }
                    .getOrDefault(-1)
                if (inputIndex >= 0) break
                drainOutputBuffers(activeCodec, bufferInfo)
                attempts++
            }
            if (inputIndex < 0) {
                frameGate.resetGate()
                continue
            }

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
            } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                runCatching {
                    val outFmt = activeCodec.outputFormat
                    val w = if (outFmt.containsKey("crop-right") && outFmt.containsKey("crop-left")) {
                        outFmt.getInteger("crop-right") - outFmt.getInteger("crop-left") + 1
                    } else if (outFmt.containsKey(MediaFormat.KEY_WIDTH)) {
                        outFmt.getInteger(MediaFormat.KEY_WIDTH)
                    } else {
                        0
                    }
                    val h = if (outFmt.containsKey("crop-bottom") && outFmt.containsKey("crop-top")) {
                        outFmt.getInteger("crop-bottom") - outFmt.getInteger("crop-top") + 1
                    } else if (outFmt.containsKey(MediaFormat.KEY_HEIGHT)) {
                        outFmt.getInteger(MediaFormat.KEY_HEIGHT)
                    } else {
                        0
                    }
                    if (w > 0 && h > 0) {
                        Log.i(TAG, "Mirror output format changed: ${w}x$h")
                        onVideoSizeChanged?.let { callback ->
                            renderListenerHandler.post { callback(w, h) }
                        }
                    }
                }
                continue
            } else if (outIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                continue
            } else {
                break
            }
        }
    }
}
