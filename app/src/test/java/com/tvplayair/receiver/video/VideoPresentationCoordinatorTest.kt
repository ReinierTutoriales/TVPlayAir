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

    @Test
    fun resumesExactSubsecondPositionAndPauseInTheInitialPrepare() {
        val events = mutableListOf<String>()
        val direct = FakeDirectEngine(events)
        val coordinator = VideoPresentationCoordinator(
            FakeMirrorEngine(events), direct, FakeAudioController(events), FakeSurfaceController(events)
        )
        val original = DirectPlaybackStart("https://example.com/movie.mp4", 0.5, 7)
        assertNull(original.initialPositionMs())
        assertEquals(0.5, original.startFraction)

        val restored = original.withCheckpoint(370L).withPlaybackIntent("pause")
        assertTrue(coordinator.switchToDirectVideo(restored))
        assertEquals(370L, direct.preparedVideo?.initialPositionMs())
        assertNull(direct.preparedVideo?.startFraction)
        assertEquals(false, direct.preparedVideo?.playWhenReady)
        assertFalse(events.contains("direct.play"))
        assertEquals(0L, restored.withCheckpoint(0L).initialPositionMs())
        assertEquals(370L, restored.withCheckpoint(NO_DIRECT_CHECKPOINT_MS).initialPositionMs())
    }

    @Test
    fun pauseIntentSurvivesPositionUpdatesAndOrdersRapidToggles() {
        val initial = DirectPlaybackStart("https://example.com/movie.mp4", 0.0, 1)
        val paused = initial.withPlaybackIntent("toggle").withCheckpoint(720L)
        assertFalse(paused.playWhenReady)
        assertTrue(paused.withPlaybackIntent("toggle").playWhenReady)
        assertFalse(paused.withPlaybackIntent("play").withPlaybackIntent("pause").playWhenReady)
        assertEquals(720L, paused.withPlaybackIntent("play").resumePositionMs)
    }

    @Test
    fun invalidDirectHandoffDoesNotInterruptActiveMirror() {
        val events = mutableListOf<String>()
        val coordinator = VideoPresentationCoordinator(
            FakeMirrorEngine(events),
            FakeDirectEngine(events),
            FakeAudioController(events),
            FakeSurfaceController(events)
        )
        assertTrue(coordinator.startMirror())
        events.clear()
        assertFalse(coordinator.switchToDirectVideo(DirectPlaybackStart("", 0.0, playbackId = 1)))
        assertFalse(coordinator.switchToDirectVideo(DirectPlaybackStart("https://example.com/movie", Double.NaN, playbackId = 1)))
        assertFalse(coordinator.switchToDirectVideo(DirectPlaybackStart("https://example.com/movie", Double.POSITIVE_INFINITY, playbackId = 1)))
        assertEquals(PresentationState.MIRROR_ACTIVE, coordinator.state)
        assertTrue(events.isEmpty())
    }

    @Test
    fun invalidStartPositionsNeverBecomeSeekCommands() {
        assertNull(initialDirectPositionMs(Double.NaN, 120_000L))
        assertNull(initialDirectPositionMs(Double.POSITIVE_INFINITY, 120_000L))
        assertNull(initialDirectPositionMs(Double.NEGATIVE_INFINITY, 120_000L))
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
        var preparedVideo: DirectPlaybackStart? = null
        override fun prepare(video: DirectPlaybackStart): Boolean {
            preparedVideo = video
            events += "direct.prepareAndPlay(${video.url},${video.airPlayPosition})"
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

        override fun revealDirectSurface() {
            events += "surface.revealDirect"
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

        assertTrue(coordinator.switchToDirectVideo(DirectPlaybackStart("https://example.com/4k.m3u8", 12.5, playbackId = 1)))
        assertEquals(PresentationState.SWITCHING_TO_DIRECT, coordinator.state)
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
    fun changedCodecRestartsMirrorWithoutActivatingTheDirectEngine() {
        val events = mutableListOf<String>()
        val mirror = FakeMirrorEngine(events)
        val coordinator = VideoPresentationCoordinator(
            mirror,
            FakeDirectEngine(events),
            FakeAudioController(events),
            FakeSurfaceController(events)
        )

        assertTrue(coordinator.startMirror())
        events.clear()
        assertTrue(coordinator.startMirror(reconfigured = true))
        assertEquals(listOf("mirror.start"), events)

        mirror.failStart = true
        assertFalse(coordinator.startMirror(reconfigured = true))
        assertEquals(PresentationState.RECOVERING, coordinator.state)
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
        coordinator.switchToDirectVideo(DirectPlaybackStart("https://example.com/video.mp4", 0.0, playbackId = 1))
        events.clear()
        coordinator.switchToDirectVideo(DirectPlaybackStart("https://example.com/next.mp4", 0.0, playbackId = 2))
        assertEquals(listOf("surface.showDirect", "direct.prepareAndPlay(https://example.com/next.mp4,0.0)"), events)
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

        coordinator.switchToDirectVideo(DirectPlaybackStart("https://example.com/video.mp4", 0.0, playbackId = 1))
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
        assertTrue(coordinator.switchToDirectVideo(DirectPlaybackStart("https://example.com/video.mp4", 0.0, playbackId = 1)))
        events.clear()
        coordinator.onSurfaceDestroyed(isDirectSurface = true)

        assertEquals(PresentationState.IDLE, coordinator.state)
        assertEquals(
            listOf("direct.stopAndClear", "audio.pausedForExternal(false)", "surface.hideAll"),
            events
        )
    }

    @Test
    fun directPrepareFailureReturnsToStandbyAndUnpausesAudio() {
        val events = mutableListOf<String>()
        val direct = FakeDirectEngine(events).apply { failPrepare = true }
        val coordinator = VideoPresentationCoordinator(
            FakeMirrorEngine(events),
            direct,
            FakeAudioController(events),
            FakeSurfaceController(events)
        )

        assertFalse(coordinator.switchToDirectVideo(DirectPlaybackStart("https://bad.invalid/stream.m3u8", 0.0, playbackId = 1)))
        assertEquals(PresentationState.IDLE, coordinator.state)
        assertEquals(
            listOf("direct.stopAndClear", "audio.pausedForExternal(false)", "surface.hideAll"),
            events.takeLast(3)
        )
    }

    @Test
    fun mirrorDisconnectDuringDirectPlaybackPreventsRestartingADeadMirror() {
        val events = mutableListOf<String>()
        val coordinator = VideoPresentationCoordinator(
            FakeMirrorEngine(events),
            FakeDirectEngine(events),
            FakeAudioController(events),
            FakeSurfaceController(events)
        )

        coordinator.startMirror()
        coordinator.switchToDirectVideo(DirectPlaybackStart("https://example.com/video.mp4", 0.0, playbackId = 1))
        events.clear()
        coordinator.onMirrorDisconnected()
        assertEquals(PresentationState.SWITCHING_TO_DIRECT, coordinator.state)
        assertTrue(events.isEmpty())
        coordinator.stopDirectVideo()
        assertEquals(PresentationState.IDLE, coordinator.state)
        assertFalse(events.contains("mirror.start"))
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
                "audio.pausedForExternal(false)",
                "surface.hideAll"
            ),
            events
        )
    }
    @Test
    fun stopDuringDirectPreparationDoesNotLeaveDirectActive() {
        val events = mutableListOf<String>()
        lateinit var coordinator: VideoPresentationCoordinator
        val direct = object : DirectVideoEngine {
            override fun prepare(video: DirectPlaybackStart): Boolean {
                coordinator.stopDirectVideo()
                return true
            }
            override fun play() = Unit
            override fun pause() = Unit
            override fun seekTo(positionSec: Double) = Unit
            override fun stopAndClear() { events += "direct.stopAndClear" }
            override fun currentTelemetry() = DirectPlaybackTelemetry(0.0, 0.0, 0f, false, false)
        }
        coordinator = VideoPresentationCoordinator(
            FakeMirrorEngine(events), direct,
            FakeAudioController(events), FakeSurfaceController(events)
        )
        coordinator.switchToDirectVideo(DirectPlaybackStart("https://example.com/movie.mp4", 0.0, playbackId = 1))
        assertEquals(PresentationState.IDLE, coordinator.state)
    }

    @Test
    fun rapidReplacementOnlyRevealsTheCurrentVideoAndIgnoresStaleFailure() {
        val events = mutableListOf<String>()
        val coordinator = VideoPresentationCoordinator(
            FakeMirrorEngine(events), FakeDirectEngine(events),
            FakeAudioController(events), FakeSurfaceController(events)
        )
        assertTrue(coordinator.switchToDirectVideo(DirectPlaybackStart("https://example.com/1.mp4", 0.0, 1)))
        for (id in 2L..101L) {
            assertTrue(coordinator.switchToDirectVideo(DirectPlaybackStart("https://example.com/$id.mp4", 0.0, id)))
            assertFalse(coordinator.onDirectMediaReady(id - 1))
            assertFalse(coordinator.onDirectStartupTimeout(id - 1))
            assertFalse(coordinator.finishDirectPlayback(id - 1))
            assertEquals(PresentationState.SWITCHING_TO_DIRECT, coordinator.state)
        }
        assertFalse(events.contains("surface.revealDirect"))
        assertTrue(coordinator.onDirectMediaReady(101))
        assertFalse(coordinator.onDirectMediaReady(101))
        assertFalse(coordinator.onDirectStartupTimeout(101))
        assertEquals(PresentationState.DIRECT_ACTIVE, coordinator.state)
        assertEquals(1, events.count { it == "surface.revealDirect" })
    }

    @Test
    fun firstFrameTimeoutRestoresMirrorAndRejectsLateFrame() {
        val events = mutableListOf<String>()
        val coordinator = VideoPresentationCoordinator(
            FakeMirrorEngine(events), FakeDirectEngine(events),
            FakeAudioController(events), FakeSurfaceController(events)
        )
        coordinator.startMirror()
        coordinator.switchToDirectVideo(DirectPlaybackStart("https://example.com/slow.mp4", 0.0, 1))
        coordinator.pauseDirect()
        assertTrue(events.contains("direct.pause"))
        assertTrue(coordinator.onDirectStartupTimeout(1))
        assertEquals(PresentationState.MIRROR_ACTIVE, coordinator.state)
        assertFalse(coordinator.onDirectMediaReady(1))
        assertEquals(
            listOf("direct.stopAndClear", "surface.showMirror", "mirror.start", "audio.pausedForExternal(false)"),
            events.takeLast(4)
        )
    }
}
