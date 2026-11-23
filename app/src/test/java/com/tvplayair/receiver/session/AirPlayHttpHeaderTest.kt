package com.tvplayair.receiver.session

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.SocketTimeoutException
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class AirPlayHttpHeaderTest {
    @Test
    fun fcupGzipReturnsOnlyValidPayloadsWithinTheDecodedLimit() {
        val payload = ByteArray(9000) { 'a'.code.toByte() }
        val zipped = ByteArrayOutputStream().also { output ->
            GZIPOutputStream(output).use { it.write(payload) }
        }.toByteArray()

        assertArrayEquals(payload, decodeFcupPayload(zipped, payload.size))
        assertNull(decodeFcupPayload(zipped, payload.size - 1))
        assertNull(decodeFcupPayload(zipped.copyOf(zipped.size - 4), payload.size))
        assertArrayEquals(payload, decodeFcupPayload(payload, payload.size))
    }

    @Test
    fun eventReaderConsumesTheWholeBodyBeforeTheNextRequest() {
        val firstBody = "GET /looks-like-a-request HTTP/1.1"
        val wire = "POST /feedback HTTP/1.1\r\nContent-Length: ${firstBody.length}\r\n\r\n" +
            firstBody + "GET /info HTTP/1.1\r\n\r\n"
        // One-byte reads force headers and bodies to cross read boundaries.
        val input = BufferedInputStream(streamEndingInTimeout(wire), 1)
        assertEquals("POST", readAirPlayEventMethod(input))
        assertEquals("GET", readAirPlayEventMethod(input))
        assertNull(readAirPlayEventMethod(input))
    }

    @Test
    fun invalidAndOversizedLengthsDoNotGetTreatedAsEmptyOrClampedBodies() {
        fun contentLength(vararg fields: String) =
            airPlayContentLength(listOf("POST /action HTTP/1.1", *fields, ""), 8)
        assertEquals(0, contentLength())
        assertEquals(8, contentLength("Content-Length: 8", "content-length: 8"))
        assertNull(contentLength("Content-Length: 9"))
        assertNull(contentLength("Content-Length: -1"))
        assertNull(contentLength("Content-Length: 1", "Content-Length: 2"))
        assertNull(contentLength("Transfer-Encoding: chunked", "Content-Length: 1"))
        val truncated = "POST /feedback HTTP/1.1\r\nContent-Length: 4\r\n\r\nab"
        assertNull(readAirPlayEventMethod(truncated.byteInputStream().buffered()))
    }

    @Test
    fun acceptsTheReverseAcknowledgementObservedDuringTrackChange() {
        assertTrue(isAirPlayReverseAck("EVENT/1.0 200 OK"))
        assertTrue(isAirPlayReverseAck("HTTP/1.1 200 OK"))
        assertTrue(isAirPlayReverseAck("RTSP/1.0 200 OK"))
        assertFalse(isAirPlayReverseAck("EVENT/1.0 400 Bad Request"))
        assertFalse(isAirPlayReverseAck("OTHER/1.0 200 OK"))
        assertFalse(isAirPlayReverseAck("EVENT/1.0"))
    }

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
