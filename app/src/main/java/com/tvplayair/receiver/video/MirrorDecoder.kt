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
    private val audioClockOffsetNs: () -> Long,
    private val onVideoSizeChanged: ((Int, Int) -> Unit)? = null,
    private val onFirstFrameRendered: () -> Unit,
    private val onRecovering: () -> Unit,
    private val onRecoveryTimeout: () -> Unit
) : MirrorVideoEngine {

    companion object {
        private const val TAG = "MirrorDecoder"
        private const val MAX_PACKET_BYTES = 480 * 1024
        private const val DEQUEUE_TIMEOUT_US = 4_000L
        private const val IDLE_POLL_SLEEP_MS = 2L
    }

    private data class PendingConfig(val video: MirrorVideoConfig, val generation: Int, val csd: ByteArray)

    private var pendingConfig: PendingConfig? = null
    private val frameGate = MirrorFrameGate()

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
    @Volatile
    private var recoveryCallbackPending = false

    @Synchronized
    fun updateConfig(newConfig: MirrorVideoConfig, generation: Int, codecConfig: ByteArray): Boolean {
        if (pendingConfig == null && generationId.get() == generation && config == newConfig &&
            frameGate.matchesCodecConfig(codecConfig)
        ) return false
        pendingConfig = PendingConfig(newConfig, generation, codecConfig)
        if (stopAndRelease()) applyPendingConfig()
        return true
    }

    private fun applyPendingConfig() {
        val pending = pendingConfig ?: return
        config = pending.video
        generationId.set(pending.generation)
        frameGate.clearAll()
        frameGate.onCodecConfig(pending.csd)
        pendingConfig = null
    }

    @Synchronized
    override fun start(discardPendingFrames: Boolean): Boolean {
        if (running.get()) return true
        // A timed-out stop must not start another worker over the shared packet buffer.
        if (workerThread?.isAlive == true) {
            Log.w(PLAYBACK_DIAGNOSTICS_TAG,
                "route=mirror_decoder generation=${generationId.get()} event=previous_worker_alive")
            return false
        }
        workerThread = null
        applyPendingConfig()
        val surface = surfaceProvider()
        if (surface == null || !surface.isValid) {
            Log.w(PLAYBACK_DIAGNOSTICS_TAG,
                "route=mirror_decoder generation=${generationId.get()} event=surface_unavailable")
            return false
        }

        recoveryCallbackPending = false
        frameGate.resetGate()
        val watchdog = MirrorOutputWatchdog(SystemClock::elapsedRealtime)
        val initialCsd = frameGate.getCachedCodecConfig()
        if (initialCsd != null) {
            val createdCodec = createAndConfigureCodec(surface, config, initialCsd, watchdog) ?: return false
            codec = createdCodec
        }

        running.set(true)
        workerThread = Thread({
            try {
                if (discardPendingFrames) NativeSharedMemory.nativeDiscardPendingVideoPackets()
                decodeLoop(watchdog)
            } finally {
                // The worker owns MediaCodec until its last dequeue/queue has returned.
                running.set(false)
                codec?.let { releaseCodec(it) }
            }
        }, "TVPlayAir-MirrorDecoder").apply { start() }
        return true
    }

    @Synchronized
    override fun stopAndRelease(): Boolean {
        recoveryCallbackPending = false
        running.set(false)
        workerThread?.let { thread ->
            thread.interrupt()
            runCatching { thread.join(1_000L) }
        }
        if (workerThread?.isAlive == true) {
            // Do not release a codec that a blocked worker may still be using.
            Log.w(PLAYBACK_DIAGNOSTICS_TAG,
                "route=mirror_decoder generation=${generationId.get()} event=worker_stop_timeout")
            return false
        }
        workerThread = null
        codec?.let { releaseCodec(it) }
        return true
    }

    private fun createAndConfigureCodec(
        surface: Surface,
        cfg: MirrorVideoConfig,
        csdBytes: ByteArray,
        watchdog: MirrorOutputWatchdog,
        lastStalledCodec: String? = null
    ): MediaCodec? {
        val generation = generationId.get()
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
        }.onFailure { logCodecFailure("enumerate", it) }.getOrNull() ?: return null
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
            if (!surface.isValid || generationId.get() != generation) return null
            val codecName = candidate.name
            val lowLatencySupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                runCatching {
                    candidate.getCapabilitiesForType(cfg.mimeType)
                        .isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
                }.getOrDefault(false)

            fun tryStart(lowLatency: Boolean): MediaCodec? {
                val instance = runCatching { MediaCodec.createByCodecName(codecName) }
                    .onFailure { logCodecFailure("create_candidate", it) }.getOrNull()
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
                        synchronized(codecLock) {
                            if (running.get() && codec === activeCodec && generationId.get() == generation) {
                                watchdog.onFrameRendered()
                                if (!firstFrame) return@synchronized
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
                    logCodecFailure(if (lowLatency) "start_low_latency" else "start_basic", it)
                    runCatching { instance.release() }
                }.getOrNull()
            }

            if (lowLatencySupported) {
                tryStart(true)?.let { return it }
                if (!surface.isValid || generationId.get() != generation) return null
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
        }
        if (!reclaimed) runCatching { activeCodec.stop() }
        runCatching { activeCodec.release() }
    }

    private fun decodeLoop(outputWatchdog: MirrorOutputWatchdog) {
        val bufferInfo = MediaCodec.BufferInfo()
        val frameClock = MirrorFrameClock(audioClockOffsetNs, System::nanoTime)
        val worker = Thread.currentThread()
        val generation = generationId.get()
        var activeCodec = codec
        var lastStalledCodec: String? = null
        outputWatchdog.awaitFirstFrame()

        fun recover(codecToRelease: MediaCodec, operation: String, error: IllegalStateException? = null) {
            if (error != null && running.get()) logCodecFailure(operation, error)
            val reclaimed = (error as? MediaCodec.CodecException)?.errorCode ==
                MediaCodec.CodecException.ERROR_RECLAIMED
            if (running.get()) Log.w(PLAYBACK_DIAGNOSTICS_TAG,
                "route=mirror_decoder generation=${generationId.get()} event=await_keyframe reason=$operation")
            if (operation == "render_stall") lastStalledCodec = runCatching { codecToRelease.name }.getOrNull()
            releaseCodec(codecToRelease, reclaimed)
            activeCodec = null
            outputWatchdog.beginRecovery()
            frameGate.resetGate()
            renderListenerHandler.post {
                if (running.get() && workerThread === worker && generationId.get() == generation) onRecovering()
            }
        }

        while (running.get()) {
            if (outputWatchdog.recoveryExpired()) {
                Log.w(PLAYBACK_DIAGNOSTICS_TAG,
                    "route=mirror_decoder generation=$generation event=recovery_timeout")
                recoveryCallbackPending = true
                renderListenerHandler.post {
                    // The worker's finally block has already set running=false by the time
                    // this runs; use the pending recovery token, not running, to deliver it.
                    if (shouldDeliverRecoveryTimeout(
                            recoveryCallbackPending, workerThread === worker,
                            generationId.get() == generation
                        )
                    ) {
                        recoveryCallbackPending = false
                        onRecoveryTimeout()
                    }
                }
                return
            }
            activeCodec?.let { current ->
                try {
                    drainOutputBuffers(current, bufferInfo, frameClock)
                    if (running.get() && outputWatchdog.renderStalled()) recover(current, "render_stall")
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
                val newCodecConfig = frameGate.onCodecConfig(configBytes)
                if (activeCodec == null) {
                    val surface = surfaceProvider()
                    if (surface != null && surface.isValid && running.get()) {
                        val created = createAndConfigureCodec(surface, config, configBytes, outputWatchdog, lastStalledCodec)
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
                } else if (!newCodecConfig) {
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
                    val created = createAndConfigureCodec(surface, config, csd, outputWatchdog, lastStalledCodec)
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
                    drainOutputBuffers(currentCodec, bufferInfo, frameClock)
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
        frameClock: MirrorFrameClock
    ) {
        while (running.get()) {
            val outIndex = activeCodec.dequeueOutputBuffer(bufferInfo, 0L)
            if (outIndex >= 0) {
                val videoFrame = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                val renderTime = if (videoFrame) frameClock.renderTimeNs(bufferInfo.presentationTimeUs) else null
                if (renderTime == null) {
                    activeCodec.releaseOutputBuffer(outIndex, videoFrame)
                } else {
                    activeCodec.releaseOutputBuffer(outIndex, renderTime)
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

/** Shared by the decoder worker and the active codec's render callback. */
internal class MirrorOutputWatchdog(private val elapsedMs: () -> Long) {
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
        return recoveryStartedMs == null && queuedFrames >= threshold &&
            elapsedMs() - waitingSinceMs >= STALL_TIMEOUT_MS
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

/** Recovery callbacks may run after the worker has exited and cleared running. */
internal fun shouldDeliverRecoveryTimeout(
    pending: Boolean, sameWorker: Boolean, sameGeneration: Boolean
): Boolean = pending && sameWorker && sameGeneration
