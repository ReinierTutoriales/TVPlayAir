package com.tvplayair.receiver.video

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.tvplayair.receiver.diagnostics.PLAYBACK_DIAGNOSTICS_TAG
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
    private val codecLock = Any()
    private val generationId = AtomicInteger(0)
    private val renderListenerHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var config = MirrorVideoConfig()
    @Volatile
    private var codec: MediaCodec? = null
    private var workerThread: Thread? = null

    fun updateConfig(newConfig: MirrorVideoConfig, generation: Int, codecConfig: ByteArray): Boolean {
        if (generationId.get() == generation && config == newConfig &&
            frameGate.getCachedCodecConfig().contentEquals(codecConfig)
        ) return false
        stopAndRelease()
        config = newConfig
        generationId.set(generation)
        frameGate.clearAll()
        frameGate.onCodecConfig(codecConfig)
        return true
    }

    @Synchronized
    override fun start(discardPendingFrames: Boolean): Boolean {
        if (running.get()) return true
        val surface = surfaceProvider()
        if (surface == null || !surface.isValid) {
            Log.w(PLAYBACK_DIAGNOSTICS_TAG,
                "route=mirror_decoder generation=${generationId.get()} event=surface_unavailable")
            return false
        }

        frameGate.resetGate()
        val initialCsd = frameGate.getCachedCodecConfig()
        if (initialCsd != null) {
            val createdCodec = createAndConfigureCodec(surface, config, initialCsd) ?: return false
            codec = createdCodec
        }

        running.set(true)
        workerThread = Thread({
            if (discardPendingFrames) NativeSharedMemory.nativeDiscardPendingVideoPackets()
            decodeLoop()
        }, "TVPlayAir-MirrorDecoder").apply { start() }
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

        codec?.let { releaseCodec(it) }
    }

    private fun createAndConfigureCodec(
        surface: Surface,
        cfg: MirrorVideoConfig,
        csdBytes: ByteArray? = null
    ): MediaCodec? {
        val generation = generationId.get()
        val startedMs = SystemClock.elapsedRealtime()
        val format = MediaFormat.createVideoFormat(cfg.mimeType, cfg.width, cfg.height)
        // Format changes rebuild the decoder. Adaptive maximum hints reserve extra buffers.
        // Safe low-latency fallback on API 29+; never force KEY_OPERATING_RATE = 32767.
        format.setInteger(MediaFormat.KEY_PRIORITY, 0)
        if (csdBytes != null && csdBytes.isNotEmpty()) {
            format.setByteBuffer("csd-0", ByteBuffer.wrap(csdBytes))
        }

        val decoder = runCatching {
            val codecName = MediaCodecList(MediaCodecList.REGULAR_CODECS)
                .findDecoderForFormat(format)
            if (codecName != null) {
                MediaCodec.createByCodecName(codecName)
            } else {
                MediaCodec.createDecoderByType(cfg.mimeType)
            }
        }.onFailure {
            logCodecFailure("create", it)
        }.getOrNull() ?: return null
        val codecName = decoder.name

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
                "Configured $codecName (${cfg.mimeType} ${cfg.width}x${cfg.height}) " +
                    "lowLatency=$lowLatencySupported csd=${csdBytes?.size ?: 0}B"
            )
        } else {
            Log.i(
                TAG,
                "Configured $codecName (${cfg.mimeType} ${cfg.width}x${cfg.height}) " +
                    "API ${Build.VERSION.SDK_INT} priority=0 csd=${csdBytes?.size ?: 0}B"
            )
        }

        return runCatching {
            decoder.configure(format, surface, null, 0)
            decoder.setOnFrameRenderedListener({ activeCodec, _, _ ->
                synchronized(codecLock) {
                    if (codec === activeCodec) {
                        Log.i(PLAYBACK_DIAGNOSTICS_TAG,
                            "route=mirror_decoder generation=$generation event=first_frame codec=$codecName " +
                                "elapsedMs=${SystemClock.elapsedRealtime() - startedMs}")
                        runCatching { activeCodec.setOnFrameRenderedListener(null, null) }
                    }
                }
            }, renderListenerHandler)
            decoder.start()
            Log.i(PLAYBACK_DIAGNOSTICS_TAG,
                "route=mirror_decoder generation=$generation event=codec_started codec=$codecName")
            decoder
        }.onFailure {
            logCodecFailure("configure", it)
            runCatching { decoder.release() }
        }.getOrNull()
    }

    private fun logCodecFailure(operation: String, error: Throwable) {
        val failure = error as? MediaCodec.CodecException
        Log.w(PLAYBACK_DIAGNOSTICS_TAG,
            "route=mirror_decoder generation=${generationId.get()} event=codec_error operation=$operation " +
                "type=${error.javaClass.simpleName} code=${failure?.errorCode ?: "-"} " +
                "diagnostic=${failure?.diagnosticInfo ?: "-"} recoverable=${failure?.isRecoverable ?: "-"} " +
                "transient=${failure?.isTransient ?: "-"}")
    }

    private fun releaseCodec(activeCodec: MediaCodec, reclaimed: Boolean = false) {
        synchronized(codecLock) {
            if (codec === activeCodec) codec = null
            if (!reclaimed) runCatching { activeCodec.stop() }
            runCatching { activeCodec.release() }
        }
    }

    private fun decodeLoop() {
        val bufferInfo = MediaCodec.BufferInfo()
        val outputWatchdog = MirrorOutputWatchdog(SystemClock::elapsedRealtime)
        var activeCodec = codec

        fun recover(codecToRelease: MediaCodec, operation: String, error: IllegalStateException? = null) {
            if (error != null && running.get()) logCodecFailure(operation, error)
            val reclaimed = (error as? MediaCodec.CodecException)?.errorCode ==
                MediaCodec.CodecException.ERROR_RECLAIMED
            if (running.get()) Log.w(PLAYBACK_DIAGNOSTICS_TAG,
                "route=mirror_decoder generation=${generationId.get()} event=await_keyframe reason=$operation")
            releaseCodec(codecToRelease, reclaimed)
            activeCodec = null
            outputWatchdog.clearQueuedFrames()
            frameGate.resetGate()
        }

        while (running.get()) {
            activeCodec?.let { current ->
                try {
                    drainOutputBuffers(current, bufferInfo, outputWatchdog)
                    if (running.get() && outputWatchdog.takeRecovery()) recover(current, "output_stall")
                } catch (error: IllegalStateException) {
                    recover(current, "output", error)
                }
            }

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
                val previousCsd = frameGate.getCachedCodecConfig()
                frameGate.onCodecConfig(configBytes)
                if (activeCodec == null) {
                    val surface = surfaceProvider()
                    if (surface != null && surface.isValid && running.get()) {
                        val created = createAndConfigureCodec(surface, config, configBytes)
                        if (created != null) {
                            if (running.get()) {
                                activeCodec = created
                                codec = created
                            } else {
                                releaseCodec(created)
                            }
                        }
                    }
                    // csd-0 was already passed into MediaCodec.configure(); skip duplicate queueInputBuffer.
                    continue
                } else if (previousCsd != null && previousCsd.contentEquals(configBytes)) {
                    // Identical SPS/PPS already configured on activeCodec; skip duplicate re-config buffer.
                    continue
                }
            }

            if (!frameGate.shouldSubmitFrame(flags)) {
                continue
            }

            if (activeCodec == null && (flags and MirrorFrameGate.FLAG_KEY_FRAME) != 0) {
                val surface = surfaceProvider()
                val csd = frameGate.getCachedCodecConfig()
                if (surface != null && surface.isValid && csd != null && running.get()) {
                    val created = createAndConfigureCodec(surface, config, csd)
                    if (created != null) {
                        if (running.get()) {
                            activeCodec = created
                            codec = created
                        } else {
                            releaseCodec(created)
                        }
                    }
                }
            }
            val currentCodec = activeCodec ?: continue

            var inputIndex = -1
            var attempts = 0
            val inputBuffer = try {
                while (running.get() && attempts < 25) {
                    inputIndex = currentCodec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (inputIndex >= 0) break
                    drainOutputBuffers(currentCodec, bufferInfo, outputWatchdog)
                    attempts++
                }
                if (inputIndex < 0) {
                    if (running.get()) recover(currentCodec, "input_congestion")
                    continue
                }
                currentCodec.getInputBuffer(inputIndex)
            } catch (error: IllegalStateException) {
                recover(currentCodec, "input_or_output", error)
                continue
            }
            if (inputBuffer == null || payloadSize > inputBuffer.capacity()) {
                recover(currentCodec, "input_buffer_unavailable")
                continue
            }
            inputBuffer.clear()

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

            try {
                currentCodec.queueInputBuffer(inputIndex, 0, payloadSize, ptsUs, codecFlags)
                frameGate.onFrameSubmitted(flags)
                if ((flags and MirrorFrameGate.FLAG_CODEC_CONFIG) == 0) outputWatchdog.onFrameQueued()
            } catch (error: IllegalStateException) {
                recover(currentCodec, "queue_input", error)
            }
        }
    }

    private fun drainOutputBuffers(
        activeCodec: MediaCodec,
        bufferInfo: MediaCodec.BufferInfo,
        outputWatchdog: MirrorOutputWatchdog
    ) {
        while (running.get()) {
            val outIndex = activeCodec.dequeueOutputBuffer(bufferInfo, 0L)
            if (outIndex >= 0) {
                activeCodec.releaseOutputBuffer(outIndex, true)
                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                    outputWatchdog.onOutputReleased()
                }
            } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
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
                        renderListenerHandler.post { if (codec === activeCodec) callback(w, h) }
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

/** Runs on the decoder worker. An idle sender and an isolated dropped frame are normal. */
internal class MirrorOutputWatchdog(private val elapsedMs: () -> Long) {
    private companion object {
        const val PENDING_FRAME_THRESHOLD = 4
        const val STALL_TIMEOUT_MS = 2_000L
    }

    private var queuedFrames = 0
    private var waitingSinceMs = 0L
    private var recoveryAttempted = false

    fun onFrameQueued() {
        if (queuedFrames == 0) waitingSinceMs = elapsedMs()
        queuedFrames++
    }

    fun onOutputReleased() {
        if (queuedFrames > 0) queuedFrames--
        waitingSinceMs = elapsedMs()
        recoveryAttempted = false
    }

    fun clearQueuedFrames() {
        queuedFrames = 0
    }

    fun takeRecovery(): Boolean {
        if (recoveryAttempted || queuedFrames < PENDING_FRAME_THRESHOLD ||
            elapsedMs() - waitingSinceMs < STALL_TIMEOUT_MS
        ) return false
        recoveryAttempted = true
        clearQueuedFrames()
        return true
    }
}
