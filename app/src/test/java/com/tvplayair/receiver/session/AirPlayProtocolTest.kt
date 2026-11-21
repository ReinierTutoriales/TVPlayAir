package com.tvplayair.receiver.session

import com.tvplayair.receiver.video.DirectPlaybackTelemetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AirPlayProtocolTest {

    @Test
    fun parsesBinaryPlistDirectPlayBodyFromItsOriginalBytes() {
        val url = "https://cdn.example.com/video.mp4?token=a&part=2"
        val body = BinaryPlistWriter.encode(
            mapOf("Content-Location" to url, "Start-Position" to 0.25)
        )

        assertEquals(
            ParsedDirectPlayRequest(url, 0.25),
            AirPlayHttpRequestParser.parsePlayBody(body)
        )
    }

    @Test
    fun rejectsBinaryPlayBodyWithoutContentLocation() {
        val body = BinaryPlistWriter.encode(mapOf("Start-Position" to 0.25))

        assertNull(AirPlayHttpRequestParser.parsePlayBody(body))
    }

    @Test
    fun parsesPlainTextDirectPlayBody() {
        val body = """
            Content-Location: https://cdn.example.com/movie/master.m3u8
            Start-Position: 42.5
        """.trimIndent()

        val parsed = AirPlayHttpRequestParser.parsePlayBody(body.toByteArray())
        assertNotNull(parsed)
        assertEquals("https://cdn.example.com/movie/master.m3u8", parsed!!.url)
        assertEquals(42.5, parsed.startPosition, 0.001)
    }

    @Test
    fun parsesXmlPlistDirectPlayBodyWithEscapedAmpersand() {
        val body = """
            <?xml version="1.0" encoding="UTF-8"?>
            <plist version="1.0"><dict>
              <key>Content-Location</key><string>https://example.com/v.m3u8?a=1&amp;b=2</string>
              <key>Start-Position</key><real>15.25</real>
            </dict></plist>
        """.trimIndent()

        val parsed = AirPlayHttpRequestParser.parsePlayBody(body.toByteArray())
        assertNotNull(parsed)
        assertEquals("https://example.com/v.m3u8?a=1&b=2", parsed!!.url)
        assertEquals(15.25, parsed.startPosition, 0.001)
    }

    @Test
    fun parsesScrubAndRateQueryParameters() {
        assertEquals(88.2, AirPlayHttpRequestParser.parseQueryDouble("/scrub?position=88.2", "position")!!, 0.001)
        assertEquals(1.0, AirPlayHttpRequestParser.parseQueryDouble("/rate?value=1.000000", "value")!!, 0.001)
        assertNull(AirPlayHttpRequestParser.parseQueryDouble("/scrub", "position"))
    }

    @Test
    fun formatsPlaybackInfoPlistFromTelemetry() {
        val plist = AirPlayPlaybackInfoFormatter.formatPlist(
            DirectPlaybackTelemetry(
                durationSec = 180.0,
                positionSec = 30.5,
                rate = 1.0f,
                isPlaying = true,
                isBuffering = false
            )
        )
        assertTrue(plist.contains("<key>duration</key><real>180.000</real>"))
        assertTrue(plist.contains("<key>position</key><real>30.500</real>"))
        assertTrue(plist.contains("<key>rate</key><real>1.000</real>"))
        assertTrue(plist.contains("<key>readyToPlay</key><true/>"))
        assertTrue(plist.contains("<key>playbackLikelyToKeepUp</key><true/>"))
    }
}
