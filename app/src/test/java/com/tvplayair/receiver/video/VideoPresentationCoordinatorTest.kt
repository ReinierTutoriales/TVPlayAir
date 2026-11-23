package com.tvplayair.receiver.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoPresentationCoordinatorTest {

    @Test
    fun retainedPlaybackCallbacksAreBoundToTheirOriginalHandoff() {
        val original = Any()
        val replacement = Any()
        assertTrue(isCurrentRetainedHandoff(original, original))
        assertFalse(isCurrentRetainedHandoff(replacement, original))
        assertFalse(isCurrentRetainedHandoff(null, original))
    }

    @Test
    fun retainedDirectPlaybackRejectsInvalidSeekPositions() {
        assertFalse(validDirectSeekPosition(Double.NaN))
        assertFalse(validDirectSeekPosition(Double.POSITIVE_INFINITY))
        assertFalse(validDirectSeekPosition(Double.NEGATIVE_INFINITY))
        assertFalse(validDirectSeekPosition(-0.5))
        assertTrue(validDirectSeekPosition(0.0))
        assertTrue(validDirectSeekPosition(12.5))
    }

    @Test
    fun directSeekRejectsNonFiniteAndNegativeSenderPositions() {
        val events = mutableListOf<String>()
        val coordinator = VideoPresentationCoordinator(
            FakeMirrorEngine(events), FakeDirectEngine(events),
            FakeAudioController(events), FakeSurfaceController(events)
        )
        assertTrue(coordinator.switchToDirectVideo(
            DirectPlaybackStart("https://example.com/movie.mp4", 0.0, playbackId = 3)
        ))
        events.clear()
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1.0)
            .forEach(coordinator::seekDirect)
        assertTrue(events.isEmpty())
        coordinator.seekDirect(0.0)
        coordinator.seekDirect(12.5)
        assertEquals(listOf("direct.seekTo(0.0)", "direct.seekTo(12.5)"), events)
    }

    @Test
    fun failedMirrorStartupHidesPresentationInsteadOfLeavingStaleSurface() {
        val events = mutableListOf<String>()
        val mirror = FakeMirrorEngine(events).apply { failStart = true }
        val coordinator = VideoPresentationCoordinator(
            mirror, FakeDirectEngine(events), FakeAudioController(events),
            FakeSurfaceController(events)
        )
        assertFalse(coordinator.startMirror())
        assertEquals(PresentationState.RECOVERING, coordinator.state)
        assertEquals(
            listOf("direct.release", "surface.showMirror", "mirror.start",
                "surface.hideAll", "audio.pausedForExternal(false)"),
            events
        )
    }

    @Test
    fun failedLiveMirrorReconfigurationHidesOldFrame() {
        val events = mutableListOf<String>()
        val mirror = FakeMirrorEngine(events)
        val coordinator = VideoPresentationCoordinator(
            mirror, FakeDirectEngine(events), FakeAudioController(events),
            FakeSurfaceController(events)
        )
        assertTrue(coordinator.startMirror())
        events.clear()
        mirror.failStart = true
        assertFalse(coordinator.startMirror(reconfigured = true))
        assertEquals(PresentationState.RECOVERING, coordinator.state)
        assertEquals(listOf("surface.showMirror", "mirror.start", "surface.hideAll"), events)
    }

    @Test
    fun losingMirrorSurfaceClearsPresentationAndStopsDecoder() {
        val events = mutableListOf<String>()
        val coordinator = VideoPresentationCoordinator(
            FakeMirrorEngine(events), FakeDirectEngine(events),
            FakeAudioController(events), FakeSurfaceController(events)
        )
        assertTrue(coordinator.startMirror())
        events.clear()
        coordinator.onSurfaceDestroyed(isDirectSurface = false)
        assertEquals(PresentationState.IDLE, coordinator.state)
        assertEquals(listOf("mirror.stopAndRelease", "surface.hideAll"), events)
    }

    @Test
    fun senderStopDefersAudioAndTheNextVideoCancelsTheResumeBeforePreparing() {
        val events = mutableListOf<String>()
        val coordinator = VideoPresentationCoordinator(
            FakeMirrorEngine(events), FakeDirectEngine(events),
            FakeAudioController(events), FakeSurfaceController(events)
        )
        val first = DirectPlaybackStart("https://example.com/first", 0.0, playbackId = 1)
        val second = DirectPlaybackStart("https://example.com/second", 0.0, playbackId = 2)
        assertTrue(coordinator.switchToDirectVideo(first))
        events.clear()

        assertTrue(coordinator.finishDirectPlayback(1, senderStop = true))
        assertEquals(PresentationState.IDLE, coordinator.state)
        assertEquals(
            listOf("direct.stopAndClear", "audio.resumeAfterSenderStop", "surface.hideAll"),
            events
        )
        events.clear()

        assertTrue(coordinator.switchToDirectVideo(second))
        assertEquals("audio.pausedForExternal(true)", events.first())
        assertFalse(coordinator.finishDirectPlayback(1, senderStop = true))
        assertEquals(PresentationState.SWITCHING_TO_DIRECT, coordinator.state)
    }

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
        assertEquals(PresentationState.MIRROR_PREPARING, coordinator.state)
        assertTrue(events.isEmpty())
    }

    @Test
    fun delayedMirrorSurfaceNeverPreemptsSelectedDirectVideo() {
        val events = mutableListOf<String>()
        val coordinator = VideoPresentationCoordinator(
            FakeMirrorEngine(events), FakeDirectEngine(events),
            FakeAudioController(events), FakeSurfaceController(events)
        )
        assertTrue(coordinator.switchToDirectVideo(
            DirectPlaybackStart("https://example.com/movie", 0.0, playbackId = 1),
            mirrorAvailable = true
        ))
        events.clear()
        coordinator.onMirrorSurfaceCreated()
        assertEquals(PresentationState.SWITCHING_TO_DIRECT, coordinator.state)
        assertTrue(coordinator.onDirectMediaReady(1))
        coordinator.onMirrorSurfaceCreated()
        assertEquals(PresentationState.DIRECT_ACTIVE, coordinator.state)
        assertEquals(listOf("surface.revealDirect"), events)

        assertTrue(coordinator.finishDirectPlayback(1))
        assertEquals(PresentationState.MIRROR_PREPARING, coordinator.state)
    }

    @Test
    fun liveMirrorReconfigurationKeepsDirectVideoUntilANewMirrorOpens() {
        val events = mutableListOf<String>()
        val coordinator = VideoPresentationCoordinator(
            FakeMirrorEngine(events), FakeDirectEngine(events),
            FakeAudioController(events), FakeSurfaceController(events)
        )
        val video = DirectPlaybackStart("https://example.com/movie", 0.0, 1)
        assertTrue(coordinator.startMirror())
        assertTrue(coordinator.switchToDirectVideo(video))
        assertTrue(coordinator.onDirectMediaReady(video.playbackId))
        events.clear()

        assertFalse(coordinator.startMirror(reconfigured = true, opensProjection = false))
        assertEquals(PresentationState.DIRECT_ACTIVE, coordinator.state)
        assertTrue(events.isEmpty())

        assertTrue(coordinator.startMirror(reconfigured = true, opensProjection = true))
        assertEquals(PresentationState.MIRROR_PREPARING, coordinator.state)
        assertEquals(
            listOf("direct.release", "surface.showMirror", "mirror.start", "audio.pausedForExternal(false)"),
            events
        )
        assertFalse(coordinator.finishDirectPlayback(video.playbackId))
    }

    @Test
    fun invalidStartPositionsNeverBecomeSeekCommands() {
        assertNull(initialDirectPositionMs(Double.NaN, 120_000L))
        assertNull(initialDirectPositionMs(Double.POSITIVE_INFINITY, 120_000L))
        assertNull(initialDirectPositionMs(Double.NEGATIVE_INFINITY, 120_000L))
    }

    private class FakeMirrorEngine(private val events: MutableList<String>) : MirrorVideoEngine {
        var failStart = false
        var failStop = false
        var discardedPendingFrames = false

        override fun start(discardPendingFrames: Boolean): Boolean {
            events += "mirror.start"
            discardedPendingFrames = discardPendingFrames
            return !failStart
        }

        override fun stopAndRelease(): Boolean {
            events += "mirror.stopAndRelease"
            return !failStop
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

        override fun stopAndClear(releasePlayer: Boolean) {
            events += if (releasePlayer) "direct.release" else "direct.stopAndClear"
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

        override fun resumeAfterSenderStop() {
            events += "audio.resumeAfterSenderStop"
        }

        override fun stop() {
            events += "audio.stop"
        }
    }

    private class FakeSurfaceController(
        private val events: MutableList<String>
    ) : SurfaceLayerController {
        var mirrorSurfaceAvailable = true
        override fun hasMirrorSurface(): Boolean = mirrorSurfaceAvailable

        override fun coverMirrorSurface() {
            events += "surface.coverMirror"
        }

        override fun revealMirrorSurface() {
            events += "surface.revealMirror"
        }
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
    fun mirrorIsActiveOnlyAfterRenderConfirmationAndIgnoresCallbacksDuringDirectPlayback() {
        val events = mutableListOf<String>()
        val coordinator = VideoPresentationCoordinator(
            FakeMirrorEngine(events), FakeDirectEngine(events),
            FakeAudioController(events), FakeSurfaceController(events)
        )

        assertTrue(coordinator.startMirror())
        assertEquals(PresentationState.MIRROR_PREPARING, coordinator.state)
        assertFalse("surface.revealMirror" in events)
        assertTrue(coordinator.onMirrorFrameRendered())
        assertEquals(PresentationState.MIRROR_ACTIVE, coordinator.state)
        assertEquals("surface.revealMirror", events.last())

        assertTrue(coordinator.onMirrorRecovering())
        assertEquals(PresentationState.MIRROR_RECOVERING, coordinator.state)
        assertEquals("surface.coverMirror", events.last())
        assertTrue(coordinator.onMirrorFrameRendered())
        assertEquals(PresentationState.MIRROR_ACTIVE, coordinator.state)

        assertTrue(coordinator.switchToDirectVideo(DirectPlaybackStart("https://example.com/video", 0.0, playbackId = 1)))
        assertFalse(coordinator.onMirrorFrameRendered())
        assertFalse(coordinator.onMirrorRecovering())
        assertEquals(PresentationState.SWITCHING_TO_DIRECT, coordinator.state)
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
        assertEquals(PresentationState.MIRROR_PREPARING, coordinator.state)
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
    fun mirrorWaitsForSurfaceCreationWithoutTearingItDown() {
        val events = mutableListOf<String>()
        val surfaces = FakeSurfaceController(events).apply { mirrorSurfaceAvailable = false }
        val coordinator = VideoPresentationCoordinator(
            FakeMirrorEngine(events), FakeDirectEngine(events), FakeAudioController(events), surfaces
        )
        assertTrue(coordinator.startMirror())
        assertEquals(PresentationState.SWITCHING_TO_MIRROR, coordinator.state)
        assertFalse("mirror.start" in events)
        assertFalse("surface.hideAll" in events)

        surfaces.mirrorSurfaceAvailable = true
        coordinator.onMirrorSurfaceCreated()
        assertEquals(PresentationState.MIRROR_PREPARING, coordinator.state)
        assertEquals(1, events.count { it == "mirror.start" })
    }

    @Test
    fun directPreparationWaitsForMirrorDecoderToRelease() {
        val events = mutableListOf<String>()
        val mirror = FakeMirrorEngine(events).apply { failStop = true }
        val direct = FakeDirectEngine(events)
        val coordinator = VideoPresentationCoordinator(
            mirror, direct, FakeAudioController(events), FakeSurfaceController(events)
        )
        assertTrue(coordinator.startMirror())
        val video = DirectPlaybackStart("https://example.com/video.m3u8", 0.0, playbackId = 1)
        assertFalse(coordinator.switchToDirectVideo(video))
        assertEquals(PresentationState.RECOVERING, coordinator.state)
        assertNull(direct.preparedVideo)

        mirror.failStop = false
        assertTrue(coordinator.switchToDirectVideo(video))
        assertEquals(video, direct.preparedVideo)
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
        assertEquals(listOf("surface.showMirror", "mirror.start"), events)

        mirror.failStart = true
        assertFalse(coordinator.startMirror(reconfigured = true))
        assertEquals(PresentationState.RECOVERING, coordinator.state)
    }

    @Test
    fun switchToMirrorReleasesExoPlayerBeforeRestartingMirrorAndAudio() {
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
        assertEquals(PresentationState.MIRROR_PREPARING, coordinator.state)
        assertEquals(
            listOf(
                "direct.release",
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
    fun losingDirectSurfaceKeepsPlaybackAndRequiresAFreshFrameOnReturn() {
        val events = mutableListOf<String>()
        val coordinator = VideoPresentationCoordinator(
            FakeMirrorEngine(events),
            FakeDirectEngine(events),
            FakeAudioController(events),
            FakeSurfaceController(events)
        )

        assertTrue(coordinator.startMirror())
        assertTrue(coordinator.switchToDirectVideo(DirectPlaybackStart("https://example.com/video.mp4", 0.0, playbackId = 1)))
        assertTrue(coordinator.onDirectMediaReady(1))
        events.clear()
        coordinator.onSurfaceDestroyed(isDirectSurface = true)

        assertEquals(PresentationState.DIRECT_WITHOUT_SURFACE, coordinator.state)
        assertFalse(coordinator.onDirectStartupTimeout(1))
        assertFalse(coordinator.onDirectMediaReady(1))
        assertTrue(events.isEmpty())

        assertTrue(coordinator.onDirectSurfaceCreated())
        assertFalse(coordinator.onDirectMediaReady(2))
        assertTrue(coordinator.onDirectMediaReady(1))
        assertEquals(PresentationState.DIRECT_ACTIVE, coordinator.state)
        assertEquals(listOf("surface.revealDirect"), events)
    }

    @Test
    fun directStopWithoutItsSurfaceStillRestoresLiveMirroring() {
        val events = mutableListOf<String>()
        val mirror = FakeMirrorEngine(events)
        val coordinator = VideoPresentationCoordinator(
            mirror, FakeDirectEngine(events),
            FakeAudioController(events), FakeSurfaceController(events)
        )
        assertTrue(coordinator.startMirror())
        assertTrue(coordinator.switchToDirectVideo(DirectPlaybackStart("https://example.com/video.mp4", 0.0, 1)))
        coordinator.onSurfaceDestroyed(isDirectSurface = true)
        events.clear()

        assertTrue(coordinator.finishDirectPlayback(1, senderStop = true))
        assertEquals(PresentationState.MIRROR_PREPARING, coordinator.state)
        assertTrue(mirror.discardedPendingFrames)
        assertEquals(
            listOf("direct.release", "surface.showMirror", "mirror.start", "audio.pausedForExternal(false)"),
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
            override fun stopAndClear(releasePlayer: Boolean) { events += "direct.stopAndClear" }
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
        assertEquals(PresentationState.MIRROR_PREPARING, coordinator.state)
        assertFalse(coordinator.onDirectMediaReady(1))
        assertEquals(
            listOf("direct.release", "surface.showMirror", "mirror.start", "audio.pausedForExternal(false)"),
            events.takeLast(4)
        )
    }
    @Test
    fun restoredDirectSnapshotQueuesMirrorFallbackWithoutStartingItsDecoder() {
        val events = mutableListOf<String>()
        val coordinator = VideoPresentationCoordinator(
            FakeMirrorEngine(events), FakeDirectEngine(events),
            FakeAudioController(events), FakeSurfaceController(events)
        )
        val video = DirectPlaybackStart("https://example.com/movie.mp4", 0.0, 10)
        assertTrue(coordinator.switchToDirectVideo(video, mirrorAvailable = true))
        assertFalse(events.contains("mirror.start"))
        assertTrue(coordinator.finishDirectPlayback(video.playbackId))
        assertEquals(PresentationState.MIRROR_PREPARING, coordinator.state)
        assertEquals(1, events.count { it == "mirror.start" })
    }
    @Test
    fun mirrorSnapshotKeepsCastPlayingAndIsOnlyUsedAsFallback() {
        val events = mutableListOf<String>()
        val coordinator = VideoPresentationCoordinator(
            FakeMirrorEngine(events), FakeDirectEngine(events),
            FakeAudioController(events), FakeSurfaceController(events)
        )
        assertTrue(coordinator.switchToDirectVideo(DirectPlaybackStart("https://example.com/cast.mp4", 0.0, -1)))
        assertTrue(coordinator.onDirectMediaReady(-1))
        events.clear()

        coordinator.onCachedMirrorAvailable()
        assertEquals(PresentationState.DIRECT_ACTIVE, coordinator.state)
        assertTrue(events.isEmpty())

        assertTrue(coordinator.finishDirectPlayback(-1))
        assertEquals(PresentationState.MIRROR_PREPARING, coordinator.state)
        assertEquals(1, events.count { it == "mirror.start" })
    }
}
