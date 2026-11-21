package com.tvplayair.receiver.session

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class RtspAndRtpParserTest {

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
