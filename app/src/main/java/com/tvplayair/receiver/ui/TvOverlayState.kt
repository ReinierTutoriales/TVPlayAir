package com.tvplayair.receiver.ui

import com.tvplayair.receiver.audio.OboeAudioConsumer
import com.tvplayair.receiver.session.RaopNowPlaying
import com.tvplayair.receiver.video.DirectPlaybackTelemetry
import com.tvplayair.receiver.video.PresentationState

enum class ResolutionPreset(val width: Int, val height: Int, val label: String) {
    UHD_4K(3840, 2160, "4K"),
    FHD_1080P(1920, 1080, "1080p");

    fun next(): ResolutionPreset = if (this == UHD_4K) FHD_1080P else UHD_4K
}

data class TvOverlayState(
    val visible: Boolean = false,
    val resolutionPreset: ResolutionPreset = ResolutionPreset.UHD_4K,
    val hevcEnabled: Boolean = true,
    val audioOffsetMs: Int = 0,
    val presentationState: PresentationState = PresentationState.IDLE,
    val connectedClient: String? = null,
    val receiverName: String = "TVPlayAir",
    val projectionHome: Boolean = false,
    val audioGeneration: Int? = null,
    val audioControlsAvailable: Boolean = false,
    val nowPlaying: RaopNowPlaying? = null,
    val directAudio: DirectPlaybackTelemetry? = null,
    val mirrorControlsVisible: Boolean = false,
    val videoControls: DirectPlaybackTelemetry? = null,
    private val awaitingAudioPresentation: Boolean = false,
    private val pendingNowPlaying: RaopNowPlaying? = null
) {
    companion object {
        const val OFFSET_STEP_MS = 10
    }

    val showStandby: Boolean
        get() = presentationState == PresentationState.IDLE ||
            presentationState == PresentationState.RECOVERING

    val audioProjectionActive: Boolean
        get() = audioGeneration != null

    val showingAudio: Boolean
        get() = showStandby && (audioProjectionActive || nowPlaying != null)

    val showingDirectAudio: Boolean
        get() = !visible && presentationState == PresentationState.DIRECT_ACTIVE && directAudio != null

    val showingMirrorControls: Boolean
        get() = !visible && presentationState == PresentationState.MIRROR_ACTIVE && mirrorControlsVisible

    val showingDirectControls: Boolean
        get() = showingDirectAudio || showingVideoControls

    val showingVideoControls: Boolean
        get() = !visible && presentationState == PresentationState.DIRECT_ACTIVE &&
            directAudio == null && videoControls != null

    fun withDirectTelemetry(telemetry: DirectPlaybackTelemetry): TvOverlayState =
        if (presentationState != PresentationState.DIRECT_ACTIVE) this else copy(
            directAudio = telemetry.takeIf { it.audioOnly },
            videoControls = telemetry.takeIf { !it.audioOnly && videoControls != null }
        )

    fun withPresentation(state: PresentationState): TvOverlayState {
        val showAudio = state == PresentationState.IDLE || state == PresentationState.RECOVERING
        val enteringVideo = state != presentationState &&
            (state == PresentationState.SWITCHING_TO_DIRECT ||
                state == PresentationState.SWITCHING_TO_MIRROR ||
                state == PresentationState.MIRROR_ACTIVE ||
                state == PresentationState.DIRECT_ACTIVE && showStandby)
        return copy(
            visible = visible && !enteringVideo,
            presentationState = state,
            nowPlaying = if (showAudio) pendingNowPlaying ?: nowPlaying else null,
            directAudio = directAudio.takeIf { state == PresentationState.DIRECT_ACTIVE },
            mirrorControlsVisible = mirrorControlsVisible && state == PresentationState.MIRROR_ACTIVE,
            videoControls = videoControls.takeIf { state == PresentationState.DIRECT_ACTIVE },
            awaitingAudioPresentation = awaitingAudioPresentation && !showAudio,
            pendingNowPlaying = pendingNowPlaying.takeUnless { showAudio }
        )
    }

    fun canSendAudioControl(generation: Int): Boolean =
        audioGeneration == generation && audioControlsAvailable &&
            (showStandby || presentationState == PresentationState.MIRROR_ACTIVE)

    fun withAudioStreamStarted(generation: Int): TvOverlayState = copy(
        audioGeneration = generation,
        awaitingAudioPresentation = !showStandby
    )

    fun withAudioStreamStopped(): TvOverlayState = clearAudioMetadata().copy(
        audioGeneration = null,
        audioControlsAvailable = false
    )

    fun withNowPlaying(metadata: RaopNowPlaying?): TvOverlayState = when {
        showStandby -> copy(nowPlaying = metadata)
        awaitingAudioPresentation -> copy(pendingNowPlaying = metadata)
        else -> this
    }

    fun clearAudioMetadata(): TvOverlayState = copy(
        nowPlaying = null,
        awaitingAudioPresentation = false,
        pendingNowPlaying = null
    )

    fun stepAudioOffset(deltaMs: Int): TvOverlayState = copy(
        audioOffsetMs = (audioOffsetMs + deltaMs).coerceIn(
            OboeAudioConsumer.MIN_OFFSET_MS,
            OboeAudioConsumer.MAX_OFFSET_MS
        )
    )

    fun toggleResolution(): TvOverlayState = copy(resolutionPreset = resolutionPreset.next())

    fun toggleHevc(): TvOverlayState = copy(hevcEnabled = !hevcEnabled)
}
