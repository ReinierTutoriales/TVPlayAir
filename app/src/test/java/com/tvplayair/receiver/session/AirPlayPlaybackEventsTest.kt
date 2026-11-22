package com.tvplayair.receiver.session

import com.tvplayair.receiver.video.DirectPlaybackTelemetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Socket

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
    fun controlsCarryCurrentPlaybackIdentityAcrossTransitions() {
        val events = AirPlayPlaybackEvents()
        var first = 0L
        var second = 0L
        events.start(peerA) { first = it }
        val dispatched = mutableListOf<Long>()
        events.control(peerA) { id -> dispatched.add(id) }
        events.start(peerA) { second = it }
        events.control(peerA) { id -> dispatched.add(id) }
        events.control(peerB, stop = true) { id -> dispatched.add(id) }
        assertEquals(listOf(first, second), dispatched)
    }


    @Test
    fun stopForPreviousSessionCannotStopReplacement() {
        val events = AirPlayPlaybackEvents()
        var first = 0L
        var second = 0L
        events.start(peerA) { first = it }
        events.start(peerB) { second = it }
        val stopped = mutableListOf<Long>()
        events.control(peerA, stop = true) { id -> stopped.add(id) }
        events.control(peerB) { id -> stopped.add(id) }
        assertEquals(listOf(second), stopped)
        events.control(peerB, stop = true) { id -> stopped.add(id) }
        assertEquals(listOf(second, second), stopped)
        events.control(peerB) { id -> stopped.add(id) }
        assertEquals(listOf(second, second), stopped)
    }

    @Test
    fun samePeerNewPlaybackReceivesNewCommandIdentity() {
        val events = AirPlayPlaybackEvents()
        var oldId = 0L
        var currentId = 0L
        events.start(peerA) { oldId = it }
        events.start(peerA) { currentId = it }
        val ids = mutableListOf<Long>()
        events.control(peerA) { id -> ids.add(id) }
        assertEquals(listOf(currentId), ids)
        org.junit.Assert.assertNotEquals(oldId, currentId)
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

    @Test
    fun receiverFinishOnlyStopsTheMatchingVideoAndRejectsLaterTelemetry() {
        val events = AirPlayPlaybackEvents()
        val key = AirPlayPlaybackEvents.SessionKey("peer", "session")
        Socket().use { socket ->
            val channel = AirPlayPlaybackEvents.Channel(key, socket)
            events.attach(channel)
            var priorId = 0L
            var currentId = 0L
            events.start(key) { priorId = it }
            events.start(key) { currentId = it }
            channel.events.clear()

            events.finish(priorId)
            assertTrue(channel.events.isEmpty())
            events.finish(currentId)
            events.update(currentId, DirectPlaybackTelemetry(10.0, 2.0, 1f, true, false))
            assertEquals(listOf(AirPlayPlaybackEvents.State.STOPPED), channel.events.map { it.state })
        }
    }
}
