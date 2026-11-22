package com.tvplayair.receiver.ui

import com.tvplayair.receiver.audio.OboeAudioConsumer
import com.tvplayair.receiver.session.RaopNowPlaying
import com.tvplayair.receiver.session.RaopTrack
import com.tvplayair.receiver.video.DirectPlaybackTelemetry
import com.tvplayair.receiver.video.PresentationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun switchingAppsNeverRestoresArtworkOrVideoControlsFromThePreviousMode() {
        val oldTrack = RaopNowPlaying(RaopTrack("Anterior", null, null), byteArrayOf(1))
        val newTrack = RaopNowPlaying(RaopTrack("Actual", null, null), byteArrayOf(2))
        var state = TvOverlayState(audioProjectionActive = true).withNowPlaying(oldTrack)

        repeat(100) {
            state = state.withPresentation(PresentationState.SWITCHING_TO_DIRECT)
            assertNull(state.nowPlaying)
            assertFalse(state.showingAudio)
            state = state.withNowPlaying(oldTrack).withPresentation(PresentationState.DIRECT_ACTIVE)
                .copy(videoControls = DirectPlaybackTelemetry(10.0, 1.0, 1f, true, false))
            assertNull(state.nowPlaying)
            state = state.withPresentation(PresentationState.MIRROR_ACTIVE).withNowPlaying(oldTrack)
            assertNull(state.videoControls)
            state = state.withPresentation(PresentationState.IDLE)
            assertNull(state.nowPlaying)
            assertTrue(state.showingAudio)
            state = state.withNowPlaying(newTrack)
            assertEquals(newTrack, state.nowPlaying)
        }
    }

    @Test
    fun incomingVideoClosesSettingsAndDropsTheOutgoingArtwork() {
        val audio = TvOverlayState(visible = true, audioProjectionActive = true)
            .withNowPlaying(RaopNowPlaying(RaopTrack("Anterior", null, null), byteArrayOf(1)))

        for (state in listOf(PresentationState.SWITCHING_TO_DIRECT, PresentationState.MIRROR_ACTIVE)) {
            val incoming = audio.withPresentation(state)
            assertFalse(incoming.visible)
            assertFalse(incoming.showingAudio)
            assertNull(incoming.nowPlaying)
            assertTrue(incoming.copy(visible = true).withPresentation(state).visible)
        }
    }

    @Test
    fun freshAudioMetadataSurvivesVideoToAudioHandoff() {
        val old = RaopNowPlaying(RaopTrack("Anterior", null, null), byteArrayOf(1))
        val artwork = byteArrayOf(2)
        val fresh = RaopNowPlaying(RaopTrack("Actual", null, null), artwork)
        val direct = TvOverlayState(audioProjectionActive = true).withNowPlaying(old)
            .withPresentation(PresentationState.DIRECT_ACTIVE)
        assertNull(direct.withNowPlaying(old).withPresentation(PresentationState.IDLE).nowPlaying)

        val handoff = direct.withAudioStreamStarted()
            .withNowPlaying(RaopNowPlaying(artwork = artwork))
            .withNowPlaying(fresh)
        assertNull(handoff.nowPlaying)
        assertFalse(handoff.showingAudio)
        assertEquals(fresh, handoff.withPresentation(PresentationState.IDLE).nowPlaying)
        assertNull(handoff.clearAudioMetadata().withPresentation(PresentationState.IDLE).nowPlaying)
        assertNull(handoff.withNowPlaying(null).withPresentation(PresentationState.IDLE).nowPlaying)
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
