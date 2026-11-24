package com.tvplayair.receiver.ui

import com.tvplayair.receiver.network.ResolutionPreset

import com.tvplayair.receiver.R
import com.tvplayair.receiver.session.ReceiverAvailability
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
    fun autoOpenRequiresCurrentOverlayAccessEvenWhenPreviouslyEnabled() {
        val enabled = TvOverlayState(autoOpenVisual = true, overlayAccessGranted = true)
        assertEquals(R.string.receiver_setting_on, enabled.autoOpenSettingText)
        assertEquals(R.string.receiver_access_required,
            enabled.copy(overlayAccessGranted = false).autoOpenSettingText)
        assertEquals(R.string.receiver_setting_off, enabled.copy(autoOpenVisual = false).autoOpenSettingText)
    }

    @Test
    fun standbyDistinguishesStartupDiscoveryAndNetworkLoss() {
        val state = TvOverlayState()
        assertEquals(R.string.receiver_starting, state.standbyStatusText)
        assertEquals(R.string.receiver_airplay_ready,
            state.copy(receiverAvailability = ReceiverAvailability.AVAILABLE).standbyStatusText)
        assertEquals(R.string.receiver_unavailable,
            state.copy(receiverAvailability = ReceiverAvailability.UNAVAILABLE).standbyStatusText)
        assertEquals(R.string.receiver_network_unavailable,
            state.copy(receiverAvailability = ReceiverAvailability.NETWORK_UNAVAILABLE).standbyStatusText)
        assertEquals(R.string.receiver_connected,
            state.copy(receiverAvailability = ReceiverAvailability.AVAILABLE, connectedClient = "iPhone").standbyStatusText)
    }

    @Test
    fun mirrorControlsStayHiddenUntilRequestedAndDoNotFollowTheNextSource() {
        val mirror = TvOverlayState(audioGeneration = 1, audioControlsAvailable = true)
            .withPresentation(PresentationState.MIRROR_ACTIVE)
        assertFalse(mirror.showingMirrorControls)
        val controls = mirror.copy(mirrorControlsVisible = true)
        assertTrue(controls.showingMirrorControls)
        assertFalse(controls.showingAudio)
        assertTrue(controls.canSendAudioControl(1))
        assertFalse(controls.copy(visible = true).showingMirrorControls)
        val switching = controls.withPresentation(PresentationState.SWITCHING_TO_DIRECT)
        assertFalse(switching.showingMirrorControls)
        assertFalse(switching.canSendAudioControl(1))
        assertFalse(switching.withPresentation(PresentationState.MIRROR_ACTIVE).showingMirrorControls)
    }

    @Test
    fun visualAutoOpenIsOptInAndDoesNotChangeTransportPresentation() {
        val initial = TvOverlayState()
        assertFalse(initial.autoOpenVisual)
        val enabled = initial.copy(autoOpenVisual = true)
        assertTrue(enabled.autoOpenVisual)
        assertTrue(enabled.showStandby)
        assertTrue(enabled.withAudioStreamStarted(7).autoOpenVisual)
        assertTrue(enabled.withPresentation(PresentationState.MIRROR_ACTIVE).autoOpenVisual)
        assertTrue(enabled.withPresentation(PresentationState.DIRECT_ACTIVE).autoOpenVisual)
    }

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
    fun directAudioKeepsItsOwnControlsAfterTheVideoHudCloses() {
        val webAudio = DirectPlaybackTelemetry(120.0, 30.0, 1f, true, false,
            title = "Safari", audioOnly = true, artwork = byteArrayOf(1))
        val video = webAudio.copy(audioOnly = false, title = "Video", artwork = null)
        val direct = TvOverlayState(presentationState = PresentationState.DIRECT_ACTIVE, videoControls = video)

        val audio = direct.withDirectTelemetry(webAudio).copy(videoControls = null)
        assertTrue(audio.showingDirectAudio)
        assertTrue(audio.showingDirectControls)
        assertFalse(audio.showingVideoControls)
        assertEquals(webAudio, audio.directAudio)
        assertFalse(audio.copy(visible = true).showingDirectControls)
        assertTrue(audio.withDirectTelemetry(webAudio.copy(playWhenReady = false)).showingDirectControls)

        val switching = audio.withPresentation(PresentationState.SWITCHING_TO_DIRECT)
        assertNull(switching.withDirectTelemetry(webAudio).directAudio)
        val nextVideo = switching.withPresentation(PresentationState.DIRECT_ACTIVE)
            .copy(videoControls = video).withDirectTelemetry(video)
        assertTrue(nextVideo.showingVideoControls)
        assertNull(nextVideo.directAudio)
        assertNull(audio.withPresentation(PresentationState.MIRROR_ACTIVE).directAudio)
    }

    @Test
    fun switchingAppsNeverRestoresArtworkOrVideoControlsFromThePreviousMode() {
        val oldTrack = RaopNowPlaying(RaopTrack("Anterior", null, null), byteArrayOf(1))
        val newTrack = RaopNowPlaying(RaopTrack("Actual", null, null), byteArrayOf(2))
        var state = TvOverlayState(audioGeneration = 1).withNowPlaying(oldTrack)

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
        val audio = TvOverlayState(visible = true, audioGeneration = 1)
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
        val direct = TvOverlayState(audioGeneration = 1).withNowPlaying(old)
            .withPresentation(PresentationState.DIRECT_ACTIVE)
        assertNull(direct.withNowPlaying(old).withPresentation(PresentationState.IDLE).nowPlaying)

        val handoff = direct.withAudioStreamStarted(2)
            .withNowPlaying(RaopNowPlaying(artwork = artwork))
            .withNowPlaying(fresh)
        assertNull(handoff.nowPlaying)
        assertFalse(handoff.showingAudio)
        assertEquals(fresh, handoff.withPresentation(PresentationState.IDLE).nowPlaying)
        assertNull(handoff.clearAudioMetadata().withPresentation(PresentationState.IDLE).nowPlaying)
        assertNull(handoff.withNowPlaying(null).withPresentation(PresentationState.IDLE).nowPlaying)
    }

    @Test
    fun audioControlsOnlyApplyToTheCurrentStreamAndCannotPreemptDirectPlayback() {
        val first = TvOverlayState(audioGeneration = 1, audioControlsAvailable = true)
        assertTrue(first.canSendAudioControl(1))
        val switching = first.withPresentation(PresentationState.SWITCHING_TO_DIRECT)
        assertFalse(switching.canSendAudioControl(1))
        val second = switching.withAudioStreamStarted(2).withPresentation(PresentationState.IDLE)
        assertFalse(second.canSendAudioControl(1))
        assertTrue(second.canSendAudioControl(2))
        assertFalse(second.withAudioStreamStopped().canSendAudioControl(2))
        assertFalse(second.copy(audioControlsAvailable = false).canSendAudioControl(2))
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
