package com.tvplayair.receiver.session

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class RtspAndRtpParserTest {

    @Test
    fun mirroringRetiresPriorAudioButPreservesAudioNegotiatedOnTheSameConnection() {
        val updates = mutableListOf<RaopNowPlaying?>()
        val playback = RaopNowPlayingSession { updates += it }
        val previous = Any()
        val mirror = Any()
        playback.activate(previous)
        playback.track(previous, 90L, RaopTrack("Previous audio", null, null))
        assertTrue(playback.retireAudioForMirror(mirror))
        assertNull(updates.last())
        playback.track(previous, 91L, RaopTrack("Late update", null, null))
        assertNull(updates.last())

        playback.activate(mirror)
        assertFalse(playback.retireAudioForMirror(mirror))
        assertNotNull(updates.last())
    }

    @Test
    fun mirroringPreservesCompanionAudioOnASeparateConnectionWithTheSameSessionId() {
        val playback = RaopNowPlayingSession { }
        val audio = AirPlayConnectionCrypto { ByteArray(32) }.apply { mediaSessionId = "mirror-session" }
        val mirror = AirPlayConnectionCrypto { ByteArray(32) }.apply { mediaSessionId = "mirror-session" }
        playback.activate(audio)
        assertFalse(playback.retireAudioForMirror(mirror))
        mirror.mediaSessionId = "different-session"
        assertTrue(playback.retireAudioForMirror(mirror))
    }

    @Test
    fun combinedMirrorSetupKeepsBothVideoAndAudioDescriptors() {
        val video = mapOf("type" to 110L, "streamConnectionID" to 7L)
        val audio = mapOf("type" to 96L, "sr" to 44100L)
        val encoded = BinaryPlistWriter.encode(mapOf("streams" to listOf(video, audio)))
        val decoded = BinaryPlistParser.parse(encoded) as Map<*, *>
        assertEquals(listOf(video, audio), airPlaySetupStreams(decoded))
        assertTrue(airPlaySetupStreams(mapOf("streams" to listOf(mapOf("type" to 999L)))).isEmpty())
        assertEquals(listOf(audio, video), airPlaySetupStreams(mapOf("streams" to listOf(audio, video))))
    }

    @Test
    fun freshSetupOnTheSameConnectionStartsANewAudioGeneration() {
        val published = mutableListOf<RaopNowPlaying?>()
        val playback = RaopNowPlayingSession { published += it }
        val connection = Any()
        var generations = 0
        playback.activate(connection) { generations++ }
        playback.track(connection, 90L, RaopTrack("Previous track", null, null))
        playback.artwork(connection, 90L, byteArrayOf(1))

        playback.activate(connection) { generations++ }
        assertEquals(2, generations)
        assertNull(published.last()?.artwork)
        assertNull(published.last()?.track)
        playback.track(connection, 10L, RaopTrack("New track", null, null))
        assertEquals("New track", published.last()?.track?.title)
    }

    @Test
    fun duplicateArtworkDoesNotRepublishButNewTrackStillClearsIt() {
        val events = mutableListOf<RaopNowPlaying?>()
        val playback = RaopNowPlayingSession { events += it }
        val source = Any()
        playback.activate(source)
        playback.artwork(source, 1L, byteArrayOf(1))
        playback.artwork(source, 1L, byteArrayOf(1))
        assertEquals(2, events.size)

        playback.artwork(source, 2L, null)
        assertEquals(3, events.size)
        assertNull(events.last()?.artwork)
    }

    @Test
    fun parsesTrackFieldsAndRejectsTruncatedDmapRecords() {
        fun record(key: String, value: ByteArray) = ByteBuffer.allocate(8 + value.size)
            .put(key.toByteArray()).putInt(value.size).put(value).array()
        val record = record("mlit", record("minm", "Canción".toByteArray()) +
            record("asar", "Artista".toByteArray()) + record("asal", "Álbum".toByteArray()))
        assertEquals(RaopTrack("Canción", "Artista", "Álbum"), RaopMetadataParser.track(record))
        assertEquals(42L, RaopMetadataParser.timestamp("seq=10; rtptime=42"))
        assertNull(RaopMetadataParser.track(record.copyOf(record.size - 1)))
    }

    @Test
    fun rejectsLateArtworkFromAnEarlierTrackOrSource() {
        var visible: RaopNowPlaying? = null
        val playback = RaopNowPlayingSession { visible = it }
        val source1 = Any()
        val source2 = Any()
        val track1 = RaopTrack("Primera", null, null)
        val track2 = RaopTrack("Segunda", null, null)
        val art1 = byteArrayOf(1)
        val art2 = byteArrayOf(2)
        val remote1 = RaopRemote("AAAA", "1")
        val remote2 = RaopRemote("BBBB", "2")
        playback.activate(source1, remote1)
        playback.track(source1, 0xFFFF_FF00L, track1)
        playback.artwork(source1, 0xFFFF_FF00L, art1)
        assertArrayEquals(art1, visible?.artwork)

        playback.track(source1, null, track2)
        playback.artwork(source1, 0xFFFF_FF00L, art1)
        assertNull(visible?.artwork)
        playback.track(source1, 100L, track2)
        playback.artwork(source1, 0xFFFF_FF00L, art1)
        assertEquals(track2, visible?.track)
        assertNull(visible?.artwork)

        var generationBumps = 0
        playback.activate(source2, remote2) { generationBumps++ }
        playback.withOwner(source1) { generationBumps++ }
        playback.withOwner(source2) { generationBumps++ }
        assertEquals(2, generationBumps)
        assertEquals(remote2, visible?.remote)
        assertNull(visible?.track)
        assertNull(visible?.artwork)
        playback.artwork(source2, 10L, art2)
        playback.track(source2, 10L, track2)
        playback.artwork(source1, 100L, art1)
        playback.updateRemote(source1, remote1)
        playback.clear(source1)
        assertArrayEquals(art2, visible?.artwork)
        assertEquals(remote2, visible?.remote)
        playback.clear(source2)
        assertNull(visible)
    }

    @Test
    fun identifiesRtspFromVersionInsteadOfMethodOrPath() {
        assertTrue(RtspMessageParser.isRtspRequestLine("SETUP rtsp://localhost/stream RTSP/1.0"))
        assertTrue(RtspMessageParser.isRtspRequestLine("GET /info RTSP/1.0"))
        assertFalse(RtspMessageParser.isRtspRequestLine("GET /info HTTP/1.1"))
        assertFalse(RtspMessageParser.isRtspRequestLine("GET /RTSP/1.0 HTTP/1.1"))
    }

    @Test
    fun parsesRtspRequestTransportAndVolume() {
        val raw = "SETUP rtsp://192.168.1.50/123 RTSP/1.0\r\n" +
            "CSeq: 4\r\n" +
            "DACP-ID: 1234ABCD\r\n" +
            "Active-Remote: 998877\r\n" +
            "Transport: RTP/AVP/UDP;unicast;interleaved=0-1;mode=record;control_port=6001;timing_port=6002\r\n\r\n"

        val req = RtspMessageParser.parseRequest(raw)
        assertNotNull(req)
        assertEquals("SETUP", req!!.method)
        assertEquals("4", req.cseq())
        assertEquals("1234ABCD", req.header("DACP-ID"))
        assertEquals("998877", req.header("Active-Remote"))

        val transport = RtspMessageParser.parseTransport(req.header("Transport"))
        assertEquals(6001, transport.controlPort)
        assertEquals(6002, transport.timingPort)
        assertEquals("record", transport.mode)

        assertEquals(0f, RtspMessageParser.parseVolumeLinear("volume: -144.000000")!!, 0.0001f)
        assertEquals(1f, RtspMessageParser.parseVolumeLinear("volume: 0.000000")!!, 0.0001f)
        assertTrue(RtspMessageParser.parseVolumeLinear("volume: -6.000000")!! in 0.49f..0.51f)
    }

    @Test
    fun parsesRtpAudioPacketAndRejectsInvalidVersion() {
        val packet = ByteArray(16)
        packet[0] = 0x80.toByte() // V=2, no padding, no ext, CC=0
        packet[1] = 0x60.toByte() // PT=96
        packet[2] = 0x12
        packet[3] = 0x34          // seq=0x1234
        packet[4] = 0x00
        packet[5] = 0x01
        packet[6] = 0x02
        packet[7] = 0x03          // ts=0x00010203
        packet[12] = 0xAA.toByte()
        packet[15] = 0x55.toByte()

        val parsed = RtpAudioPacketParser.parse(packet, packet.size)
        assertNotNull(parsed)
        assertEquals(96, parsed!!.payloadType)
        assertEquals(0x1234, parsed.sequenceNumber)
        assertEquals(0x00010203L, parsed.timestamp)
        assertEquals(12, parsed.payloadOffset)
        assertEquals(4, parsed.payloadLength)

        packet[0] = 0x40.toByte() // Invalid RTP version 1
        assertNull(RtpAudioPacketParser.parse(packet, packet.size))
    }

    @Test
    fun decryptsRaopAesCbcWithTrailingRemainderAndCtrMirrorVideo() {
        val key = ByteArray(16) { (it + 1).toByte() }
        val iv = ByteArray(16) { (0xA0 + it).toByte() }
        // 20 bytes = 1 full 16-byte AES block + 4 unencrypted trailing bytes per RAOP spec.
        val plaintext = ByteArray(20) { (it * 3).toByte() }

        val cbcEnc = Cipher.getInstance("AES/CBC/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        }
        val encrypted = plaintext.copyOf()
        val encryptedBlock = cbcEnc.doFinal(plaintext, 0, 16)
        System.arraycopy(encryptedBlock, 0, encrypted, 0, 16)

        val decryptedCbc = SessionCrypto.decryptRaopAudioCbc(key, iv, encrypted)
        assertArrayEquals(plaintext, decryptedCbc)

        val ctrEnc = Cipher.getInstance("AES/CTR/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        }
        val ctrCiphertext = ctrEnc.doFinal(plaintext)
        val decryptedCtr = SessionCrypto.decryptMirrorVideoCtr(key, iv, ctrCiphertext)
        assertArrayEquals(plaintext, decryptedCtr)
    }

    @Test
    fun resamples44k1StereoTo48kWithExactFrameRatio() {
        val srcFrames = 441
        val src = ShortArray(srcFrames * 2) { 1000 }
        val dst = ShortArray(1024 * 2)

        val outFrames = PcmResampler.resampleStereoI16(src, srcFrames, 44100, dst, 48000)
        assertEquals(480, outFrames)
        assertEquals(1000.toShort(), dst[0])
        assertEquals(1000.toShort(), dst[(outFrames - 1) * 2])
    }

    @Test
    fun buildsAirPlayTimingResponseAndVolumeGetParameterBody() {
        val req = ByteArray(AirPlayTimingPacket.PACKET_BYTES)
        req[0] = 0x80.toByte()
        req[1] = AirPlayTimingPacket.PT_TIMING_REQUEST.toByte()
        for (i in 24..31) req[i] = (i - 20).toByte()

        val resp = AirPlayTimingPacket.buildResponse(req, req.size, 1_700_000_000_000L)
        assertNotNull(resp)
        assertEquals(AirPlayTimingPacket.PT_TIMING_RESPONSE, resp!![1].toInt() and 0xFF)
        assertArrayEquals(req.copyOfRange(24, 32), resp.copyOfRange(8, 16))

        val vol = String(RtspMessageParser.formatVolumeBody(-12.5f), Charsets.US_ASCII)
        assertEquals("volume: -12.500000\r\n", vol)
    }
}
