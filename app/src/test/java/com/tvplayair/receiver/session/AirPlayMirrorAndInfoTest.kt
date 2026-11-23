package com.tvplayair.receiver.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AirPlayMirrorAndInfoTest {

    @Test
    fun mirrorEndNotifiesOnlyForUnexpectedActiveStreamTermination() {
        val receiver = AirPlayMirrorStreamReceiver({ _, _, _, _, _, _, _ -> }, {})
        val shouldNotify = receiver::shouldNotifyStreamEnded
        assertEquals(true, shouldNotify(7, true, false, true))
        assertEquals(false, shouldNotify(0, true, false, true))
        assertEquals(false, shouldNotify(7, false, false, true))
        assertEquals(false, shouldNotify(7, true, true, true))
        assertEquals(false, shouldNotify(7, true, false, false))
    }


    @Test
    fun distinguishesCodecConfigurationBeforeStartingTheDecoder() {
        val avc = byteArrayOf(1, 100, 0, 40, 0xFF.toByte(), 0xE1.toByte(), 0)
        val hevc = ByteArray(23).apply { this[0] = 1 }

        assertEquals(false, AirPlayMirrorHeaderParser.isHevcCodecConfig(ByteBuffer.wrap(avc)))
        assertEquals(true, AirPlayMirrorHeaderParser.isHevcCodecConfig(ByteBuffer.wrap(hevc)))
        assertNull(AirPlayMirrorHeaderParser.isHevcCodecConfig(ByteBuffer.wrap(hevc.copyOf(22))))
    }

    @Test
    fun recognizesTheEncryptedIdrSubtypeAsVideo() {
        val raw = ByteArray(AirPlayMirrorHeaderParser.HEADER_BYTES)
        val packet = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        packet.putInt(0, 32)
        packet.put(5, 0x10)

        assertEquals(AirPlayMirrorHeaderParser.PAYLOAD_TYPE_VIDEO, AirPlayMirrorHeaderParser.parse(raw)?.payloadType)
    }

    @Test
    fun unwrapsHevcConfigAndReadsSourceDimensions() {
        val header = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN)
        val sampleEntry = ByteBuffer.allocate(86 + 8 + 23)
        sampleEntry.putInt(sampleEntry.capacity()).putInt(0x68766331)
        sampleEntry.position(86)
        sampleEntry.putInt(31).putInt(0x68766343).put(1)
        sampleEntry.position(0)

        val config = AirPlayMirrorHeaderParser.unwrapCodecConfig(sampleEntry)!!
        assertEquals(23, config.remaining())
        assertEquals(true, AirPlayMirrorHeaderParser.isHevcCodecConfig(config))
        sampleEntry.putInt(86, 32)
        assertNull(AirPlayMirrorHeaderParser.unwrapCodecConfig(sampleEntry))

        header.putInt(0, sampleEntry.capacity())
        header.put(4, AirPlayMirrorHeaderParser.PAYLOAD_TYPE_CODEC_CONFIG.toByte())
        header.putFloat(16, 2868f).putFloat(20, 1320f)
        header.putFloat(40, 1170f).putFloat(44, 2532f)
        val parsed = AirPlayMirrorHeaderParser.parse(header.array())!!
        assertEquals(1170, parsed.width)
        assertEquals(2532, parsed.height)
    }

    @Test
    fun mirrorProfileRespectsResolutionCodecAndFrameRateLimits() {
        val uhd30 = MirrorDisplayProfile(3840, 2160, 30, true)
        val fhd60 = MirrorDisplayProfile(1920, 1080, 60, false)
        val hd30 = MirrorDisplayProfile(1280, 720, 30, false)
        val supported = setOf(uhd30, fhd60)

        assertEquals(uhd30, MirrorDisplayProfile.select(3840, 2160, true, supported::contains))
        assertEquals(fhd60, MirrorDisplayProfile.select(1920, 1080, true, supported::contains))
        assertEquals(fhd60, MirrorDisplayProfile.select(3840, 2160, false, supported::contains))
        assertEquals(hd30, MirrorDisplayProfile.select(3840, 2160, true) { false })
    }

    @Test
    fun binaryInfoAdvertisesTheSelectedResolutionCodecAndFrameRate() {
        val bplist = AirPlayInfoPlist.formatInfoBinary(
            deviceId = "AA:BB:CC:DD:EE:FF",
            maxWidth = 3840,
            maxHeight = 2160,
            hevcEnabled = true,
            maxFps = 30
        )
        val decoded = BinaryPlistParser.parse(bplist) as? Map<*, *>
        assertNotNull(decoded)
        assertEquals("AA:BB:CC:DD:EE:FF", decoded!!["macAddress"])
        val displays = decoded["displays"] as? List<*>
        val display0 = displays?.firstOrNull() as? Map<*, *>
        assertNotNull(display0)
        assertEquals(14L, display0!!["features"])
        assertEquals(3840L, display0["widthPixels"])
        assertEquals(2160L, display0["heightPixels"])
        assertEquals(30L, display0["maxFPS"])
        assertEquals(1.0 / 30.0, display0["refreshRate"])
        assertEquals(false, display0["rotation"])
        assertEquals((0x41CL shl 32) or 0x5A7FFFF3L, decoded["features"])
        assertNotNull(display0["uuid"])
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
