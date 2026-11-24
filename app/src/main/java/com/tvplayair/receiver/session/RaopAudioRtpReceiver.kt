package com.tvplayair.receiver.session

import android.util.Log
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

internal data class RaopClockAnchor(val rtpTimestamp: Long, val senderTimeUs: Long) {
    fun senderTimeUsAt(timestamp: Long, sampleRate: Int): Long =
        senderTimeUs + (timestamp - rtpTimestamp).toInt().toLong() * 1_000_000L / sampleRate

    companion object {
        fun parse(data: ByteArray, length: Int): RaopClockAnchor? {
            if (length !in 20..data.size || (data[0].toInt() and 0xc0) != 0x80 ||
                (data[1].toInt() and 0x7f) != 0x54
            ) return null
            val buffer = ByteBuffer.wrap(data, 0, length)
            val rtp = buffer.getInt(4).toLong() and 0xffffffffL
            val ntp = buffer.getLong(8)
            if (ntp == 0L) return null
            val fraction = ntp and 0xffffffffL
            return RaopClockAnchor(
                rtp,
                (ntp ushr 32) * 1_000_000L + ((fraction * 1_000_000L) ushr 32)
            )
        }
    }
}

data class RtpAudioPacket(
    val payloadType: Int,
    val sequenceNumber: Int,
    val timestamp: Long,
    val payloadOffset: Int,
    val payloadLength: Int,
    val ssrc: Long = 0L
)

object RtpAudioPacketParser {
    const val MIN_RTP_HEADER_BYTES = 12

    fun parse(packet: ByteArray, length: Int, baseOffset: Int = 0): RtpAudioPacket? {
        if (baseOffset < 0 || length < MIN_RTP_HEADER_BYTES || packet.size < baseOffset + length) return null
        val b0 = packet[baseOffset].toInt() and 0xFF
        val version = (b0 ushr 6) and 0x03
        if (version != 2) return null

        val hasPadding = (b0 and 0x20) != 0
        val hasExtension = (b0 and 0x10) != 0
        val csrcCount = b0 and 0x0F

        val b1 = packet[baseOffset + 1].toInt() and 0xFF
        val payloadType = b1 and 0x7F
        val seq = ((packet[baseOffset + 2].toInt() and 0xFF) shl 8) or
            (packet[baseOffset + 3].toInt() and 0xFF)
        val ts = ((packet[baseOffset + 4].toLong() and 0xFF) shl 24) or
            ((packet[baseOffset + 5].toLong() and 0xFF) shl 16) or
            ((packet[baseOffset + 6].toLong() and 0xFF) shl 8) or
            (packet[baseOffset + 7].toLong() and 0xFF)
        val ssrc = ((packet[baseOffset + 8].toLong() and 0xFF) shl 24) or
            ((packet[baseOffset + 9].toLong() and 0xFF) shl 16) or
            ((packet[baseOffset + 10].toLong() and 0xFF) shl 8) or
            (packet[baseOffset + 11].toLong() and 0xFF)

        var headerLen = MIN_RTP_HEADER_BYTES + csrcCount * 4
        if (headerLen > length) return null

        if (hasExtension) {
            if (headerLen + 4 > length) return null
            val extWords = ((packet[baseOffset + headerLen + 2].toInt() and 0xFF) shl 8) or
                (packet[baseOffset + headerLen + 3].toInt() and 0xFF)
            headerLen += 4 + extWords * 4
            if (headerLen > length) return null
        }

        var payloadEnd = length
        if (hasPadding) {
            val padBytes = packet[baseOffset + length - 1].toInt() and 0xFF
            if (padBytes <= 0 || headerLen + padBytes > length) return null
            payloadEnd -= padBytes
        }

        val payloadLen = payloadEnd - headerLen
        if (payloadLen <= 0) return null

        return RtpAudioPacket(
            payloadType = payloadType,
            sequenceNumber = seq,
            timestamp = ts,
            payloadOffset = baseOffset + headerLen,
            payloadLength = payloadLen,
            ssrc = ssrc
        )
    }
}

internal class RaopRtpJitterBuffer(
    private val maxReorderWindow: Int = 48,
    private val maxHoldPackets: Int = 12
) {
    data class Packet(val payload: ByteArray, val rtpTimestamp: Long)

    data class PushResult(
        val readyPayloads: List<Packet>,
        val missingRange: Pair<Int, Int>?
    )

    private var nextExpectedSeq: Int = -1
    private var lastRequestedSeq: Int = -1
    private val buffered = HashMap<Int, Packet>()
    private val requestedMissingSeqs = HashSet<Int>()
    private var activeSsrc: Long? = null
    private var retiredSsrc: Long? = null
    private var probationSsrc: Long? = null
    private var probationNextSeq: Int = -1
    private var probationPacket: Pair<Int, Packet>? = null

    @Synchronized
    fun reset(
        initialSeq: Int? = null,
        retireCurrentSsrc: Boolean = false,
        preserveActiveSsrc: Boolean = false
    ) {
        if (retireCurrentSsrc && activeSsrc != null) {
            retiredSsrc = activeSsrc
        }
        if (!preserveActiveSsrc) {
            activeSsrc = null
        }
        probationSsrc = null
        probationNextSeq = -1
        probationPacket = null
        nextExpectedSeq = initialSeq?.and(0xFFFF) ?: -1
        lastRequestedSeq = -1
        requestedMissingSeqs.clear()
        buffered.clear()
    }

    @Synchronized
    fun seedInitialSeq(initialSeq: Int) {
        if (nextExpectedSeq == -1 && buffered.isEmpty()) {
            nextExpectedSeq = initialSeq and 0xFFFF
        }
    }

    @Synchronized
    fun isPendingRetransmit(seq: Int): Boolean {
        if (nextExpectedSeq == -1) return false
        val normSeq = seq and 0xFFFF
        if (normSeq !in requestedMissingSeqs || buffered.containsKey(normSeq)) return false
        val delta = (normSeq - nextExpectedSeq) and 0xFFFF
        return delta in 0..maxReorderWindow
    }

    @Synchronized
    fun push(
        seq: Int,
        payload: ByteArray,
        rtpTimestamp: Long = 0L,
        ssrc: Long? = null
    ): PushResult {
        val packet = Packet(payload, rtpTimestamp)
        val normSeq = seq and 0xFFFF
        var promotedFromProbation: Pair<Int, Packet>? = null

        if (ssrc != null) {
            val currentSsrc = activeSsrc
            if (currentSsrc == null) {
                val anchored = nextExpectedSeq != -1 &&
                    ((normSeq - nextExpectedSeq) and 0xFFFF) <= maxReorderWindow
                val needsProbation = (retiredSsrc != null && ssrc == retiredSsrc && !anchored) ||
                    (nextExpectedSeq != -1 && !anchored)
                if (needsProbation) {
                    if (probationSsrc == ssrc && normSeq == probationNextSeq) {
                        activeSsrc = ssrc
                        promotedFromProbation = probationPacket
                        probationSsrc = null
                        probationNextSeq = -1
                        probationPacket = null
                        if (nextExpectedSeq != -1 && promotedFromProbation != null) {
                            nextExpectedSeq = promotedFromProbation.first
                        }
                    } else {
                        probationSsrc = ssrc
                        probationNextSeq = (normSeq + 1) and 0xFFFF
                        probationPacket = normSeq to packet
                        return PushResult(emptyList(), null)
                    }
                } else {
                    activeSsrc = ssrc
                    probationSsrc = null
                    probationNextSeq = -1
                    probationPacket = null
                }
            } else if (ssrc != currentSsrc) {
                if (probationSsrc == ssrc && normSeq == probationNextSeq) {
                    activeSsrc = ssrc
                    promotedFromProbation = probationPacket
                    probationSsrc = null
                    probationNextSeq = -1
                    probationPacket = null
                    buffered.clear()
                    requestedMissingSeqs.clear()
                    lastRequestedSeq = -1
                    nextExpectedSeq = promotedFromProbation?.first ?: normSeq
                } else {
                    probationSsrc = ssrc
                    probationNextSeq = (normSeq + 1) and 0xFFFF
                    probationPacket = normSeq to packet
                    return PushResult(emptyList(), null)
                }
            } else {
                probationSsrc = null
                probationNextSeq = -1
                probationPacket = null
            }
        }

        if (promotedFromProbation != null) {
            val (firstSeq, firstPkt) = promotedFromProbation
            requestedMissingSeqs.remove(firstSeq)
            requestedMissingSeqs.remove(normSeq)
            nextExpectedSeq = (normSeq + 1) and 0xFFFF
            val ready = ArrayList<Packet>(buffered.size + 2)
            ready.add(firstPkt)
            ready.add(packet)
            drainContiguous(ready)
            return PushResult(ready, null)
        }

        if (nextExpectedSeq == -1) {
            nextExpectedSeq = (normSeq + 1) and 0xFFFF
            return PushResult(listOf(packet), null)
        }

        val delta = (normSeq - nextExpectedSeq) and 0xFFFF
        if (delta == 0) {
            requestedMissingSeqs.remove(normSeq)
            val ready = ArrayList<Packet>(buffered.size + 1)
            ready.add(packet)
            nextExpectedSeq = (nextExpectedSeq + 1) and 0xFFFF
            drainContiguous(ready)
            return PushResult(ready, null)
        }

        // Duplicate or late packet behind nextExpectedSeq.
        if (delta > 32768) {
            return PushResult(emptyList(), null)
        }

        // Large forward jump (stream reset / seek): flush stale buffered packets.
        if (delta > maxReorderWindow) {
            buffered.clear()
            requestedMissingSeqs.clear()
            lastRequestedSeq = -1
            nextExpectedSeq = (normSeq + 1) and 0xFFFF
            return PushResult(listOf(packet), null)
        }

        requestedMissingSeqs.remove(normSeq)
        buffered[normSeq] = packet
        val ready = ArrayList<Packet>()
        if (buffered.size > maxHoldPackets) {
            // Advance past unrecovered gap to bound latency.
            val minSeq = buffered.keys.minByOrNull { (it - nextExpectedSeq) and 0xFFFF }
            if (minSeq != null) {
                nextExpectedSeq = minSeq
                drainContiguous(ready)
            }
        }

        val missingCount = (normSeq - nextExpectedSeq) and 0xFFFF
        val missingRange = if (missingCount in 1..maxReorderWindow && nextExpectedSeq != lastRequestedSeq) {
            lastRequestedSeq = nextExpectedSeq
            val count = missingCount.coerceAtMost(maxHoldPackets)
            for (i in 0 until count) {
                val missingSeq = (nextExpectedSeq + i) and 0xFFFF
                if (!buffered.containsKey(missingSeq)) {
                    requestedMissingSeqs.add(missingSeq)
                }
            }
            nextExpectedSeq to count
        } else {
            null
        }
        return PushResult(ready, missingRange)
    }

    private fun drainContiguous(out: MutableList<Packet>) {
        while (true) {
            val next = buffered.remove(nextExpectedSeq) ?: break
            requestedMissingSeqs.remove(nextExpectedSeq)
            out.add(next)
            nextExpectedSeq = (nextExpectedSeq + 1) and 0xFFFF
        }
    }
}

/**
 * UDP RTP audio receiver for RAOP (default port 6000) inside :session.
 * Decrypts optional AES-128-CBC payloads, decodes to 48 kHz PCM stereo,
 * and writes into the lock-free audio SharedRingBuffer.
 */
class RaopAudioRtpReceiver(
    private val payloadObserver: ((ByteArray, Int) -> Unit)? = null
) {
    companion object {
        private const val TAG = "RaopAudioRtpReceiver"
        const val DEFAULT_AUDIO_PORT = 6000
        const val DEFAULT_CONTROL_PORT = 6001
        const val DEFAULT_TIMING_PORT = 6002
        private const val MAX_UDP_PACKET = 4096

        internal fun buildRetransmitRequest(firstSeq: Int, count: Int): ByteArray {
            val seq = firstSeq and 0xFFFF
            val cnt = count.coerceIn(1, 0xFFFF)
            return byteArrayOf(
                0x80.toByte(),
                0xD5.toByte(), // 0x80 | 0x55 (RAOP retransmit request)
                0x00,
                0x01,
                ((seq ushr 8) and 0xFF).toByte(),
                (seq and 0xFF).toByte(),
                ((cnt ushr 8) and 0xFF).toByte(),
                (cnt and 0xFF).toByte()
            )
        }

        internal fun isExpectedPeer(expected: InetAddress?, actual: InetAddress?): Boolean {
            if (expected == null) return true
            if (actual == null) return false
            return normalizeIpBytes(expected.address).contentEquals(normalizeIpBytes(actual.address))
        }

        private fun normalizeIpBytes(raw: ByteArray): ByteArray {
            if (raw.size == 16 &&
                (0 until 10).all { raw[it] == 0.toByte() } &&
                raw[10] == 0xFF.toByte() &&
                raw[11] == 0xFF.toByte()
            ) {
                return raw.copyOfRange(12, 16)
            }
            return raw
        }
    }

    internal class TransportSession(
        val owner: Any?,
        var generation: Int, // Guarded by decodeLock.
        val peerAddress: InetAddress?,
        @Volatile var remoteControlPort: Int,
        val audioSocket: DatagramSocket?,
        val controlSocket: DatagramSocket?,
        val closed: AtomicBoolean = AtomicBoolean(false)
    ) {
        fun close() {
            closed.set(true)
            runCatching { audioSocket?.close() }
            runCatching { controlSocket?.close() }
        }
    }

    private val running = AtomicBoolean(false)
    private val decoder = RaopAudioDecoder()
    private val jitterBuffer = RaopRtpJitterBuffer()
    private val decodeLock = Any()
    @Volatile
    private var activeTransport: TransportSession? = null

    @Volatile
    private var aesKey: ByteArray? = null
    @Volatile
    private var candidateAesKeys: List<ByteArray> = emptyList()
    @Volatile
    private var alacKeySelector: RaopAlacKeySelector? = null
    @Volatile
    private var keyConfirmed: Boolean = false
    @Volatile
    private var aesIv: ByteArray? = null
    @Volatile
    private var unencryptedPrefix0: Int = -1
    @Volatile
    private var unencryptedPrefix1: Int = -1
    private data class SessionClockAnchor(val generation: Int, val anchor: RaopClockAnchor)

    @Volatile
    private var clockAnchor: SessionClockAnchor? = null
    @Volatile
    private var clockSampleRate = 0
    private var timestampedPackets = 0L
    @Volatile
    private var rxPackets: Long = 0L
    @Volatile
    private var unmatchedAlacPackets: Long = 0L
    @Volatile
    private var lastAlacSelector: RaopAlacKeySelector? = null
    private var workerThread: Thread? = null
    private var controlThread: Thread? = null

    internal val localAudioPort: Int
        get() = activeTransport?.audioSocket?.localPort ?: 0

    internal val localControlPort: Int
        get() = activeTransport?.controlSocket?.localPort ?: 0

    internal fun currentTransportForTest(): TransportSession? = activeTransport

    fun configureSession(
        codec: RaopAudioCodec,
        sampleRate: Int,
        csd0: ByteArray? = null,
        sessionAesKey: ByteArray? = null,
        sessionAesIv: ByteArray? = null,
        samplesPerFrame: Int = 480,
        alternateAesKey: ByteArray? = null,
        extraAesKeys: List<ByteArray> = emptyList()
    ) {
        closeActiveTransport(retireSsrc = true)
        synchronized(decodeLock) {
            val primary = sessionAesKey?.takeIf { it.size == 16 }?.copyOf()
            val alt = alternateAesKey?.takeIf { it.size == 16 }?.copyOf()
            val candidates = buildList {
                if (primary != null) add(primary)
                if (alt != null && (primary == null || !primary.contentEquals(alt))) add(alt)
                for (extra in extraAesKeys) {
                    if (extra.size == 16 && none { it.contentEquals(extra) }) {
                        add(extra.copyOf())
                    }
                }
            }
            aesKey = primary
            candidateAesKeys = candidates
            keyConfirmed = candidates.size <= 1
            val iv = sessionAesIv?.takeIf { it.size == 16 }?.copyOf()
            aesIv = iv
            alacKeySelector = if (codec == RaopAudioCodec.ALAC && iv != null && candidates.isNotEmpty()) {
                RaopAlacKeySelector(candidates, iv)
            } else null
            clockAnchor = null
            clockSampleRate = sampleRate
            rxPackets = 0L
            unmatchedAlacPackets = 0L
            unencryptedPrefix0 = -1
            unencryptedPrefix1 = -1
            decoder.configure(codec, sampleRate, csd0, samplesPerFrame)
        }
    }

    fun start(peer: InetAddress?, remoteControlPort: Int?, owner: Any, generation: Int): Boolean {
        closeActiveTransport(retireSsrc = true)
        val udp = bindUdp(0) ?: bindUdp(DEFAULT_AUDIO_PORT) ?: return false
        val ctrlUdp = bindUdp(0) ?: bindUdp(DEFAULT_CONTROL_PORT)
        if (ctrlUdp == null) {
            udp.close()
            return false
        }

        val session = TransportSession(
            owner = owner,
            generation = generation,
            peerAddress = peer,
            remoteControlPort = remoteControlPort?.takeIf { it in 1..65535 } ?: 0,
            audioSocket = udp,
            controlSocket = ctrlUdp
        )
        synchronized(decodeLock) {
            activeTransport = session
            running.set(true)
        }
        workerThread = Thread({ receiveLoop(session, udp) }, "TVPlayAir-RaopAudioUdp").apply {
            isDaemon = true
            start()
        }
        controlThread = Thread({ controlLoop(session, ctrlUdp) }, "TVPlayAir-RaopControlUdp").apply {
            isDaemon = true
            start()
        }
        Log.i(TAG, "RAOP audio UDP receiver listening on port ${udp.localPort} (control=${ctrlUdp.localPort})")
        return true
    }

    fun onRecord(owner: Any?, initialSeq: Int?) {
        if (initialSeq == null) return
        synchronized(decodeLock) {
            val current = activeTransport ?: return
            if (current.closed.get() || (owner != null && current.owner !== owner)) return
            jitterBuffer.seedInitialSeq(initialSeq)
        }
    }

    fun onFlush(owner: Any?, newGeneration: Int, flushSeq: Int?) {
        synchronized(decodeLock) {
            val current = activeTransport ?: return
            if (current.closed.get() || (owner != null && current.owner !== owner)) return
            current.generation = newGeneration
            clockAnchor = null
            jitterBuffer.reset(initialSeq = flushSeq, preserveActiveSsrc = true)
        }
    }

    private fun closeActiveTransport(retireSsrc: Boolean) {
        val prevWorker: Thread?
        val prevControl: Thread?
        synchronized(decodeLock) {
            running.set(false)
            activeTransport?.close()
            activeTransport = null
            jitterBuffer.reset(retireCurrentSsrc = retireSsrc)
            clockAnchor = null
            prevWorker = workerThread
            prevControl = controlThread
            workerThread = null
            controlThread = null
        }
        prevWorker?.let {
            it.interrupt()
            if (it !== Thread.currentThread()) runCatching { it.join(500L) }
        }
        prevControl?.let {
            it.interrupt()
            if (it !== Thread.currentThread()) runCatching { it.join(500L) }
        }
    }

    private fun bindUdp(port: Int): DatagramSocket? {
        val udp = DatagramSocket(null)
        return try {
            udp.bind(InetSocketAddress(port))
            udp
        } catch (error: IOException) {
            udp.close()
            Log.w(TAG, "Failed to bind RAOP UDP port $port", error)
            null
        }
    }

    fun stop() {
        closeActiveTransport(retireSsrc = false)
        synchronized(decodeLock) {
            decoder.release()
        }
    }

    private fun controlLoop(session: TransportSession, ctrlUdp: DatagramSocket) {
        val rxBuf = ByteArray(MAX_UDP_PACKET)
        val packet = DatagramPacket(rxBuf, rxBuf.size)
        while (!session.closed.get() && !ctrlUdp.isClosed) {
            packet.length = rxBuf.size
            try {
                ctrlUdp.receive(packet)
            } catch (error: IOException) {
                if (!ctrlUdp.isClosed && !session.closed.get()) {
                    Log.w(TAG, "Control socket read failed", error)
                }
                break
            }
            if (ctrlUdp.isClosed || session.closed.get() || packet.length < 4) continue
            ingestControlDatagram(session, rxBuf, packet.length, packet.address, packet.port)
        }
    }

    internal fun ingestControlDatagram(
        session: TransportSession,
        rxBuf: ByteArray,
        length: Int,
        senderAddress: InetAddress?,
        senderPort: Int
    ): Boolean {
        if (session.closed.get() || activeTransport !== session || length < 4) return false
        if (!isExpectedPeer(session.peerAddress, senderAddress)) return false
        if (session.remoteControlPort <= 0 && senderPort > 0) {
            session.remoteControlPort = senderPort
        }
        if (session.remoteControlPort > 0 && senderPort != session.remoteControlPort) return false

        val payloadType = rxBuf[1].toInt() and 0x7F
        if (payloadType == 0x54) {
            val anchor = RaopClockAnchor.parse(rxBuf, length) ?: return false
            synchronized(decodeLock) {
                if (session.closed.get() || activeTransport !== session) return false
                clockAnchor = SessionClockAnchor(session.generation, anchor)
            }
            return true
        }
        if (payloadType == 0x56 && length > 4 + RtpAudioPacketParser.MIN_RTP_HEADER_BYTES) {
            val parsed = RtpAudioPacketParser.parse(rxBuf, length - 4, baseOffset = 4) ?: return false
            if (parsed.payloadType != 96) return false
            return enqueueRtpPacket(session, parsed, rxBuf, retransmitted = true)
        }
        return false
    }

    private fun sendRetransmitRequest(session: TransportSession, firstSeq: Int, count: Int) {
        if (session.closed.get() || activeTransport !== session) return
        val ctrlUdp = session.controlSocket ?: return
        val addr = session.peerAddress ?: return
        val port = session.remoteControlPort
        if (ctrlUdp.isClosed || port <= 0) return
        val req = buildRetransmitRequest(firstSeq, count)
        runCatching {
            ctrlUdp.send(DatagramPacket(req, req.size, addr, port))
        }
    }

    private fun receiveLoop(session: TransportSession, udp: DatagramSocket) {
        val rxBuf = ByteArray(MAX_UDP_PACKET)
        val packet = DatagramPacket(rxBuf, rxBuf.size)
        while (!session.closed.get() && !udp.isClosed) {
            packet.length = rxBuf.size
            try {
                udp.receive(packet)
            } catch (error: IOException) {
                if (!udp.isClosed && !session.closed.get()) {
                    Log.w(TAG, "Audio socket read failed", error)
                }
                break
            }
            if (udp.isClosed || session.closed.get()) break
            ingestAudioDatagram(session, rxBuf, packet.length, packet.address)
        }
    }

    internal fun ingestAudioDatagram(
        session: TransportSession,
        rxBuf: ByteArray,
        length: Int,
        senderAddress: InetAddress?
    ): Boolean {
        if (session.closed.get() || activeTransport !== session) return false
        if (!isExpectedPeer(session.peerAddress, senderAddress)) return false
        val parsed = RtpAudioPacketParser.parse(rxBuf, length) ?: return false
        // Audio data only; sync and retransmissions arrive on the control socket.
        if (parsed.payloadType != 96) return false
        return enqueueRtpPacket(session, parsed, rxBuf)
    }

    private fun logSenderClockSample(rtpTimestamp: Long, generation: Int) {
        val timing = clockAnchor ?: return
        val sampleRate = clockSampleRate
        if (sampleRate <= 0 || timing.generation != generation) return
        if (timestampedPackets++ % 200L == 0L) {
            val senderTimeUs = timing.anchor.senderTimeUsAt(rtpTimestamp, sampleRate)
            Log.i(TAG, "RAOP clock sample gen=${timing.generation} rtp=$rtpTimestamp senderTimeUs=$senderTimeUs")
        }
    }

    private fun enqueueRtpPacket(
        session: TransportSession,
        packet: RtpAudioPacket,
        buf: ByteArray,
        retransmitted: Boolean = false
    ): Boolean {
        val payload = buf.copyOfRange(packet.payloadOffset, packet.payloadOffset + packet.payloadLength)
        var acceptedAny = false
        val missing = synchronized(decodeLock) {
            if (session.closed.get() || activeTransport !== session) return false
            if (retransmitted && !jitterBuffer.isPendingRetransmit(packet.sequenceNumber)) return false
            val generation = session.generation
            if (!retransmitted) logSenderClockSample(packet.timestamp, generation)
            val result = jitterBuffer.push(
                seq = packet.sequenceNumber,
                payload = payload,
                rtpTimestamp = packet.timestamp,
                ssrc = packet.ssrc
            )
            acceptedAny = result.readyPayloads.isNotEmpty()
            val timing = clockAnchor?.takeIf { it.generation == generation }
            val sampleRate = clockSampleRate
            for (ready in result.readyPayloads) {
                val timestampUs = if (timing != null && sampleRate > 0) {
                    timing.anchor.senderTimeUsAt(ready.rtpTimestamp, sampleRate).coerceAtLeast(0L)
                } else 0L
                decodeAudioPayload(ready.payload, 0, ready.payload.size, generation, timestampUs)
            }
            result.missingRange
        }
        missing?.let { (firstMissing, count) -> sendRetransmitRequest(session, firstMissing, count) }
        return acceptedAny
    }

    private fun decodeAudioPayload(buf: ByteArray, offset: Int, length: Int, generation: Int, senderTimeUs: Long) {
        if (payloadObserver != null) {
            payloadObserver.invoke(buf.copyOfRange(offset, offset + length), generation)
            return
        }
        val key = aesKey
        val iv = aesIv
        rxPackets++
        val selector = alacKeySelector
        if (lastAlacSelector !== selector) {
            unmatchedAlacPackets = 0L
            lastAlacSelector = selector
        }
        if (rxPackets == 1L || rxPackets % 200L == 0L) {
            Log.i(
                TAG,
                "RAOP audio rx: pkts=$rxPackets len=$length gen=$generation " +
                    "encrypted=${key != null && iv != null} alacUnmatched=$unmatchedAlacPackets " +
                    decoder.pcmStats()
            )
        }
        if (selector != null && length >= 16) {
            val previousIndex = selector.selectedIndex
            val plain = selector.decrypt(buf, offset, length)
            if (plain == null) {
                unmatchedAlacPackets++
            } else {
                if (previousIndex == null) {
                    Log.i(TAG, "Selected RAOP ALAC key idx=${selector.selectedIndex} after $unmatchedAlacPackets unmatched packets")
                }
                decoder.decodeAndPublish(plain, 0, plain.size, generation, senderTimeUs)
            }
            return
        }
        if (key != null && iv != null) {
            if (length < 16) {
                if (length >= 2 && unencryptedPrefix0 == -1) {
                    unencryptedPrefix0 = buf[offset].toInt() and 0xFF
                    unencryptedPrefix1 = buf[offset + 1].toInt() and 0xFF
                }
                decoder.decodeAndPublish(buf, offset, length, generation, senderTimeUs)
                return
            }
            var plain = SessionCrypto.decryptRaopAudioCbc(key, iv, buf, offset, length)
            if (!keyConfirmed && plain.size >= 16) {
                val candidates = candidateAesKeys
                var matchedIdx = -1
                for (idx in candidates.indices) {
                    val cand = candidates[idx]
                    val candPlain = if (idx == 0) plain else SessionCrypto.decryptRaopAudioCbc(
                        cand, iv, buf, offset, length
                    )
                    val b0 = candPlain[0].toInt() and 0xFF
                    val b1 = candPlain[1].toInt() and 0xFF
                    val b2 = candPlain[2].toInt() and 0xFF
                    val looksAlac = b0 == 0x20 && b1 == 0x00 && (b2 and 0xE0) == 0x00
                    val looksAacEld = (unencryptedPrefix0 != -1 && b0 == unencryptedPrefix0 && (b1 and 0xF0) == (unencryptedPrefix1 and 0xF0)) ||
                        (b0 == 0x00 && (b1 and 0xF0) == 0x60)
                    if (looksAlac || looksAacEld) {
                        matchedIdx = idx
                        aesKey = cand
                        plain = candPlain
                        break
                    }
                }
                if (matchedIdx >= 0) {
                    Log.i(
                        TAG,
                        "Selected RAOP AES key idx=$matchedIdx (len=${plain.size} hdr=${plain[0].toInt() and 0xFF},${plain[1].toInt() and 0xFF})"
                    )
                    keyConfirmed = true
                }
            }
            decoder.decodeAndPublish(plain, 0, plain.size, generation, senderTimeUs)
        } else {
            decoder.decodeAndPublish(buf, offset, length, generation, senderTimeUs)
        }
    }
}
