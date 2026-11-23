package com.tvplayair.receiver.video

enum class PresentationState {
    IDLE,
    MIRROR_ACTIVE,
    SWITCHING_TO_DIRECT,
    DIRECT_ACTIVE,
    DIRECT_WITHOUT_SURFACE,
    SWITCHING_TO_MIRROR,
    RECOVERING
}

data class DirectPlaybackTelemetry(
    val durationSec: Double,
    val positionSec: Double,
    val rate: Float,
    val isPlaying: Boolean,
    val isBuffering: Boolean,
    val isReady: Boolean = isPlaying || durationSec > 0.0,
    val title: String? = null,
    val canSeek: Boolean = false,
    val playWhenReady: Boolean = isPlaying,
    val audioOnly: Boolean = false,
    val artist: String? = null,
    val album: String? = null,
    val artwork: ByteArray? = null
)

interface AudioHandoffController {
    fun setPausedForExternalPlayer(paused: Boolean)
    fun resumeAfterSenderStop() = setPausedForExternalPlayer(false)
    fun stop()
}

interface MirrorVideoEngine {
    fun start(discardPendingFrames: Boolean = false): Boolean
    fun stopAndRelease()
}

interface DirectVideoEngine {
    fun prepare(video: DirectPlaybackStart): Boolean
    fun play()
    fun pause()
    fun seekTo(positionSec: Double)
    fun stopAndClear(releasePlayer: Boolean = false)
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
        get() = state == PresentationState.SWITCHING_TO_DIRECT ||
            state == PresentationState.DIRECT_ACTIVE || state == PresentationState.DIRECT_WITHOUT_SURFACE

    fun onDirectMediaReady(playbackId: Long): Boolean {
        if (state != PresentationState.SWITCHING_TO_DIRECT || directPlaybackId != playbackId) return false
        state = PresentationState.DIRECT_ACTIVE
        surfaceController.revealDirectSurface()
        return true
    }

    fun onDirectStartupTimeout(playbackId: Long): Boolean =
        state == PresentationState.SWITCHING_TO_DIRECT && finishDirectPlayback(playbackId)

    fun finishDirectPlayback(playbackId: Long, senderStop: Boolean = false): Boolean {
        if (!hasDirectPlayback || directPlaybackId != playbackId) return false
        restoreAfterDirect(senderStop)
        return true
    }

    fun startMirror(reconfigured: Boolean = false, opensProjection: Boolean = true): Boolean {
        if (!opensProjection && hasDirectPlayback) {
            onCachedMirrorAvailable()
            return false
        }
        if (state == PresentationState.MIRROR_ACTIVE) {
            if (!reconfigured || mirrorEngine.start()) return true
            state = PresentationState.RECOVERING
            return false
        }
        return switchToMirror()
    }

    fun switchToDirectVideo(video: DirectPlaybackStart, mirrorAvailable: Boolean = false): Boolean {
        if (video.url.isBlank() || !video.airPlayPosition.isFinite()) return false
        val playbackId = video.playbackId
        val replacingDirect = hasDirectPlayback
        if (state == PresentationState.MIRROR_ACTIVE || mirrorAvailable) resumeMirrorAfterDirect = true
        state = PresentationState.SWITCHING_TO_DIRECT
        directPlaybackId = playbackId
        if (!replacingDirect) {
            audioController.setPausedForExternalPlayer(true)
            // Hard VPU rule: release mirror MediaCodec before preparing ExoPlayer.
            mirrorEngine.stopAndRelease()
        }
        surfaceController.showDirectSurface()
        val prepared = directEngine.prepare(video)
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

    private fun restoreAfterDirect(senderStop: Boolean = false) {
        if (resumeMirrorAfterDirect) {
            switchToMirror(discardPendingFrames = true)
        } else {
            returnToStandbyFromDirect(senderStop)
        }
    }

    private fun switchToMirror(discardPendingFrames: Boolean = false): Boolean {
        resumeMirrorAfterDirect = false
        directPlaybackId = null
        state = PresentationState.SWITCHING_TO_MIRROR
        directEngine.stopAndClear(releasePlayer = true)
        surfaceController.showMirrorSurface()
        val started = mirrorEngine.start(discardPendingFrames)
        audioController.setPausedForExternalPlayer(false)
        state = if (started) PresentationState.MIRROR_ACTIVE else PresentationState.RECOVERING
        return started
    }

    private fun returnToStandbyFromDirect(senderStop: Boolean) {
        resumeMirrorAfterDirect = false
        directPlaybackId = null
        state = PresentationState.IDLE
        directEngine.stopAndClear()
        if (senderStop) audioController.resumeAfterSenderStop()
        else audioController.setPausedForExternalPlayer(false)
        surfaceController.hideAllSurfaces()
    }

    fun onCachedMirrorAvailable() {
        if (hasDirectPlayback) resumeMirrorAfterDirect = true
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

    fun onMirrorSurfaceCreated() {
        if (!hasDirectPlayback && state != PresentationState.SWITCHING_TO_MIRROR) startMirror()
    }

    fun onDirectSurfaceCreated(): Boolean {
        if (state != PresentationState.DIRECT_WITHOUT_SURFACE) return false
        state = PresentationState.SWITCHING_TO_DIRECT
        return true
    }

    fun onSurfaceDestroyed(isDirectSurface: Boolean) {
        if (isDirectSurface && hasDirectPlayback) {
            state = PresentationState.DIRECT_WITHOUT_SURFACE
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
