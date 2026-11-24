package com.tvplayair.receiver.session

import java.net.BindException
import java.net.ServerSocket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class AirPlayListenerSocketTest {
    @Test
    fun listenerCanAcquireItsPortAfterAnEarlierBindFailure() {
        val port = ServerSocket(0).use { occupied ->
            val busyPort = occupied.localPort
            assertThrows(BindException::class.java) { openAirPlayListener(busyPort).close() }
            busyPort
        }
        openAirPlayListener(port).use { listener ->
            assertTrue(listener.isBound)
            assertEquals(port, listener.localPort)
        }
    }
}
