package com.tvplayair.receiver.session

import java.io.BufferedInputStream
import java.io.InputStream
import java.net.SocketTimeoutException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class AirPlayHttpHeaderTest {
    @Test
    fun idleLocalKeepAliveClosesWithoutReportingRequestFailure() {
        assertNull(readAirPlayHttpHeader(streamEndingInTimeout(""), allowIdleTimeout = true))
    }

    @Test
    fun partialHeaderAndExpectedReverseAckStillTimeOut() {
        assertThrows(SocketTimeoutException::class.java) {
            readAirPlayHttpHeader(streamEndingInTimeout("GET /__fcup"), allowIdleTimeout = true)
        }
        assertThrows(SocketTimeoutException::class.java) {
            readAirPlayHttpHeader(streamEndingInTimeout(""))
        }
    }

    @Test
    fun completeRequestStillReturnsOnlyTheHeader() {
        val header = "POST /play HTTP/1.1\r\nContent-Length: 4\r\n\r\n"
        val input = streamEndingInTimeout(header + "body")
        assertArrayEquals(header.toByteArray(), readAirPlayHttpHeader(input, allowIdleTimeout = true))
        assertArrayEquals("body".toByteArray(), input.readNBytes(4))
    }

    private fun streamEndingInTimeout(text: String) = BufferedInputStream(object : InputStream() {
        private val bytes = text.toByteArray()
        private var offset = 0

        override fun read(): Int {
            if (offset == bytes.size) throw SocketTimeoutException("fixture timeout")
            return bytes[offset++].toInt() and 0xff
        }
    })
}
