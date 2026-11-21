package com.tvplayair.receiver.video

enum class PresentationState {
    IDLE,
    MIRROR_ACTIVE,
    SWITCHING_TO_DIRECT,
    DIRECT_ACTIVE,
    SWITCHING_TO_MIRROR,
    RECOVERING
}

data class DirectPlaybackTelemetry(
    val durationSec: Double,
    val positionSec: Double,
    val rate: Float,
    val isPlaying: Boolean,
    val isBuffering: Boolean,
    val isReady: Boolean = isPlaying || durationSec > 0.0
)

interface AudioHandoffController {
    fun setPausedForExternalPlayer(paused: Boolean)
    fun stop()
}

interface MirrorVideoEngine {
    fun start(): Boolean
    fun stopAndRelease()
}

interface DirectVideoEngine {
    fun prepareAndPlay(url: String, startPosition: Double, playbackId: Long): Boolean
    fun play()
    fun pause()
    fun seekTo(positionSec: Double)
    fun stopAndClear()
    fun currentTelemetry(): DirectPlaybackTelemetry
}

internal fun initialDirectPositionMs(startPosition: Double, durationMs: Long): Long? {
    // AirPlay sends a fraction. Some clients send absolute seconds above 1.
    return when {
        !startPosition.isFinite() -> null
        startPosition <= 0.0 -> 0L
        startPosition > 1.0 -> (startPosition * 1000).coerceAtMost(Long.MAX_VALUE.toDouble()).toLong()
        durationMs > 0L -> (durationMs * startPosition).coerceAtMost(durationMs.toDouble()).toLong()
        else -> null
    }
}

interface SurfaceLayerController {
    fun showMirrorSurface()
    fun showDirectSurface()
    fun revealDirectSurface()
    fun hideAllSurfaces()
}

/**
 * Explicit VPU state machine guaranteeing single active 4K hardware decode path.
 * Always stops and releases MirrorDecoder before preparing ExoPlayer.
 */
class VideoPresentationCoordinator(
    private val mirrorEngine: MirrorVideoEngine,
    private val directEngine: DirectVideoEngine,
    private val audioController: AudioHandoffController,
    private val surfaceController: SurfaceLayerController
) {
    var state: PresentationState = PresentationState.IDLE
        private set
    private var resumeMirrorAfterDirect = false
    private var directPlaybackId: Long? = null

    val hasDirectPlayback: Boolean
        get() = state == PresentationState.SWITCHING_TO_DIRECT || state == PresentationState.DIRECT_ACTIVE

    fun onDirectMediaReady(playbackId: Long): Boolean {
        if (state != PresentationState.SWITCHING_TO_DIRECT || directPlaybackId != playbackId) return false
        state = PresentationState.DIRECT_ACTIVE
        surfaceController.revealDirectSurface()
        return true
    }

    fun onDirectStartupTimeout(playbackId: Long): Boolean =
        state == PresentationState.SWITCHING_TO_DIRECT && finishDirectPlayback(playbackId)

    fun finishDirectPlayback(playbackId: Long): Boolean {
        if (!hasDirectPlayback || directPlaybackId != playbackId) return false
        restoreAfterDirect()
        return true
    }

    fun startMirror(reconfigured: Boolean = false): Boolean {
        if (state == PresentationState.MIRROR_ACTIVE) {
            if (!reconfigured || mirrorEngine.start()) return true
            state = PresentationState.RECOVERING
            return false
        }
        return switchToMirror()
    }

    fun switchToDirectVideo(url: String, startPosition: Double, playbackId: Long): Boolean {
        if (url.isBlank() || !startPosition.isFinite()) return false
        val replacingDirect = hasDirectPlayback
        if (state == PresentationState.MIRROR_ACTIVE) resumeMirrorAfterDirect = true
        state = PresentationState.SWITCHING_TO_DIRECT
        directPlaybackId = playbackId
        if (!replacingDirect) {
            audioController.setPausedForExternalPlayer(true)
            // Hard VPU rule: release mirror MediaCodec before preparing ExoPlayer.
            mirrorEngine.stopAndRelease()
        }
        surfaceController.showDirectSurface()
        val prepared = directEngine.prepareAndPlay(url, startPosition, playbackId)
        if (!hasDirectPlayback || directPlaybackId != playbackId) return false
        if (!prepared) {
            // A failed replacement must not leave an old direct stream visible.
            restoreAfterDirect()
            return false
        }
        return true
    }

    fun stopDirectVideo() {
        if (!hasDirectPlayback) return
        restoreAfterDirect()
    }

    private fun restoreAfterDirect() {
        if (resumeMirrorAfterDirect) {
            switchToMirror()
        } else {
            returnToStandbyFromDirect()
        }
    }

    private fun switchToMirror(): Boolean {
        resumeMirrorAfterDirect = false
        directPlaybackId = null
        state = PresentationState.SWITCHING_TO_MIRROR
        directEngine.stopAndClear()
        surfaceController.showMirrorSurface()
        val started = mirrorEngine.start()
        audioController.setPausedForExternalPlayer(false)
        state = if (started) PresentationState.MIRROR_ACTIVE else PresentationState.RECOVERING
        return started
    }

    private fun returnToStandbyFromDirect() {
        resumeMirrorAfterDirect = false
        directPlaybackId = null
        state = PresentationState.IDLE
        directEngine.stopAndClear()
        audioController.setPausedForExternalPlayer(false)
        surfaceController.hideAllSurfaces()
    }

    fun onMirrorDisconnected() {
        resumeMirrorAfterDirect = false
        if (state != PresentationState.MIRROR_ACTIVE && state != PresentationState.RECOVERING) return
        mirrorEngine.stopAndRelease()
        surfaceController.hideAllSurfaces()
        state = PresentationState.IDLE
    }

    fun onSessionDied() {
        resumeMirrorAfterDirect = false
        directPlaybackId = null
        directEngine.stopAndClear()
        mirrorEngine.stopAndRelease()
        audioController.stop()
        audioController.setPausedForExternalPlayer(false)
        surfaceController.hideAllSurfaces()
        state = PresentationState.IDLE
    }

    fun onSurfaceDestroyed(isDirectSurface: Boolean) {
        if (isDirectSurface &&
            (state == PresentationState.DIRECT_ACTIVE ||
                state == PresentationState.SWITCHING_TO_DIRECT)
        ) {
            returnToStandbyFromDirect()
        } else if (!isDirectSurface &&
            (state == PresentationState.MIRROR_ACTIVE ||
                state == PresentationState.SWITCHING_TO_MIRROR)
        ) {
            mirrorEngine.stopAndRelease()
            state = PresentationState.IDLE
        }
    }

    fun playDirect() {
        if (hasDirectPlayback) directEngine.play()
    }

    fun pauseDirect() {
        if (hasDirectPlayback) directEngine.pause()
    }

    fun seekDirect(positionSec: Double) {
        if (hasDirectPlayback) directEngine.seekTo(positionSec)
    }

    fun directTelemetry(): DirectPlaybackTelemetry = directEngine.currentTelemetry()
}
