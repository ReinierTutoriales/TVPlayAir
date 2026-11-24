package com.tvplayair.receiver.video

enum class PresentationState {
    IDLE,
    MIRROR_PREPARING,
    MIRROR_ACTIVE,
    MIRROR_RECOVERING,
    MIRROR_WAITING_KEYFRAME,
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
    val artwork: ByteArray? = null,
    val livePositionSec: Double? = null
)

interface AudioHandoffController {
    fun setPausedForExternalPlayer(paused: Boolean)
    fun resumeAfterSenderStop() = setPausedForExternalPlayer(false)
    fun stop()
}

interface MirrorVideoEngine {
    fun start(discardPendingFrames: Boolean = false): Boolean
    fun stopAndRelease()

    /** Returns true if released now. Otherwise invokes the callback on the presentation thread. */
    fun stopBeforeDirect(onReleased: () -> Unit): Boolean {
        stopAndRelease()
        return true
    }
}

interface DirectVideoEngine {
    fun prepare(video: DirectPlaybackStart): Boolean
    fun play()
    fun pause()
    fun seekTo(positionSec: Double)
    fun stopAndClear(releasePlayer: Boolean = false)
    fun currentTelemetry(): DirectPlaybackTelemetry
}

internal fun timelineSeekStep(telemetry: DirectPlaybackTelemetry, direction: Int): Double? {
    val duration = telemetry.durationSec
    val position = telemetry.positionSec
    if (!telemetry.canSeek || !duration.isFinite() || duration <= 0.0 || !position.isFinite()) return null
    return (position + direction * duration / 100.0).coerceIn(0.0, duration)
}

internal fun validDirectSeekPosition(positionSec: Double): Boolean =
    positionSec.isFinite() && positionSec >= 0.0

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
    fun hasMirrorSurface(): Boolean
    fun coverMirrorSurface()
    fun revealMirrorSurface()
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
    private val surfaceController: SurfaceLayerController,
    private val onDeferredDirectFailure: (Long) -> Unit = {}
) {
    var state: PresentationState = PresentationState.IDLE
        private set
    private var resumeMirrorAfterDirect = false
    private var discardMirrorFrames = false
    private var directPlaybackId: Long? = null
    private class DirectHandoff(var video: DirectPlaybackStart) {
        var seekPositionSec: Double? = null
    }
    private var directHandoff: DirectHandoff? = null

    internal fun cancelPendingDirectHandoff() {
        directHandoff = null
    }

    val hasDirectPlayback: Boolean
        get() = state == PresentationState.SWITCHING_TO_DIRECT ||
            state == PresentationState.DIRECT_ACTIVE || state == PresentationState.DIRECT_WITHOUT_SURFACE

    fun onDirectMediaReady(playbackId: Long): Boolean {
        if (state != PresentationState.SWITCHING_TO_DIRECT || directPlaybackId != playbackId ||
            directHandoff != null
        ) return false
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
        if (isMirrorRequested()) {
            if (!reconfigured) return true
            state = PresentationState.SWITCHING_TO_MIRROR
            surfaceController.showMirrorSurface()
            return startOnMirrorSurface()
        }
        return switchToMirror()
    }

    fun switchToDirectVideo(video: DirectPlaybackStart, mirrorAvailable: Boolean = false): Boolean {
        if (video.url.isBlank() || !video.airPlayPosition.isFinite()) return false
        val playbackId = video.playbackId
        val replacingDirect = hasDirectPlayback
        if (isMirrorRequested() || mirrorAvailable) resumeMirrorAfterDirect = true
        state = PresentationState.SWITCHING_TO_DIRECT
        directPlaybackId = playbackId
        directHandoff?.let { handoff ->
            handoff.video = video
            handoff.seekPositionSec = null
            return true
        }
        if (replacingDirect) return prepareDirect(video)

        audioController.setPausedForExternalPlayer(true)
        val handoff = DirectHandoff(video)
        directHandoff = handoff
        if (!mirrorEngine.stopBeforeDirect {
                if (directHandoff === handoff) {
                    directHandoff = null
                    if (prepareDirect(handoff.video)) {
                        handoff.seekPositionSec?.let(directEngine::seekTo)
                    } else {
                        onDeferredDirectFailure(handoff.video.playbackId)
                    }
                }
            }
        ) {
            surfaceController.coverMirrorSurface()
            return true
        }
        directHandoff = null
        return prepareDirect(video)
    }

    private fun prepareDirect(video: DirectPlaybackStart): Boolean {
        val playbackId = video.playbackId
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

    private fun restoreAfterDirect(senderStop: Boolean = false) {
        cancelPendingDirectHandoff()
        if (resumeMirrorAfterDirect) {
            switchToMirror(discardPendingFrames = true)
        } else {
            returnToStandbyFromDirect(senderStop)
        }
    }

    private fun switchToMirror(discardPendingFrames: Boolean = false): Boolean {
        cancelPendingDirectHandoff()
        resumeMirrorAfterDirect = false
        directPlaybackId = null
        state = PresentationState.SWITCHING_TO_MIRROR
        discardMirrorFrames = discardPendingFrames
        directEngine.stopAndClear(releasePlayer = true)
        surfaceController.showMirrorSurface()
        val started = startOnMirrorSurface()
        audioController.setPausedForExternalPlayer(false)
        return started
    }

    private fun startOnMirrorSurface(): Boolean {
        if (!surfaceController.hasMirrorSurface()) return true
        val started = mirrorEngine.start(discardMirrorFrames)
        discardMirrorFrames = false
        if (!started) surfaceController.hideAllSurfaces()
        state = if (started) PresentationState.MIRROR_PREPARING else PresentationState.RECOVERING
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
        if (!isMirrorRequested() && state != PresentationState.RECOVERING) return
        mirrorEngine.stopAndRelease()
        surfaceController.hideAllSurfaces()
        state = PresentationState.IDLE
    }

    fun onSessionDied() {
        cancelPendingDirectHandoff()
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
        if (state == PresentationState.SWITCHING_TO_MIRROR) startOnMirrorSurface()
        else if (!hasDirectPlayback) startMirror()
    }

    fun onMirrorFrameRendered(): Boolean {
        if (state != PresentationState.MIRROR_PREPARING && state != PresentationState.MIRROR_RECOVERING &&
            state != PresentationState.MIRROR_WAITING_KEYFRAME
        ) return false
        state = PresentationState.MIRROR_ACTIVE
        surfaceController.revealMirrorSurface()
        return true
    }

    fun onMirrorRecovering(): Boolean {
        if (!isMirrorRequested() || state == PresentationState.SWITCHING_TO_MIRROR) return false
        state = PresentationState.MIRROR_RECOVERING
        surfaceController.coverMirrorSurface()
        return true
    }

    fun onMirrorWaitingForKeyFrame(): Boolean {
        if (!isMirrorRequested() || state == PresentationState.SWITCHING_TO_MIRROR) return false
        state = PresentationState.MIRROR_WAITING_KEYFRAME
        surfaceController.coverMirrorSurface()
        return true
    }

    private fun isMirrorRequested(): Boolean = when (state) {
        PresentationState.SWITCHING_TO_MIRROR, PresentationState.MIRROR_PREPARING,
        PresentationState.MIRROR_ACTIVE, PresentationState.MIRROR_RECOVERING,
        PresentationState.MIRROR_WAITING_KEYFRAME -> true
        else -> false
    }

    fun onDirectSurfaceCreated(): Boolean {
        if (state != PresentationState.DIRECT_WITHOUT_SURFACE) return false
        state = PresentationState.SWITCHING_TO_DIRECT
        return true
    }

    fun onSurfaceDestroyed(isDirectSurface: Boolean) {
        if (isDirectSurface && hasDirectPlayback) {
            state = PresentationState.DIRECT_WITHOUT_SURFACE
        } else if (!isDirectSurface && isMirrorRequested()) {
            mirrorEngine.stopAndRelease()
            surfaceController.hideAllSurfaces()
            state = PresentationState.IDLE
        }
    }

    fun playDirect() = setDirectIntent("play", directEngine::play)

    fun pauseDirect() = setDirectIntent("pause", directEngine::pause)

    private fun setDirectIntent(command: String, applyToPlayer: () -> Unit) {
        if (!hasDirectPlayback) return
        val handoff = directHandoff
        if (handoff == null) applyToPlayer()
        else handoff.video = handoff.video.withPlaybackIntent(command)
    }

    fun seekDirect(positionSec: Double) {
        // Sender commands are untrusted. Never forward NaN, infinity or negative
        // positions into Media3's seek path.
        if (hasDirectPlayback && validDirectSeekPosition(positionSec)) {
            val handoff = directHandoff
            if (handoff == null) directEngine.seekTo(positionSec)
            else handoff.seekPositionSec = positionSec
        }
    }

    fun directTelemetry(): DirectPlaybackTelemetry = directEngine.currentTelemetry()
}
