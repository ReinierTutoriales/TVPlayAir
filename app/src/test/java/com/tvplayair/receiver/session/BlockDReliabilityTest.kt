package com.tvplayair.receiver.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutionException

class BlockDReliabilityTest {
    @Test
    fun `headerless reverse channel receives LOADING and PLAYING events`() {
        val events = AirPlayPlaybackEvents()
        val reverseSocket = Socket()
        val standby = AirPlayPlaybackEvents.Channel(
            AirPlayPlaybackEvents.SessionKey("192.168.1.50", null),
            reverseSocket
        )
        events.attach(standby)

        var startedId = 0L
        val playKey = AirPlayPlaybackEvents.SessionKey("192.168.1.50", "ios-session-1")
        assertTrue(events.start(playKey) { startedId = it })
        events.update(
            startedId,
            DirectPlaybackTelemetry(10.0, 0.0, 1f, isPlaying = true, isBuffering = false)
        )

        val queued = mutableListOf<AirPlayPlaybackEvents.State>()
        while (true) {
            val ev = standby.events.poll() ?: break
            queued.add(ev.state)
        }
        assertEquals(
            listOf(AirPlayPlaybackEvents.State.LOADING, AirPlayPlaybackEvents.State.PLAYING),
            queued
        )
    }

    @Test
    fun `evicted FCUP event fails pending request immediately`() {
        val fcup = FcupRequests()
        fcup.reset(1L)
        val events = AirPlayPlaybackEvents { evicted ->
            evicted.fcupRequestId?.let { fcup.fail(it, "FCUP reverse queue overflow") }
        }
        val key = AirPlayPlaybackEvents.SessionKey("192.168.1.50", "s1")
        val channel = AirPlayPlaybackEvents.Channel(key, Socket())
        events.attach(channel)
        events.start(key)

        val req = fcup.acquire(1L, "https://example.com/master.m3u8") { reqId ->
            events.sendFcupRequest("https://example.com/master.m3u8", reqId)
        }
        assertNotNull(req)

        // Saturate the 8-slot reverse queue until the FCUP event is evicted.
        repeat(10) { idx ->
            events.sendFcupRequest("https://example.com/seg$idx.ts", 100 + idx)
        }

        assertNull(fcup.pending(req!!.id))
        assertTrue(req.result.isCompletedExceptionally)
        val ex = runCatching { req.result.get() }.exceptionOrNull()
        assertTrue(ex is ExecutionException)
    }

    @Test
    fun `readAirPlayHttpHeader retries on idle timeout when keepIdleConnection is true`() {
        val payload = "GET /server-info HTTP/1.1\r\nContent-Length: 0\r\n\r\n"
            .toByteArray(StandardCharsets.US_ASCII)
        var timedOutOnce = false
        var pos = 0
        val flakyStream = object : InputStream() {
            override fun read(): Int {
                if (!timedOutOnce) {
                    timedOutOnce = true
                    throw SocketTimeoutException("idle")
                }
                return if (pos < payload.size) payload[pos++].toInt() and 0xFF else -1
            }
        }
        val header = readAirPlayHttpHeader(
            input = BufferedInputStream(flakyStream, 1),
            allowIdleTimeout = false,
            keepIdleConnection = { true }
        )
        assertNotNull(header)
        assertTrue(header!!.toString(StandardCharsets.US_ASCII).startsWith("GET /server-info"))
    }
}
