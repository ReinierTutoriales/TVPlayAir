package com.tvplayair.receiver.ui

import com.tvplayair.receiver.audio.OboeAudioConsumer
import com.tvplayair.receiver.video.DirectPlaybackTelemetry
import com.tvplayair.receiver.video.PresentationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvOverlayStateTest {

    @Test
    fun aConnectedClientKeepsStandbyVisibleUntilVideoTakesOver() {
        val connected = TvOverlayState(connectedClient = "iPhone")

        assertTrue(connected.showStandby)
        assertTrue(connected.copy(presentationState = PresentationState.RECOVERING).showStandby)
        assertFalse(connected.copy(presentationState = PresentationState.DIRECT_ACTIVE).showStandby)
        assertFalse(connected.copy(presentationState = PresentationState.MIRROR_ACTIVE).showStandby)
    }

    @Test
    fun directVideoControlsOnlyAppearOnRequestAndNeverCoverMirrorOrSettings() {
        val direct = TvOverlayState(presentationState = PresentationState.DIRECT_ACTIVE)
        assertFalse(direct.showingVideoControls)
        val controls = direct.copy(videoControls = DirectPlaybackTelemetry(120.0, 30.0, 1f, true, false))
        assertTrue(controls.showingVideoControls)
        assertFalse(controls.copy(visible = true).showingVideoControls)
        assertFalse(controls.copy(presentationState = PresentationState.MIRROR_ACTIVE).showingVideoControls)
        assertFalse(controls.copy(presentationState = PresentationState.IDLE).showingVideoControls)
    }

    @Test
    fun clampsAudioOffsetWithinConfiguredBounds() {
        var state = TvOverlayState(audioOffsetMs = 190)
        state = state.stepAudioOffset(10)
        assertEquals(200, state.audioOffsetMs)
        state = state.stepAudioOffset(50)
        assertEquals(OboeAudioConsumer.MAX_OFFSET_MS, state.audioOffsetMs)

        state = TvOverlayState(audioOffsetMs = -195).stepAudioOffset(-20)
        assertEquals(OboeAudioConsumer.MIN_OFFSET_MS, state.audioOffsetMs)
    }

    @Test
    fun togglesResolutionAndHevcPresets() {
        val initial = TvOverlayState()
        assertEquals(ResolutionPreset.UHD_4K, initial.resolutionPreset)
        assertTrue(initial.hevcEnabled)

        val toggled = initial.toggleResolution().toggleHevc()
        assertEquals(ResolutionPreset.FHD_1080P, toggled.resolutionPreset)
        assertFalse(toggled.hevcEnabled)
    }
}
