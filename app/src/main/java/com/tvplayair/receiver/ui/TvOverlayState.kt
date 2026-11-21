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
    val audioProjectionActive: Boolean = false,
    val nowPlaying: RaopNowPlaying? = null,
    val videoControls: DirectPlaybackTelemetry? = null
) {
    companion object {
        const val OFFSET_STEP_MS = 10
    }

    val showStandby: Boolean
        get() = presentationState == PresentationState.IDLE ||
            presentationState == PresentationState.RECOVERING

    val showingAudio: Boolean
        get() = showStandby && (audioProjectionActive || nowPlaying != null)

    val showingVideoControls: Boolean
        get() = !visible && presentationState == PresentationState.DIRECT_ACTIVE && videoControls != null

    fun stepAudioOffset(deltaMs: Int): TvOverlayState = copy(
        audioOffsetMs = (audioOffsetMs + deltaMs).coerceIn(
            OboeAudioConsumer.MIN_OFFSET_MS,
            OboeAudioConsumer.MAX_OFFSET_MS
        )
    )

    fun toggleResolution(): TvOverlayState = copy(resolutionPreset = resolutionPreset.next())

    fun toggleHevc(): TvOverlayState = copy(hevcEnabled = !hevcEnabled)
}
