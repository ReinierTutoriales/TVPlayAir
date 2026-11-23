package com.tvplayair.receiver.session

import com.tvplayair.receiver.ui.TvOverlayState
import com.tvplayair.receiver.video.AudioHandoffController
import com.tvplayair.receiver.video.DirectPlaybackStart
import com.tvplayair.receiver.video.DirectPlaybackTelemetry
import com.tvplayair.receiver.video.DirectVideoEngine
import com.tvplayair.receiver.video.MirrorFrameGate
import com.tvplayair.receiver.video.MirrorVideoEngine
import com.tvplayair.receiver.video.PresentationState
import com.tvplayair.receiver.video.SurfaceLayerController
import com.tvplayair.receiver.video.VideoPresentationCoordinator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Socket

class MediaContinuityBaselineTest {
    @Test
    fun replaysMusicMirrorTrackChangeAndReturnToMusicWithLateEvents() {
        val receiver = Receiver()
        val firstSession = AirPlayPlaybackEvents.SessionKey("192.0.2.1", "first")
        val nextSession = firstSession.copy(appleSessionId = "next")
        repeat(100) {
            Socket().use { firstSocket ->
                Socket().use { nextSocket ->
                    receiver.startAudio(receiver.musicSource, "Music A")
                    assertTrue(receiver.overlay.showingAudio)
                    receiver.startMirror()
                    assertFalse(receiver.overlay.showingAudio)
                    receiver.metadata.artwork(receiver.musicSource, 110L, byteArrayOf(8))
                    assertNull(receiver.overlay.nowPlaying)

                    receiver.events.attach(AirPlayPlaybackEvents.Channel(firstSession, firstSocket))
                    val first = receiver.startVideo(firstSession)
                    assertTrue(receiver.coordinator.onDirectMediaReady(first))
                    val staleLoad = checkNotNull(receiver.fcup.acquire(first, PLAYLIST) { requestId ->
                        receiver.events.sendFcupRequest(PLAYLIST, requestId)
                    })
                    receiver.events.attach(AirPlayPlaybackEvents.Channel(nextSession, nextSocket))
                    receiver.events.control(firstSession, stop = true) { stopped ->
                        receiver.fcup.reset()
                        assertTrue(receiver.coordinator.finishDirectPlayback(stopped, senderStop = true))
                    }
                    assertEquals(PresentationState.MIRROR_ACTIVE, receiver.coordinator.state)
                    receiver.engines.acceptFirstMirrorFrame()
                    val next = receiver.startVideo(nextSession)
                    assertTrue(staleLoad.result.isCancelled)
                    assertFalse(receiver.fcup.complete(staleLoad.id, PLAYLIST, byteArrayOf()))
                    receiver.events.control(firstSession, stop = true) { error("Old stop reached new playback") }
                    assertFalse(receiver.coordinator.onDirectMediaReady(first))
                    assertTrue(receiver.coordinator.onDirectMediaReady(next))
                    assertEquals(next, receiver.engines.visibleVideo)

                    receiver.coordinator.onMirrorDisconnected()
                    receiver.startAudio(receiver.musicSource, "Music B")
                    assertFalse(receiver.overlay.showingAudio)
                    receiver.events.control(nextSession, stop = true) { stopped ->
                        assertTrue(receiver.coordinator.finishDirectPlayback(stopped, senderStop = true))
                    }
                    receiver.syncPresentation()
                    assertEquals("Music B", receiver.overlay.nowPlaying?.track?.title)
                    assertNull(receiver.engines.visibleVideo)
                    assertEquals(PresentationState.IDLE, receiver.coordinator.state)
                    receiver.events.clear()
                }
            }
        }
    }

    @Test
    fun mirrorExitAndAudioFlushPreserveOnlyCurrentAudioMetadata() {
        val receiver = Receiver()
        receiver.startAudio(receiver.musicSource, "Previous music")
        receiver.startMirror()
        receiver.startAudio(Any(), "Current music")

        // FLUSH invalidates queued PCM, while the same source still owns its metadata.
        receiver.resetAudioBuffer()
        receiver.coordinator.onMirrorDisconnected()
        receiver.syncPresentation()
        assertTrue(receiver.overlay.showingAudio)
        assertEquals("Current music", receiver.overlay.nowPlaying?.track?.title)
        assertTrue(receiver.overlay.nowPlaying?.artwork?.contentEquals(byteArrayOf(1)) == true)
    }

    private class Receiver {
        val musicSource = Any()
        private val mirrorSource = AirPlayConnectionCrypto { ByteArray(32) }
        var overlay = TvOverlayState()
            private set
        val metadata = RaopNowPlayingSession { overlay = overlay.withNowPlaying(it) }
        val events = AirPlayPlaybackEvents()
        val fcup = FcupRequests()
        val engines = Engines()
        val coordinator = VideoPresentationCoordinator(engines, engines, engines, engines)

        fun startAudio(source: Any, title: String) {
            metadata.activate(source) { resetAudioBuffer() }
            metadata.track(source, 100L, RaopTrack(title, null, null))
            metadata.artwork(source, 100L, byteArrayOf(1))
        }

        fun resetAudioBuffer() {
            overlay = overlay.withAudioStreamStarted((overlay.audioGeneration ?: 0) + 1)
        }

        fun startMirror() {
            if (metadata.retireAudioForMirror(mirrorSource)) overlay = overlay.withAudioStreamStopped()
            assertTrue(coordinator.startMirror())
            engines.acceptFirstMirrorFrame()
            syncPresentation()
        }

        fun startVideo(key: AirPlayPlaybackEvents.SessionKey): Long {
            var playbackId = 0L
            events.start(key) { id ->
                playbackId = id
                fcup.reset(id)
                assertTrue(coordinator.switchToDirectVideo(DirectPlaybackStart(PLAYLIST, 0.0, id)))
                syncPresentation()
            }
            return playbackId
        }

        fun syncPresentation() {
            overlay = overlay.withPresentation(coordinator.state)
        }
    }

    private class Engines : MirrorVideoEngine, DirectVideoEngine, AudioHandoffController, SurfaceLayerController {
        private val mirrorGate = MirrorFrameGate()
        private var mirrorRunning = false
        private var audioPaused = false
        private var preparedVideo: Long? = null
        var visibleVideo: Long? = null
            private set

        override fun start(discardPendingFrames: Boolean): Boolean {
            assertNull(preparedVideo)
            mirrorGate.resetGate()
            mirrorRunning = true
            return true
        }

        fun acceptFirstMirrorFrame() {
            assertTrue(mirrorRunning)
            assertFalse(mirrorGate.shouldSubmitFrame(0))
            assertTrue(mirrorGate.shouldSubmitFrame(MirrorFrameGate.FLAG_KEY_FRAME))
            mirrorGate.onFrameSubmitted(MirrorFrameGate.FLAG_KEY_FRAME)
            assertTrue(mirrorGate.shouldSubmitFrame(0))
        }

        override fun stopAndRelease() { mirrorRunning = false }
        override fun prepare(video: DirectPlaybackStart): Boolean {
            assertFalse(mirrorRunning)
            assertTrue(audioPaused)
            preparedVideo = video.playbackId
            return true
        }

        override fun stopAndClear(releasePlayer: Boolean) { preparedVideo = null }
        override fun play() = Unit
        override fun pause() = Unit
        override fun seekTo(positionSec: Double) = Unit
        override fun currentTelemetry() = DirectPlaybackTelemetry(30.0, 0.0, 1f, true, false)
        override fun setPausedForExternalPlayer(paused: Boolean) { audioPaused = paused }
        override fun stop() = Unit
        override fun showMirrorSurface() { visibleVideo = null }
        override fun showDirectSurface() { visibleVideo = null }
        override fun revealDirectSurface() { visibleVideo = checkNotNull(preparedVideo) }
        override fun hideAllSurfaces() { visibleVideo = null }
    }

    private companion object {
        const val PLAYLIST = "https://example.test/master.m3u8"
    }
}
