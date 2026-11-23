package com.tvplayair.receiver.session

import com.tvplayair.receiver.video.DirectPlaybackTelemetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Socket

class AirPlayProtocolTest {
    @Test
    fun onlyPlaylistPayloadsAreDecodedAsText() {
        assertTrue(hasFcupPlaylistHeader(" \r\n#EXTM3U\n#EXT-X-VERSION:3".toByteArray()))
        assertEquals(false, hasFcupPlaylistHeader(byteArrayOf(0, 0, 0, 24, 102, 116, 121, 112)))
        assertEquals(false, hasFcupPlaylistHeader("#EXT".toByteArray()))
    }


    @Test
    fun reverseChannelReportsLoadingPlayingPausedAndStoppedToTheSameSession() {
        val events = AirPlayPlaybackEvents()
        val key = AirPlayPlaybackEvents.SessionKey("127.0.0.1", "tvplayair-test")
        Socket().use { socket ->
            val channel = AirPlayPlaybackEvents.Channel(key, socket)
            events.attach(channel)
            var playbackId = 0L
            events.start(key) { playbackId = it }
            events.update(playbackId, DirectPlaybackTelemetry(0.0, 0.0, 1f, true, false))
            events.update(playbackId, DirectPlaybackTelemetry(0.0, 1.0, 1f, true, false))
            events.update(playbackId, DirectPlaybackTelemetry(0.0, 1.0, 0f, false, false, isReady = true))
            events.update(playbackId, DirectPlaybackTelemetry(120.0, 120.0, 0f, false, false, isReady = false))

            assertEquals(
                listOf("loading", "playing", "paused", "stopped"),
                channel.events.map { it.state.value }
            )
            val request = channel.events.first().request(key.appleSessionId).decodeToString()
            val body = request.substringAfter("\r\n\r\n")
            assertTrue(request.startsWith("POST /event HTTP/1.1\r\n"))
            assertTrue(request.contains("X-Apple-Session-ID: tvplayair-test\r\n"))
            assertTrue(request.contains("Content-Length: ${body.toByteArray().size}\r\n"))
        }
    }

    @Test
    fun nextAppCanOpenReverseEarlyAndOnlyTheCurrentSessionCanControlVideo() {
        val events = AirPlayPlaybackEvents()
        val first = AirPlayPlaybackEvents.SessionKey("127.0.0.1", "first-app")
        val next = first.copy(appleSessionId = "next-app")
        Socket().use { firstSocket ->
            Socket().use { nextSocket ->
                val firstChannel = AirPlayPlaybackEvents.Channel(first, firstSocket)
                val nextChannel = AirPlayPlaybackEvents.Channel(next, nextSocket)
                val commands = mutableListOf<String>()

                events.attach(firstChannel)
                var firstId = 0L
                events.start(first) { firstId = it }
                events.attach(nextChannel)
                assertTrue(nextChannel.events.isEmpty())
                events.start(next)
                events.control(first, stop = true) { commands += "old stop" }
                events.update(firstId, DirectPlaybackTelemetry(120.0, 120.0, 0f, false, false))
                events.control(next.copy(peer = "127.0.0.2")) { commands += "other peer" }
                events.control(next) { commands += "new seek" }
                events.control(next.copy(appleSessionId = null)) { commands += "legacy pause" }

                assertEquals(listOf("loading", "stopped"), firstChannel.events.map { it.state.value })
                assertEquals(listOf("loading"), nextChannel.events.map { it.state.value })
                assertEquals(listOf("new seek", "legacy pause"), commands)
            }
        }
    }

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
