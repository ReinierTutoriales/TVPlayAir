package com.tvplayair.receiver.session

import com.tvplayair.receiver.ui.TvOverlayState
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.nio.ByteBuffer

class RaopUdpSessionIsolationTest {
    private fun buildRtpAudioPacket(
        seq: Int,
        timestamp: Long,
        ssrc: Long,
        payload: ByteArray,
        payloadType: Int = 96
    ): ByteArray {
        val out = ByteArray(12 + payload.size)
        out[0] = 0x80.toByte()
        out[1] = (payloadType and 0x7F).toByte()
        out[2] = ((seq ushr 8) and 0xFF).toByte()
        out[3] = (seq and 0xFF).toByte()
        ByteBuffer.wrap(out, 4, 8).apply {
            putInt(timestamp.toInt())
            putInt(ssrc.toInt())
        }
        System.arraycopy(payload, 0, out, 12, payload.size)
        return out
    }

    private fun buildRetransmit0x56Packet(
        seq: Int,
        timestamp: Long,
        ssrc: Long,
        payload: ByteArray
    ): ByteArray {
        val inner = buildRtpAudioPacket(seq, timestamp, ssrc, payload)
        val out = ByteArray(4 + inner.size)
        out[0] = 0x80.toByte()
        out[1] = 0xD6.toByte() // 0x80 | 0x56
        out[2] = 0x00
        out[3] = 0x01
        System.arraycopy(inner, 0, out, 4, inner.size)
        return out
    }

    @Test
    fun `case 1 - replacement SETUP closes transport A, rejects stray A packets and accepts B`() {
        var currentGen = 1
        val decoded = mutableListOf<Pair<ByteArray, Int>>()
        val receiver = RaopAudioRtpReceiver(
            payloadObserver = { bytes, gen -> decoded.add(bytes to gen) }
        )
        try {
            val iphoneIpv4 = InetAddress.getByName("192.168.1.50")
            val iphoneIpv4MappedV6 = InetAddress.getByAddress(
                byteArrayOf(
                    0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                    0xFF.toByte(), 0xFF.toByte(),
                    192.toByte(), 168.toByte(), 1, 50
                )
            )
            val otherPeer = InetAddress.getByName("192.168.1.99")
            val ownerA = Any()
            val ownerB = Any()

            assertTrue(receiver.start(iphoneIpv4, remoteControlPort = 55001, owner = ownerA, generation = currentGen))
            val transportA = receiver.currentTransportForTest()!!
            val portA = receiver.localAudioPort
            val controlPortA = receiver.localControlPort
            assertTrue(portA > 0)
            assertTrue(controlPortA > 0)

            val pktA100 = buildRtpAudioPacket(100, 1000L, 0x1111_2222L, byteArrayOf(0xA1.toByte()))
            assertTrue(receiver.ingestAudioDatagram(transportA, pktA100, pktA100.size, iphoneIpv4))
            assertEquals(1, decoded.size)
            assertEquals(1, decoded[0].second)

            // Replacement SETUP for session B (e.g. Mirror audio) on new UDP sockets.
            currentGen = 2
            val inFlight = buildRtpAudioPacket(101, 1352L, 0x1111_2222L, byteArrayOf(0x01))
            assertTrue(receiver.ingestAudioDatagram(transportA, inFlight, inFlight.size, iphoneIpv4))
            assertEquals(1, decoded.last().second)
            var decoderConfigured = false
            assertTrue(receiver.start(iphoneIpv4, remoteControlPort = 55002, owner = ownerB, generation = currentGen) {
                assertTrue(transportA.closed.get())
                assertNull(receiver.currentTransportForTest())
                decoderConfigured = true
            })
            assertTrue(decoderConfigured)
            val transportB = receiver.currentTransportForTest()!!
            val portB = receiver.localAudioPort
            assertTrue(transportA.closed.get())
            assertNotEquals(portA, portB)
            assertNotEquals(controlPortA, receiver.localControlPort)
            decoded.clear()

            // 1a) Packet still queued on old transportA is rejected.
            val pktA101 = buildRtpAudioPacket(101, 1352L, 0x1111_2222L, byteArrayOf(0xA2.toByte()))
            assertFalse(receiver.ingestAudioDatagram(transportA, pktA101, pktA101.size, iphoneIpv4))

            // 1b) Stray late packet from A (retired SSRC 0x1111_2222) arriving first on transportB is rejected.
            assertFalse(receiver.ingestAudioDatagram(transportB, pktA101, pktA101.size, iphoneIpv4))

            // 1c) Packet from a different LAN host is rejected.
            val pktOther = buildRtpAudioPacket(500, 5000L, 0x3333_4444L, byteArrayOf(0xFF.toByte()))
            assertFalse(receiver.ingestAudioDatagram(transportB, pktOther, pktOther.size, otherPeer))
            assertTrue(decoded.isEmpty())

            // 1d) Valid packet from B (including IPv4-mapped IPv6 address for the same iPhone) decodes under gen 2.
            val pktB500 = buildRtpAudioPacket(500, 5000L, 0x3333_4444L, byteArrayOf(0xB1.toByte()))
            assertTrue(receiver.ingestAudioDatagram(transportB, pktB500, pktB500.size, iphoneIpv4MappedV6))
            assertEquals(1, decoded.size)
            assertArrayEquals(byteArrayOf(0xB1.toByte()), decoded[0].first)
            assertEquals(2, decoded[0].second)
        } finally {
            receiver.stop()
        }
    }

    @Test
    fun `case 2 - 0x56 control retransmission requires valid sender and pending requested gap`() {
        val currentGen = 2
        val decoded = mutableListOf<Pair<ByteArray, Int>>()
        val receiver = RaopAudioRtpReceiver(
            payloadObserver = { bytes, gen -> decoded.add(bytes to gen) }
        )
        try {
            val iphone = InetAddress.getByName("192.168.1.50")
            val otherPeer = InetAddress.getByName("192.168.1.99")
            val ownerB = Any()
            assertTrue(receiver.start(iphone, remoteControlPort = 55002, owner = ownerB, generation = currentGen))
            val transportB = receiver.currentTransportForTest()!!

            // Packet 500 arrives, then 502 arrives (creating a pending requested gap for seq 501).
            val pkt500 = buildRtpAudioPacket(500, 5000L, 0x3333_4444L, byteArrayOf(0x10))
            val pkt502 = buildRtpAudioPacket(502, 5960L, 0x3333_4444L, byteArrayOf(0x12))
            assertTrue(receiver.ingestAudioDatagram(transportB, pkt500, pkt500.size, iphone))
            assertFalse(receiver.ingestAudioDatagram(transportB, pkt502, pkt502.size, iphone))
            decoded.clear()

            // Unsolicited 0x56 for seq 499 (already passed) or seq 510 (never requested) is rejected.
            val unsol499 = buildRetransmit0x56Packet(499, 4520L, 0x3333_4444L, byteArrayOf(0x09))
            val unsol510 = buildRetransmit0x56Packet(510, 9800L, 0x3333_4444L, byteArrayOf(0x1A))
            assertFalse(receiver.ingestControlDatagram(transportB, unsol499, unsol499.size, iphone, 55002))
            assertFalse(receiver.ingestControlDatagram(transportB, unsol510, unsol510.size, iphone, 55002))

            // 0x56 for seq 501 from wrong port or wrong IP is rejected.
            val valid501 = buildRetransmit0x56Packet(501, 5480L, 0x3333_4444L, byteArrayOf(0x11))
            assertFalse(receiver.ingestControlDatagram(transportB, valid501, valid501.size, iphone, 9999))
            assertFalse(receiver.ingestControlDatagram(transportB, valid501, valid501.size, otherPeer, 55002))
            assertTrue(decoded.isEmpty())

            // Valid 0x56 for pending seq 501 from negotiated control port drains 501 and 502 in order.
            assertTrue(receiver.ingestControlDatagram(transportB, valid501, valid501.size, iphone, 55002))
            assertEquals(2, decoded.size)
            assertArrayEquals(byteArrayOf(0x11), decoded[0].first)
            assertArrayEquals(byteArrayOf(0x12), decoded[1].first)

            // Duplicate 0x56 for seq 501 after recovery is rejected.
            assertFalse(receiver.ingestControlDatagram(transportB, valid501, valid501.size, iphone, 55002))
        } finally {
            receiver.stop()
        }
    }

    @Test
    fun `case 3 - reused SSRC confirms on consecutive sequence across 65535 to 0 wrap`() {
        var currentGen = 1
        val decoded = mutableListOf<Pair<ByteArray, Int>>()
        val receiver = RaopAudioRtpReceiver(
            payloadObserver = { bytes, gen -> decoded.add(bytes to gen) }
        )
        try {
            val iphone = InetAddress.getByName("192.168.1.50")
            val sharedSsrc = 0xAAAA_BBBBL

            // Session A uses sharedSsrc.
            assertTrue(receiver.start(iphone, remoteControlPort = 55001, owner = Any(), generation = currentGen))
            val transportA = receiver.currentTransportForTest()!!
            val pktA = buildRtpAudioPacket(10, 1000L, sharedSsrc, byteArrayOf(0x01))
            assertTrue(receiver.ingestAudioDatagram(transportA, pktA, pktA.size, iphone))

            // Session B reuses sharedSsrc without RTP-Info anchor, starting across 65535 -> 0 wrap.
            currentGen = 2
            assertTrue(receiver.start(iphone, remoteControlPort = 55002, owner = Any(), generation = currentGen))
            val transportB = receiver.currentTransportForTest()!!
            decoded.clear()

            val pktB65535 = buildRtpAudioPacket(65535, 2000L, sharedSsrc, byteArrayOf(0x21))
            val pktB0 = buildRtpAudioPacket(0, 2352L, sharedSsrc, byteArrayOf(0x22))

            // First packet with retired SSRC enters probation.
            assertFalse(receiver.ingestAudioDatagram(transportB, pktB65535, pktB65535.size, iphone))
            assertTrue(decoded.isEmpty())

            // Second consecutive packet (65535 -> 0) confirms stream B and emits both packets in order.
            assertTrue(receiver.ingestAudioDatagram(transportB, pktB0, pktB0.size, iphone))
            assertEquals(2, decoded.size)
            assertArrayEquals(byteArrayOf(0x21), decoded[0].first)
            assertArrayEquals(byteArrayOf(0x22), decoded[1].first)
            assertEquals(2, decoded[0].second)
            assertEquals(2, decoded[1].second)
        } finally {
            receiver.stop()
        }
    }

    @Test
    fun `case 4 - legitimate FLUSH on same transport advances generation and applies RTP-Info seq anchor`() {
        var currentGen = 2
        val decoded = mutableListOf<Pair<ByteArray, Int>>()
        val receiver = RaopAudioRtpReceiver(
            payloadObserver = { bytes, gen -> decoded.add(bytes to gen) }
        )
        try {
            val iphone = InetAddress.getByName("192.168.1.50")
            val owner = Any()
            val ssrc = 0x7777_8888L

            assertTrue(receiver.start(iphone, remoteControlPort = 55002, owner = owner, generation = currentGen))
            val transport = receiver.currentTransportForTest()!!
            val initialPort = receiver.localAudioPort

            val preFlush = buildRtpAudioPacket(500, 5000L, ssrc, byteArrayOf(0x31))
            assertTrue(receiver.ingestAudioDatagram(transport, preFlush, preFlush.size, iphone))
            assertEquals(1, decoded.size)
            assertEquals(2, decoded[0].second)
            decoded.clear()

            // Owner sends FLUSH with RTP-Info: seq=900;rtptime=90000
            val flushSeq = RaopMetadataParser.sequenceNumber("seq=900;rtptime=90000")
            assertEquals(900, flushSeq)
            currentGen = 3
            val beforeReset = buildRtpAudioPacket(501, 5352L, ssrc, byteArrayOf(0x01))
            assertTrue(receiver.ingestAudioDatagram(transport, beforeReset, beforeReset.size, iphone))
            assertEquals(2, decoded.single().second)
            decoded.clear()
            receiver.onFlush(owner, newGeneration = currentGen, flushSeq = flushSeq)

            // Same UDP transport and port remain active.
            assertSame(transport, receiver.currentTransportForTest())
            assertEquals(initialPort, receiver.localAudioPort)

            // Late pre-flush packet (seq=501) is behind anchor 900 and rejected.
            val stale501 = buildRtpAudioPacket(501, 5352L, ssrc, byteArrayOf(0x32))
            assertFalse(receiver.ingestAudioDatagram(transport, stale501, stale501.size, iphone))
            assertTrue(decoded.isEmpty())

            // Post-flush packet at seq=900 is accepted and tagged with generation 3.
            val postFlush900 = buildRtpAudioPacket(900, 90000L, ssrc, byteArrayOf(0x33))
            assertTrue(receiver.ingestAudioDatagram(transport, postFlush900, postFlush900.size, iphone))
            assertEquals(1, decoded.size)
            assertArrayEquals(byteArrayOf(0x33), decoded[0].first)
            assertEquals(3, decoded[0].second)
        } finally {
            receiver.stop()
        }
    }

    @Test
    fun `case 5 - late Binder stop and NowPlaying from generation A do not clobber active generation B`() {
        val trackA = RaopNowPlaying(RaopTrack("Song A", "Artist A", null))
        val trackB = RaopNowPlaying(RaopTrack("Song B", "Artist B", null))

        var state = TvOverlayState()
            .withAudioStreamStarted(1)
            .withNowPlaying(trackA, generationId = 1)
        assertEquals(1, state.audioGeneration)
        assertEquals("Song A", state.nowPlaying?.track?.title)

        // Session B starts (generation 2).
        state = state
            .withAudioStreamStarted(2)
            .withNowPlaying(trackB, generationId = 2)
        assertEquals(2, state.audioGeneration)
        assertEquals("Song B", state.nowPlaying?.track?.title)

        // Late metadata and late stop from generation 1 arrive out-of-order over Binder.
        state = state
            .withNowPlaying(trackA, generationId = 1)
            .withAudioStreamStopped(generationId = 1)
        assertEquals(2, state.audioGeneration)
        assertEquals("Song B", state.nowPlaying?.track?.title)

        // Current generation 2 stop clears audio state cleanly.
        state = state.withAudioStreamStopped(generationId = 2)
        assertNull(state.audioGeneration)
        assertNull(state.nowPlaying)
    }
}
