package com.tvplayair.receiver.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AirPlayMirrorAndInfoTest {

    @Test
    fun formatsInfoPlistWith4kHevcCapabilities() {
        val xml = AirPlayInfoPlist.formatInfoXml(
            deviceId = "AA:BB:CC:DD:EE:FF",
            maxWidth = 3840,
            maxHeight = 2160,
            hevcEnabled = true
        )
        assertTrue(xml.contains("<key>deviceID</key><string>AA:BB:CC:DD:EE:FF</string>"))
        assertTrue(xml.contains("<key>widthPixels</key><integer>3840</integer>"))
        assertTrue(xml.contains("<key>heightPixels</key><integer>2160</integer>"))
        assertTrue(xml.contains("<key>preferredCodec</key><string>HEVC</string>"))
    }

    @Test
    fun parses128ByteAirPlayMirrorHeaderAndConvertsNtpToMicros() {
        val raw = ByteArray(AirPlayMirrorHeaderParser.HEADER_BYTES)
        val buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(0, 16384) // payloadSize
        buf.putShort(4, AirPlayMirrorHeaderParser.PAYLOAD_TYPE_VIDEO.toShort())
        // NTP: 10 seconds + 0.5 seconds (0x80000000 fractional)
        val ntp = (10L shl 32) or 0x80000000L
        buf.putLong(8, ntp)

        val parsed = AirPlayMirrorHeaderParser.parse(raw)
        assertNotNull(parsed)
        assertEquals(16384, parsed!!.payloadSize)
        assertEquals(AirPlayMirrorHeaderParser.PAYLOAD_TYPE_VIDEO, parsed.payloadType)
        assertEquals(10_500_000L, parsed.ptsUs)
    }

    @Test
    fun rejectsOversizedMirrorPayload() {
        val raw = ByteArray(AirPlayMirrorHeaderParser.HEADER_BYTES)
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0, AirPlayMirrorHeaderParser.MAX_MIRROR_PAYLOAD_BYTES + 1)
        assertNull(AirPlayMirrorHeaderParser.parse(raw))
    }
}
