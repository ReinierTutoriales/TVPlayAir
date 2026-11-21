package com.tvplayair.receiver.session

import com.tvplayair.receiver.video.DirectPlaybackTelemetry
import org.junit.Assert.assertEquals
import org.junit.Test

class AirPlayPlaybackEventsTest {
    private val peerA = AirPlayPlaybackEvents.SessionKey("192.0.2.1", "session-a")
    private val peerB = AirPlayPlaybackEvents.SessionKey("192.0.2.2", "session-b")

    @Test
    fun rejectsCommandsFromAnotherSession() {
        val events = AirPlayPlaybackEvents()
        events.start(peerA)
        var dispatched = 0
        events.control(peerB, stop = true) { dispatched++ }
        events.control(peerA) { dispatched++ }
        assertEquals(1, dispatched)
    }

    @Test
    fun stopPreventsLaterCommandsUntilNewPlayback() {
        val events = AirPlayPlaybackEvents()
        events.start(peerA)
        var dispatched = 0
        events.control(peerA, stop = true) { dispatched++ }
        events.control(peerA) { dispatched++ }
        assertEquals(1, dispatched)
        events.start(peerA)
        events.control(peerA) { dispatched++ }
        assertEquals(2, dispatched)
    }

    @Test
    fun staleTelemetryNeverUpdatesActivePlayback() {
        val events = AirPlayPlaybackEvents()
        var oldId = 0L
        var newId = 0L
        events.start(peerA) { oldId = it }
        events.start(peerA) { newId = it }
        val telemetry = DirectPlaybackTelemetry(
            durationSec = 30.0, positionSec = 2.0, rate = 1f,
            isPlaying = true, isBuffering = false
        )
        var accepted = 0
        events.update(oldId, telemetry) { accepted++ }
        events.update(newId, telemetry) { accepted++ }
        assertEquals(1, accepted)
    }

    @Test
    fun repeatedTransitionsDoNotAcceptPreviousPlaybackTelemetry() {
        val events = AirPlayPlaybackEvents()
        var previous = 0L
        repeat(100) {
            var current = 0L
            events.start(if (it % 2 == 0) peerA else peerB) { current = it }
            val telemetry = DirectPlaybackTelemetry(
                durationSec = 30.0, positionSec = 0.0, rate = 1f,
                isPlaying = true, isBuffering = false
            )
            var accepted = 0
            if (previous != 0L) events.update(previous, telemetry) { accepted++ }
            events.update(current, telemetry) { accepted++ }
            assertEquals(1, accepted)
            previous = current
        }
    }
}
