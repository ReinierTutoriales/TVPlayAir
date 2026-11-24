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
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

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
    private val audioClockOffsetNs: () -> Long,
    private val onVideoSizeChanged: ((Int, Int) -> Unit)? = null,
    private val onFirstFrameRendered: () -> Unit,
    private val onRecovering: () -> Unit,
    private val onWaitingForKeyFrame: () -> Unit
) : MirrorVideoEngine {

    companion object {
        private const val TAG = "MirrorDecoder"
        private const val MAX_PACKET_BYTES = 480 * 1024
        private const val DEQUEUE_TIMEOUT_US = 4_000L
        private const val IDLE_POLL_SLEEP_MS = 2L

        // A recreated Activity shares the same decoder slot and ring consumer.
        private val decoderExecutor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "TVPlayAir-MirrorDecoder")
        }
        private var queuedDecoders = 0 // Main-thread confined.
    }

    private data class StreamConfig(val video: MirrorVideoConfig, val generation: Int, val csd: ByteArray)
    private class DecodeRun(val stream: StreamConfig, val surface: Surface, val epoch: Long) {
        val running = AtomicBoolean(true)
    }

    private var requestedConfig = StreamConfig(MirrorVideoConfig(), 0, byteArrayOf())
    private var workerConfig: StreamConfig? = null
    private val frameGate = MirrorFrameGate()
    private val packetBuffer = ByteBuffer.allocateDirect(MAX_PACKET_BYTES).order(ByteOrder.LITTLE_ENDIAN)
    private val renderListenerHandler = Handler(Looper.getMainLooper())
    private var activeRun: DecodeRun? = null
    @Volatile
    private var codec: MediaCodec? = null

    fun updateConfig(newConfig: MirrorVideoConfig, generation: Int, codecConfig: ByteArray): Boolean {
        val old = requestedConfig
        if (old.generation == generation && old.video == newConfig && old.csd.contentEquals(codecConfig)) {
            return false
        }
        requestedConfig = StreamConfig(newConfig, generation, codecConfig)
        stopAndRelease()
        return true
    }

    override fun start(discardPendingFrames: Boolean): Boolean {
        if (activeRun != null) return true
        val surface = surfaceProvider()
        if (surface == null || !surface.isValid) {
            Log.w(PLAYBACK_DIAGNOSTICS_TAG,
                "route=mirror_decoder generation=${requestedConfig.generation} event=surface_unavailable")
            return false
        }

        val run = DecodeRun(requestedConfig, surface, NativeSharedMemory.nativeVideoConsumerEpoch())
        activeRun = run
        queuedDecoders++
        decoderExecutor.execute {
            try {
                if (run.running.get()) {
                    if (workerConfig !== run.stream) {
                        frameGate.clearAll()
                        if (run.stream.csd.isNotEmpty()) frameGate.onCodecConfig(run.stream.csd)
                        workerConfig = run.stream
                    }
                    frameGate.resetGate()
                    if (discardPendingFrames) NativeSharedMemory.nativeDiscardPendingVideoPackets(run.epoch)
                    val watchdog = MirrorOutputWatchdog(
                        allowLegacyOutputProgress = Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                        elapsedMs = SystemClock::elapsedRealtime
                    )
                    codec = frameGate.getCachedCodecConfig()?.let { createAndConfigureCodec(run, it, watchdog) }
                    decodeLoop(run, watchdog)
                }
            } finally {
                run.running.set(false)
                codec?.let { releaseCodec(it) }
                renderListenerHandler.post {
                    queuedDecoders--
                    if (activeRun === run) activeRun = null
                }
            }
        }
        return true
    }

    override fun stopAndRelease() {
        activeRun?.running?.set(false)
        activeRun = null
    }

    override fun stopBeforeDirect(onReleased: () -> Unit): Boolean {
        stopAndRelease()
        if (queuedDecoders == 0) return true
        // Completion is posted only after stop/release returned on the codec worker.
        decoderExecutor.execute { renderListenerHandler.post(onReleased) }
        return false
    }

    private fun createAndConfigureCodec(
        run: DecodeRun,
        csdBytes: ByteArray,
        watchdog: MirrorOutputWatchdog,
        lastStalledCodec: String? = null
    ): MediaCodec? {
        val surface = run.surface
        val cfg = run.stream.video
        val generation = run.stream.generation
        if (!run.running.get() || !surface.isValid) return null
        val startedMs = SystemClock.elapsedRealtime()
        if (cfg.width <= 0 || cfg.height <= 0) {
            Log.w(PLAYBACK_DIAGNOSTICS_TAG,
                "route=mirror_decoder generation=$generation event=invalid_dimensions " +
                    "size=${cfg.width}x${cfg.height}")
            return null
        }
        // The device-preferred codec is not necessarily capable of decoding the sender's
        // actual portrait dimensions. Check published capabilities before allocation.
        val codecInfos = runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        }.onFailure { logCodecFailure(generation, "enumerate", it) }.getOrNull() ?: return null
        val candidates = codecInfos.filter { info ->
            !info.isEncoder && info.supportedTypes.any { it.equals(cfg.mimeType, ignoreCase = true) } &&
                runCatching {
                    info.getCapabilitiesForType(cfg.mimeType).videoCapabilities
                        ?.isSizeSupported(cfg.width, cfg.height) == true
                }.getOrDefault(false)
        }
        if (candidates.isEmpty()) {
            Log.w(PLAYBACK_DIAGNOSTICS_TAG,
                "route=mirror_decoder generation=$generation event=unsupported_dimensions " +
                    "mime=${cfg.mimeType} size=${cfg.width}x${cfg.height}")
            return null
        }
        // A codec may advertise dimensions but still fail configure/start for this Surface.
        // Try every compatible decoder, not just the first creatable codec.
        for (candidate in candidates.sortedBy { it.name == lastStalledCodec }) {
            if (!surface.isValid || !run.running.get()) return null
            val codecName = candidate.name
            val lowLatencySupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                runCatching {
                    candidate.getCapabilitiesForType(cfg.mimeType)
                        .isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
                }.getOrDefault(false)

            fun tryStart(lowLatency: Boolean): MediaCodec? {
                val instance = runCatching { MediaCodec.createByCodecName(codecName) }
                    .onFailure { logCodecFailure(generation, "create_candidate", it) }.getOrNull()
                    ?: return null
                val attemptFormat = MediaFormat.createVideoFormat(cfg.mimeType, cfg.width, cfg.height)
                if (csdBytes.isNotEmpty()) {
                    attemptFormat.setByteBuffer("csd-0", ByteBuffer.wrap(csdBytes))
                }
                if (lowLatency) {
                    attemptFormat.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                    attemptFormat.setInteger(MediaFormat.KEY_PRIORITY, 0)
                }
                return runCatching {
                    instance.configure(attemptFormat, surface, null, 0)
                    var firstFrame = true
                    instance.setOnFrameRenderedListener({ activeCodec, _, _ ->
                        if (isVisibleRun(run) && codec === activeCodec) {
                            watchdog.onFrameRendered()
                            if (firstFrame) {
                                firstFrame = false
                                Log.i(PLAYBACK_DIAGNOSTICS_TAG,
                                    "route=mirror_decoder generation=$generation event=first_frame codec=$codecName " +
                                        "elapsedMs=${SystemClock.elapsedRealtime() - startedMs}")
                                onFirstFrameRendered()
                            }
                        }
                    }, renderListenerHandler)
                    instance.start()
                    Log.i(PLAYBACK_DIAGNOSTICS_TAG,
                        "route=mirror_decoder generation=$generation event=codec_started codec=$codecName " +
                            "size=${cfg.width}x${cfg.height} lowLatency=$lowLatency")
                    instance
                }.onFailure {
                    logCodecFailure(generation, if (lowLatency) "start_low_latency" else "start_basic", it)
                    runCatching { instance.release() }
                }.getOrNull()
            }

            if (lowLatencySupported) {
                tryStart(true)?.let { return it }
                if (!surface.isValid || !run.running.get()) return null
                Log.i(PLAYBACK_DIAGNOSTICS_TAG,
                    "route=mirror_decoder generation=$generation event=retry_basic codec=$codecName")
            }
            tryStart(false)?.let { return it }
        }
        Log.w(PLAYBACK_DIAGNOSTICS_TAG,
            "route=mirror_decoder generation=$generation event=all_candidates_failed " +
                "mime=${cfg.mimeType} size=${cfg.width}x${cfg.height}")
        return null
    }

    private fun logCodecFailure(generation: Int, operation: String, error: Throwable) {
        val failure = error as? MediaCodec.CodecException
        Log.w(PLAYBACK_DIAGNOSTICS_TAG,
            "route=mirror_decoder generation=$generation event=codec_error operation=$operation " +
                "type=${error.javaClass.simpleName} code=${failure?.errorCode ?: "-"} " +
                "diagnostic=${failure?.diagnosticInfo ?: "-"} recoverable=${failure?.isRecoverable ?: "-"} " +
                "transient=${failure?.isTransient ?: "-"}")
    }

    private fun releaseCodec(activeCodec: MediaCodec, reclaimed: Boolean = false) {
        if (codec === activeCodec) codec = null
        if (!reclaimed) runCatching { activeCodec.stop() }
        runCatching { activeCodec.release() }
    }

    private fun decodeLoop(run: DecodeRun, outputWatchdog: MirrorOutputWatchdog) {
        val bufferInfo = MediaCodec.BufferInfo()
        val frameClock = MirrorFrameClock(audioClockOffsetNs, System::nanoTime)
        val generation = run.stream.generation
        val surface = run.surface
        var activeCodec = codec
        var lastStalledCodec: String? = null
        outputWatchdog.awaitFirstFrame()

        fun recover(codecToRelease: MediaCodec, operation: String, error: IllegalStateException? = null) {
            if (error != null && run.running.get()) logCodecFailure(generation, operation, error)
            val reclaimed = (error as? MediaCodec.CodecException)?.errorCode ==
                MediaCodec.CodecException.ERROR_RECLAIMED
            if (run.running.get()) Log.w(PLAYBACK_DIAGNOSTICS_TAG,
                "route=mirror_decoder generation=$generation event=await_keyframe reason=$operation")
            if (operation == "render_stall") lastStalledCodec = runCatching { codecToRelease.name }.getOrNull()
            releaseCodec(codecToRelease, reclaimed)
            activeCodec = null
            outputWatchdog.beginRecovery()
            frameGate.resetGate()
            renderListenerHandler.post {
                if (isVisibleRun(run)) onRecovering()
            }
        }

        while (run.running.get()) {
            if (outputWatchdog.recoveryExpired()) {
                Log.w(PLAYBACK_DIAGNOSTICS_TAG,
                    "route=mirror_decoder generation=$generation event=recovery_timeout")
                activeCodec?.let { releaseCodec(it) }
                activeCodec = null
                frameGate.resetGate()
                outputWatchdog.parkUntilKeyFrame()
                renderListenerHandler.post {
                    if (isVisibleRun(run)) {
                        onWaitingForKeyFrame()
                    }
                }
            }
            activeCodec?.let { current ->
                try {
                    drainOutputBuffers(run, current, bufferInfo, frameClock, outputWatchdog)
                    if (run.running.get() && outputWatchdog.renderStalled()) recover(current, "render_stall")
                } catch (error: IllegalStateException) {
                    recover(current, "output", error)
                }
            }

            val readBytes = NativeSharedMemory.nativeReadVideoPacket(
                packetBuffer,
                generation,
                run.epoch
            )
            if (readBytes == -1) {
                // Stale generation skipped by ring; retry immediately.
                continue
            }
            if (readBytes <= MirrorFrameGate.PACKET_HEADER_BYTES) {
                if (readBytes <= 0) {
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
                val newCodecConfig = frameGate.onCodecConfig(configBytes)
                if (activeCodec == null) {
                    if (outputWatchdog.waitingForKeyFrame) continue
                    if (surface.isValid && run.running.get()) {
                        val created = createAndConfigureCodec(run, configBytes, outputWatchdog, lastStalledCodec)
                        if (created != null) {
                            if (run.running.get()) {
                                activeCodec = created
                                codec = created
                            } else {
                                releaseCodec(created)
                            }
                        }
                    }
                    // csd-0 was already passed into MediaCodec.configure(); skip duplicate queueInputBuffer.
                    continue
                } else if (!newCodecConfig) {
                    // Identical SPS/PPS already configured on activeCodec; skip duplicate re-config buffer.
                    continue
                }
            }

            if (!frameGate.shouldSubmitFrame(flags)) {
                continue
            }

            if (activeCodec == null && (flags and MirrorFrameGate.FLAG_KEY_FRAME) != 0) {
                val csd = frameGate.getCachedCodecConfig()
                if (surface.isValid && csd != null && run.running.get()) {
                    val wasWaiting = outputWatchdog.waitingForKeyFrame
                    if (!outputWatchdog.resumeAtKeyFrame()) continue
                    if (wasWaiting) Log.i(PLAYBACK_DIAGNOSTICS_TAG,
                        "route=mirror_decoder generation=$generation event=retry_on_keyframe")
                    val created = createAndConfigureCodec(run, csd, outputWatchdog, lastStalledCodec)
                    if (created != null) {
                        if (run.running.get()) {
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
                while (run.running.get() && attempts < 25) {
                    inputIndex = currentCodec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (inputIndex >= 0) break
                    drainOutputBuffers(run, currentCodec, bufferInfo, frameClock, outputWatchdog)
                    attempts++
                }
                if (inputIndex < 0) {
                    if (run.running.get()) recover(currentCodec, "input_congestion")
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

    private fun isVisibleRun(run: DecodeRun): Boolean = activeRun === run && run.running.get()

    private fun drainOutputBuffers(
        run: DecodeRun,
        activeCodec: MediaCodec,
        bufferInfo: MediaCodec.BufferInfo,
        frameClock: MirrorFrameClock,
        outputWatchdog: MirrorOutputWatchdog
    ) {
        val source = run.stream.video
        while (run.running.get()) {
            val outIndex = activeCodec.dequeueOutputBuffer(bufferInfo, 0L)
            if (outIndex >= 0) {
                val videoFrame = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                val renderTime = if (videoFrame) frameClock.renderTimeNs(bufferInfo.presentationTimeUs) else null
                if (renderTime == null) {
                    activeCodec.releaseOutputBuffer(outIndex, videoFrame)
                } else {
                    activeCodec.releaseOutputBuffer(outIndex, renderTime)
                }
                if (videoFrame) outputWatchdog.onOutputReleased()
            } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val outFmt = activeCodec.outputFormat
                val codedWidth = outFmt.getInteger(MediaFormat.KEY_WIDTH, 0)
                val codedHeight = outFmt.getInteger(MediaFormat.KEY_HEIGHT, 0)
                val cropWidth = cropSize(outFmt, "crop-left", "crop-right", codedWidth)
                val cropHeight = cropSize(outFmt, "crop-top", "crop-bottom", codedHeight)
                val size = mirrorPresentationSize(
                    source.width, source.height, codedWidth, codedHeight, cropWidth, cropHeight
                )
                if (size != null) {
                    val (width, height) = size
                    Log.i(TAG, "Mirror output size: ${width}x$height (coded ${codedWidth}x$codedHeight)")
                    onVideoSizeChanged?.let { callback ->
                        renderListenerHandler.post {
                            if (isVisibleRun(run) && codec === activeCodec) callback(width, height)
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
    private fun cropSize(format: MediaFormat, startKey: String, endKey: String, codedSize: Int): Int {
        val start = format.getInteger(startKey, -1)
        val end = format.getInteger(endKey, -1)
        return if (start >= 0 && end in start until codedSize) end - start + 1 else 0
    }
}

/** Shared by the decoder worker and the active codec's render callback. */
internal class MirrorOutputWatchdog(
    private val allowLegacyOutputProgress: Boolean = false,
    private val elapsedMs: () -> Long
) {
    private companion object {
        const val PENDING_FRAME_THRESHOLD = 4
        const val STALL_TIMEOUT_MS = 2_000L
        const val RECOVERY_TIMEOUT_MS = 5_000L
    }

    private var queuedFrames = 0
    private var waitingSinceMs = 0L
    private var firstFrameDeadlineMs: Long? = null
    private var recoveryStartedMs: Long? = null
    private var hasRendered = false
    private var lastOutputMs: Long? = null
    private var retryAtMs: Long? = null

    val waitingForKeyFrame: Boolean
        @Synchronized get() = retryAtMs != null

    @Synchronized
    fun parkUntilKeyFrame() {
        firstFrameDeadlineMs = null
        recoveryStartedMs = null
        lastOutputMs = null
        queuedFrames = 0
        hasRendered = false
        retryAtMs = elapsedMs() + RECOVERY_TIMEOUT_MS
    }

    @Synchronized
    fun resumeAtKeyFrame(): Boolean {
        val retryAt = retryAtMs ?: return true
        val now = elapsedMs()
        if (now < retryAt) return false
        retryAtMs = null
        recoveryStartedMs = now
        return true
    }

    @Synchronized
    fun awaitFirstFrame() {
        firstFrameDeadlineMs = elapsedMs() + RECOVERY_TIMEOUT_MS
    }

    @Synchronized
    fun onFrameQueued() {
        if (queuedFrames == 0) waitingSinceMs = elapsedMs()
        if (queuedFrames < PENDING_FRAME_THRESHOLD) queuedFrames++
    }

    @Synchronized
    fun onOutputReleased() {
        lastOutputMs = elapsedMs()
    }

    @Synchronized
    fun onFrameRendered() {
        // Android may batch or omit individual callbacks. One callback proves progress.
        queuedFrames = 0
        hasRendered = true
        firstFrameDeadlineMs = null
        recoveryStartedMs = null
    }

    @Synchronized
    fun beginRecovery() {
        if (recoveryStartedMs == null) recoveryStartedMs = elapsedMs()
        firstFrameDeadlineMs = null
        lastOutputMs = null
        queuedFrames = 0
    }

    @Synchronized
    fun recoveryExpired(): Boolean {
        val now = elapsedMs()
        return recoveryStartedMs?.let { now - it >= RECOVERY_TIMEOUT_MS } == true ||
            firstFrameDeadlineMs?.let { now >= it } == true
    }

    @Synchronized
    fun renderStalled(): Boolean {
        val threshold = if (hasRendered) PENDING_FRAME_THRESHOLD else 1
        val started = if (allowLegacyOutputProgress && hasRendered) {
            maxOf(waitingSinceMs, lastOutputMs ?: waitingSinceMs)
        } else waitingSinceMs
        return recoveryStartedMs == null && queuedFrames >= threshold &&
            elapsedMs() - started >= STALL_TIMEOUT_MS
    }
}

/** Maps the sender's NTP video PTS to Android's monotonic presentation clock. */
internal class MirrorFrameClock(
    private val audioClockOffsetNs: () -> Long,
    private val nowNs: () -> Long
) {
    private var lastPollNs: Long? = null
    private var offsetNs = Long.MIN_VALUE

    fun renderTimeNs(senderPtsUs: Long): Long? {
        if (senderPtsUs <= 0 || senderPtsUs > Long.MAX_VALUE / 1000L) return null
        val now = nowNs()
        val lastPoll = lastPollNs
        if (lastPoll == null || now - lastPoll >= 100_000_000L) {
            offsetNs = audioClockOffsetNs()
            lastPollNs = now
        }
        if (offsetNs == Long.MIN_VALUE) return null
        val ptsNs = senderPtsUs * 1000L
        // Sender timestamps and offsets are untrusted; reject signed overflow.
        if (offsetNs > 0 && ptsNs > Long.MAX_VALUE - offsetNs) return null
        if (offsetNs < 0 && ptsNs < Long.MIN_VALUE - offsetNs) return null
        val targetNs = ptsNs + offsetNs
        // Compare against a bounded future window without overflowing subtraction.
        if (now > Long.MAX_VALUE - 150_000_000L) return null
        return targetNs.takeIf { it > now && it <= now + 150_000_000L }
    }
}
