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
    val isBuffering: Boolean
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
    fun prepareAndPlay(url: String, startPosition: Double): Boolean
    fun play()
    fun pause()
    fun seekTo(positionSec: Double)
    fun stopAndClear()
    fun currentTelemetry(): DirectPlaybackTelemetry
}

internal fun initialDirectPositionMs(startPosition: Double, durationMs: Long): Long? {
    // AirPlay sends a fraction. Some clients send absolute seconds above 1.
    return when {
        startPosition <= 0.0 -> 0L
        startPosition > 1.0 -> (startPosition * 1000).toLong()
        durationMs > 0L -> (durationMs * startPosition).toLong()
        else -> null
    }
}

interface SurfaceLayerController {
    fun showMirrorSurface()
    fun showDirectSurface()
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

    fun startMirror(): Boolean {
        if (state == PresentationState.MIRROR_ACTIVE) {
            return true
        }
        return switchToMirror()
    }

    fun switchToDirectVideo(url: String, startPosition: Double = 0.0): Boolean {
        if (state == PresentationState.MIRROR_ACTIVE) resumeMirrorAfterDirect = true
        state = PresentationState.SWITCHING_TO_DIRECT
        audioController.setPausedForExternalPlayer(true)
        // Hard VPU rule: release mirror MediaCodec completely before preparing ExoPlayer.
        mirrorEngine.stopAndRelease()
        surfaceController.showDirectSurface()
        val prepared = directEngine.prepareAndPlay(url, startPosition)
        if (!prepared) {
            directEngine.stopAndClear()
            state = PresentationState.RECOVERING
            return false
        }
        state = PresentationState.DIRECT_ACTIVE
        return true
    }

    fun stopDirectVideo() {
        if (state != PresentationState.DIRECT_ACTIVE) return
        if (resumeMirrorAfterDirect) {
            switchToMirror()
        } else {
            returnToStandbyFromDirect()
        }
    }

    private fun switchToMirror(): Boolean {
        resumeMirrorAfterDirect = false
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
        state = PresentationState.IDLE
        directEngine.stopAndClear()
        audioController.setPausedForExternalPlayer(false)
        surfaceController.hideAllSurfaces()
    }

    fun onSessionDied() {
        resumeMirrorAfterDirect = false
        directEngine.stopAndClear()
        mirrorEngine.stopAndRelease()
        audioController.stop()
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
        if (state == PresentationState.DIRECT_ACTIVE) directEngine.play()
    }

    fun pauseDirect() {
        if (state == PresentationState.DIRECT_ACTIVE) directEngine.pause()
    }

    fun seekDirect(positionSec: Double) {
        if (state == PresentationState.DIRECT_ACTIVE) directEngine.seekTo(positionSec)
    }

    fun directTelemetry(): DirectPlaybackTelemetry = directEngine.currentTelemetry()
}
