package com.tvplayair.receiver.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DacpControllerTest {

    @Test
    fun buildsExpectedDacpServiceName() {
        assertEquals("iTunes_Ctrl_ABCD1234", DacpRequestBuilder.expectedServiceName("ABCD1234"))
        assertNull(DacpRequestBuilder.expectedServiceName("   "))
    }

    @Test
    fun buildsValidDacpHttpRequestWithActiveRemoteHeader() {
        val req = DacpRequestBuilder.buildRequest(
            host = "192.168.1.42",
            port = 51234,
            activeRemote = "987654321",
            command = DacpCommand.PLAY_PAUSE
        )
        assertNotNull(req)
        assertEquals("http://192.168.1.42:51234/ctrl-int/1/playpause", req!!.url)
        assertEquals("987654321", req.activeRemoteHeader)
    }

    @Test
    fun wrapsIpv6HostAndRejectsInvalidInputs() {
        val ipv6 = DacpRequestBuilder.buildRequest(
            host = "fe80::1",
            port = 3689,
            activeRemote = "12345",
            command = DacpCommand.NEXT
        )
        assertNotNull(ipv6)
        assertEquals("http://[fe80::1]:3689/ctrl-int/1/nextitem", ipv6!!.url)

        assertNull(DacpRequestBuilder.buildRequest("", 3689, "123", DacpCommand.PLAY))
        assertNull(DacpRequestBuilder.buildRequest("192.168.1.2", 0, "123", DacpCommand.PLAY))
        assertNull(DacpRequestBuilder.buildRequest("192.168.1.2", 3689, "", DacpCommand.PLAY))
    }

    @Test
    fun resolvesCommandsByWireName() {
        assertEquals(DacpCommand.PLAY_PAUSE, DacpCommand.fromWireName("playpause"))
        assertEquals(DacpCommand.NEXT, DacpCommand.fromWireName("nextitem"))
        assertNull(DacpCommand.fromWireName("unknown_cmd"))
    }
}
