package com.tvplayair.receiver.session

import com.tvplayair.receiver.network.AirPlayAdvertiseConfig
import com.tvplayair.receiver.network.MdnsTxtRecords
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class BlockCReliabilityTest {
    private fun encryptCtr(key: ByteArray, iv: ByteArray, plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(plain)
    }

    @Test
    fun `selectCtrCandidate does not mutate failing candidates across probes`() {
        val keyA = ByteArray(16) { 0x11 }
        val ivA = ByteArray(16) { 0x22 }
        val keyB = ByteArray(16) { 0x33 }
        val ivB = ByteArray(16) { 0x44 }

        val candidates = listOf(
            AirPlayMirrorStreamReceiver.CipherCandidate("cand_a", keyA, ivA),
            AirPlayMirrorStreamReceiver.CipherCandidate("cand_b", keyB, ivB)
        )

        // First probe: invalid NAL framing for all candidates (too short / bad length).
        val garbage = byteArrayOf(0x7F, 0x7F, 0x7F, 0x7F, 0x00, 0x01)
        assertNull(AirPlayMirrorStreamReceiver.selectCtrCandidate(garbage, garbage.size, candidates))

        // Second probe: valid length-prefixed NAL encrypted from offset 0 with cand_a.
        // If cand_a's CTR state had been mutated by the failed probe, this would fail.
        val validNals = byteArrayOf(0x00, 0x00, 0x00, 0x02, 0x40, 0x01)
        val encrypted = encryptCtr(keyA, ivA, validNals)
        val match = AirPlayMirrorStreamReceiver.selectCtrCandidate(encrypted, encrypted.size, candidates)
        assertNotNull(match)
        assertEquals("cand_a", match!!.first)
        assertArrayEquals(validNals, match.third)
    }

    @Test
    fun `buildRaopTxt advertises 44100 Hz sample rate`() {
        val txt = MdnsTxtRecords.buildRaopTxt(AirPlayAdvertiseConfig())
        assertEquals("44100", txt["sr"])
    }

    @Test
    fun `jitter buffer reorders out-of-order RTP packets and emits retransmit range`() {
        val jitter = RaopRtpJitterBuffer()
        val p10 = byteArrayOf(10)
        val p11 = byteArrayOf(11)
        val p12 = byteArrayOf(12)
        val p13 = byteArrayOf(13)

        val r10 = jitter.push(10, p10)
        assertEquals(1, r10.readyPayloads.size)
        assertArrayEquals(p10, r10.readyPayloads[0])
        assertNull(r10.missingRange)

        // Packet 11 lost; 12 and 13 arrive early.
        val r12 = jitter.push(12, p12)
        assertEquals(0, r12.readyPayloads.size)
        assertEquals(11 to 1, r12.missingRange)

        val r13 = jitter.push(13, p13)
        assertEquals(0, r13.readyPayloads.size)
        assertNull(r13.missingRange) // Already requested seq 11

        // Retransmitted packet 11 arrives -> drains 11, 12, 13 in order.
        val r11 = jitter.push(11, p11)
        assertEquals(3, r11.readyPayloads.size)
        assertArrayEquals(p11, r11.readyPayloads[0])
        assertArrayEquals(p12, r11.readyPayloads[1])
        assertArrayEquals(p13, r11.readyPayloads[2])
    }

    @Test
    fun `buildRetransmitRequest formats RAOP 0x55 packet`() {
        val req = RaopAudioRtpReceiver.buildRetransmitRequest(0x1234, 2)
        assertArrayEquals(
            byteArrayOf(0x80.toByte(), 0xD5.toByte(), 0x00, 0x01, 0x12, 0x34, 0x00, 0x02),
            req
        )
    }

    @Test
    fun `RtpAudioPacketParser parses embedded 0x56 retransmit audio packet`() {
        val packet = byteArrayOf(
            // 4-byte 0x56 retransmit header
            0x80.toByte(), 0xD6.toByte(), 0x00, 0x01,
            // 12-byte RTP header (PT=96, seq=42, ts=1000)
            0x80.toByte(), 0x60, 0x00, 0x2A,
            0x00, 0x00, 0x03, 0xE8.toByte(),
            0x00, 0x00, 0x00, 0x01,
            // 3-byte audio payload
            0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte()
        )
        val parsed = RtpAudioPacketParser.parse(packet, packet.size - 4, baseOffset = 4)
        assertNotNull(parsed)
        assertEquals(96, parsed!!.payloadType)
        assertEquals(42, parsed.sequenceNumber)
        assertEquals(16, parsed.payloadOffset)
        assertEquals(3, parsed.payloadLength)
    }
}
