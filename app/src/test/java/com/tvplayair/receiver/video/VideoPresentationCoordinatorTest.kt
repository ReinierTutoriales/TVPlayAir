package com.tvplayair.receiver.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoPresentationCoordinatorTest {

    @Test
    fun resolvesAirPlayStartFractionOnlyAfterDurationIsKnown() {
        assertNull(initialDirectPositionMs(0.25, durationMs = 0L))
        assertEquals(30_000L, initialDirectPositionMs(0.25, durationMs = 120_000L))
        assertEquals(42_500L, initialDirectPositionMs(42.5, durationMs = 0L))
        assertEquals(0L, initialDirectPositionMs(0.0, durationMs = 0L))
    }

    private class FakeMirrorEngine(private val events: MutableList<String>) : MirrorVideoEngine {
        var failStart = false
        override fun start(): Boolean {
            events += "mirror.start"
            return !failStart
        }

        override fun stopAndRelease() {
            events += "mirror.stopAndRelease"
        }
    }

    private class FakeDirectEngine(private val events: MutableList<String>) : DirectVideoEngine {
        var failPrepare = false
        override fun prepareAndPlay(url: String, startPosition: Double): Boolean {
            events += "direct.prepareAndPlay($url,$startPosition)"
            return !failPrepare
        }

        override fun play() {
            events += "direct.play"
        }

        override fun pause() {
            events += "direct.pause"
        }

        override fun seekTo(positionSec: Double) {
            events += "direct.seekTo($positionSec)"
        }

        override fun stopAndClear() {
            events += "direct.stopAndClear"
        }

        override fun currentTelemetry() = DirectPlaybackTelemetry(
            durationSec = 120.0,
            positionSec = 15.0,
            rate = 1f,
            isPlaying = true,
            isBuffering = false
        )
    }

    private class FakeAudioController(
        private val events: MutableList<String>
    ) : AudioHandoffController {
        override fun setPausedForExternalPlayer(paused: Boolean) {
            events += "audio.pausedForExternal($paused)"
        }

        override fun stop() {
            events += "audio.stop"
        }
    }

    private class FakeSurfaceController(
        private val events: MutableList<String>
    ) : SurfaceLayerController {
        override fun showMirrorSurface() {
            events += "surface.showMirror"
        }

        override fun showDirectSurface() {
            events += "surface.showDirect"
        }

        override fun hideAllSurfaces() {
            events += "surface.hideAll"
        }
    }

    @Test
    fun switchToDirectReleasesMirrorBeforePreparingExoPlayer() {
        val events = mutableListOf<String>()
        val coordinator = VideoPresentationCoordinator(
            FakeMirrorEngine(events),
            FakeDirectEngine(events),
            FakeAudioController(events),
            FakeSurfaceController(events)
        )

        assertTrue(coordinator.startMirror())
        assertEquals(PresentationState.MIRROR_ACTIVE, coordinator.state)
        events.clear()

        assertTrue(coordinator.switchToDirectVideo("https://example.com/4k.m3u8", 12.5))
        assertEquals(PresentationState.DIRECT_ACTIVE, coordinator.state)
        assertEquals(
            listOf(
                "audio.pausedForExternal(true)",
                "mirror.stopAndRelease",
                "surface.showDirect",
                "direct.prepareAndPlay(https://example.com/4k.m3u8,12.5)"
            ),
            events
        )
    }

    @Test
    fun switchToMirrorStopsExoPlayerBeforeRestartingMirrorAndAudio() {
        val events = mutableListOf<String>()
        val coordinator = VideoPresentationCoordinator(
            FakeMirrorEngine(events),
            FakeDirectEngine(events),
            FakeAudioController(events),
            FakeSurfaceController(events)
        )

        assertTrue(coordinator.startMirror())
        coordinator.switchToDirectVideo("https://example.com/video.mp4", 0.0)
        coordinator.switchToDirectVideo("https://example.com/next.mp4", 0.0)
        events.clear()

        coordinator.stopDirectVideo()
        assertEquals(PresentationState.MIRROR_ACTIVE, coordinator.state)
        assertEquals(
            listOf(
                "direct.stopAndClear",
                "surface.showMirror",
                "mirror.start",
                "audio.pausedForExternal(false)"
            ),
            events
        )
    }

    @Test
    fun stoppingDirectOnlyPlaybackReturnsToStandbyWithoutStartingMirror() {
        val events = mutableListOf<String>()
        val coordinator = VideoPresentationCoordinator(
            FakeMirrorEngine(events),
            FakeDirectEngine(events),
            FakeAudioController(events),
            FakeSurfaceController(events)
        )

        coordinator.switchToDirectVideo("https://example.com/video.mp4")
        events.clear()
        coordinator.stopDirectVideo()
        coordinator.stopDirectVideo()

        assertEquals(PresentationState.IDLE, coordinator.state)
        assertEquals(
            listOf("direct.stopAndClear", "audio.pausedForExternal(false)", "surface.hideAll"),
            events
        )
    }

    @Test
    fun losingDirectSurfaceDoesNotRestartMirrorOnAHiddenWindow() {
        val events = mutableListOf<String>()
        val coordinator = VideoPresentationCoordinator(
            FakeMirrorEngine(events),
            FakeDirectEngine(events),
            FakeAudioController(events),
            FakeSurfaceController(events)
        )

        assertTrue(coordinator.startMirror())
        assertTrue(coordinator.switchToDirectVideo("https://example.com/video.mp4"))
        events.clear()
        coordinator.onSurfaceDestroyed(isDirectSurface = true)

        assertEquals(PresentationState.IDLE, coordinator.state)
        assertEquals(
            listOf("direct.stopAndClear", "audio.pausedForExternal(false)", "surface.hideAll"),
            events
        )
    }

    @Test
    fun directPrepareFailureEntersRecoveringAndCleansUp() {
        val events = mutableListOf<String>()
        val direct = FakeDirectEngine(events).apply { failPrepare = true }
        val coordinator = VideoPresentationCoordinator(
            FakeMirrorEngine(events),
            direct,
            FakeAudioController(events),
            FakeSurfaceController(events)
        )

        assertFalse(coordinator.switchToDirectVideo("https://bad.invalid/stream.m3u8", 0.0))
        assertEquals(PresentationState.RECOVERING, coordinator.state)
        assertTrue(events.contains("mirror.stopAndRelease"))
        assertEquals("direct.stopAndClear", events.last())
    }

    @Test
    fun sessionDeathStopsBothEnginesAudioAndHidesSurfaces() {
        val events = mutableListOf<String>()
        val coordinator = VideoPresentationCoordinator(
            FakeMirrorEngine(events),
            FakeDirectEngine(events),
            FakeAudioController(events),
            FakeSurfaceController(events)
        )

        coordinator.startMirror()
        events.clear()

        coordinator.onSessionDied()
        assertEquals(PresentationState.IDLE, coordinator.state)
        assertEquals(
            listOf(
                "direct.stopAndClear",
                "mirror.stopAndRelease",
                "audio.stop",
                "surface.hideAll"
            ),
            events
        )
    }
}
