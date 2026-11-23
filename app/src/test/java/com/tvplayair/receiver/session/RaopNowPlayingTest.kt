package com.tvplayair.receiver.session

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class RaopNowPlayingTest {
    @Test
    fun lateArtworkFromBeforeFlushCannotOverwriteNextAppOnSameConnection() {
        var latest: RaopNowPlaying? = null
        val session = RaopNowPlayingSession { latest = it }
        val source = Any()

        session.activate(source)
        session.track(source, 100L, RaopTrack("Song A", "App A", null))
        val genBeforeFlush = checkNotNull(session.itemGenerationFor(source))

        // App switch on the same RTSP socket sends FLUSH and new metadata.
        session.onFlush(source)
        session.track(source, 200L, RaopTrack("Song B", "App B", null))
        val genAfterFlush = checkNotNull(session.itemGenerationFor(source))
        session.artwork(source, 200L, byteArrayOf(2, 2), genAfterFlush)

        // Late async artwork from App A must not overwrite App B.
        session.artwork(source, 100L, byteArrayOf(1, 1), genBeforeFlush)

        assertEquals("Song B", latest?.track?.title)
        assertArrayEquals(byteArrayOf(2, 2), latest?.artwork)
    }

    @Test
    fun artworkWithoutRtpInfoIsAcceptedForCurrentTimestampedTrack() {
        var latest: RaopNowPlaying? = null
        val session = RaopNowPlayingSession { latest = it }
        val source = Any()

        session.activate(source)
        session.track(source, 500L, RaopTrack("Track 1", "Artist", null))
        val gen = checkNotNull(session.itemGenerationFor(source))

        session.artwork(source, null, byteArrayOf(9, 8, 7), gen)

        assertEquals("Track 1", latest?.track?.title)
        assertArrayEquals(byteArrayOf(9, 8, 7), latest?.artwork)
    }
}
