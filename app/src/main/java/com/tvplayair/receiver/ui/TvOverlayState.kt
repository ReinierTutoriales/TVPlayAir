package com.tvplayair.receiver.ui

import com.tvplayair.receiver.audio.OboeAudioConsumer
import com.tvplayair.receiver.video.PresentationState

enum class ResolutionPreset(val width: Int, val height: Int, val label: String) {
    UHD_4K(3840, 2160, "4K (3840x2160)"),
    FHD_1080P(1920, 1080, "1080p (1920x1080)");

    fun next(): ResolutionPreset = if (this == UHD_4K) FHD_1080P else UHD_4K
}

data class TvOverlayState(
    val visible: Boolean = false,
    val resolutionPreset: ResolutionPreset = ResolutionPreset.UHD_4K,
    val hevcEnabled: Boolean = true,
    val audioOffsetMs: Int = 0,
    val presentationState: PresentationState = PresentationState.IDLE,
    val connectedClient: String? = null,
    val oboeExclusive: Boolean = false,
    val oboeSampleRate: Int = 48000,
    val oboeUnderruns: Long = 0L,
    val oboeRecoveries: Long = 0L,
    val mirrorDroppedNonIdr: Long = 0L,
    val hidAvailable: Boolean = false,
    val localIpAddress: String = "Connecting...",
    val receiverName: String = "TVPlayAir"
) {
    companion object {
        const val OFFSET_STEP_MS = 10
    }

    fun stepAudioOffset(deltaMs: Int): TvOverlayState = copy(
        audioOffsetMs = (audioOffsetMs + deltaMs).coerceIn(
            OboeAudioConsumer.MIN_OFFSET_MS,
            OboeAudioConsumer.MAX_OFFSET_MS
        )
    )

    fun toggleResolution(): TvOverlayState = copy(resolutionPreset = resolutionPreset.next())

    fun toggleHevc(): TvOverlayState = copy(hevcEnabled = !hevcEnabled)
}
