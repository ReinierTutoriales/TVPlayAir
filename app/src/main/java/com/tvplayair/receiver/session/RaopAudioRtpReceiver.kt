package com.tvplayair.receiver.session

import android.util.Log
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean

data class RtpAudioPacket(
    val payloadType: Int,
    val sequenceNumber: Int,
    val timestamp: Long,
    val payloadOffset: Int,
    val payloadLength: Int
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
            payloadLength = payloadLen
        )
    }
}

internal class RaopRtpJitterBuffer(
    private val maxReorderWindow: Int = 32,
    private val maxHoldPackets: Int = 8
) {
    data class PushResult(
        val readyPayloads: List<ByteArray>,
        val missingRange: Pair<Int, Int>?
    )

    private var nextExpectedSeq: Int = -1
    private var lastRequestedSeq: Int = -1
    private val buffered = HashMap<Int, ByteArray>()

    @Synchronized
    fun reset() {
        nextExpectedSeq = -1
        lastRequestedSeq = -1
        buffered.clear()
    }

    @Synchronized
    fun push(seq: Int, payload: ByteArray): PushResult {
        val normSeq = seq and 0xFFFF
        if (nextExpectedSeq == -1) {
            nextExpectedSeq = (normSeq + 1) and 0xFFFF
            return PushResult(listOf(payload), null)
        }

        val delta = (normSeq - nextExpectedSeq) and 0xFFFF
        if (delta == 0) {
            val ready = ArrayList<ByteArray>(buffered.size + 1)
            ready.add(payload)
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
            lastRequestedSeq = -1
            nextExpectedSeq = (normSeq + 1) and 0xFFFF
            return PushResult(listOf(payload), null)
        }

        buffered[normSeq] = payload
        val ready = ArrayList<ByteArray>()
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
            nextExpectedSeq to missingCount.coerceAtMost(maxHoldPackets)
        } else {
            null
        }
        return PushResult(ready, missingRange)
    }

    private fun drainContiguous(out: MutableList<ByteArray>) {
        while (true) {
            val next = buffered.remove(nextExpectedSeq) ?: break
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
    private val generationProvider: () -> Int
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
    }

    private val running = AtomicBoolean(false)
    private val decoder = RaopAudioDecoder()
    private val jitterBuffer = RaopRtpJitterBuffer()
    private val decodeLock = Any()

    @Volatile
    private var socket: DatagramSocket? = null
    @Volatile
    private var controlSocket: DatagramSocket? = null
    @Volatile
    private var clientControlAddress: InetAddress? = null
    @Volatile
    private var clientControlPort: Int = 0
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
    @Volatile
    private var rxPackets: Long = 0L
    @Volatile
    private var unmatchedAlacPackets: Long = 0L
    @Volatile
    private var lastAlacSelector: RaopAlacKeySelector? = null
    private var workerThread: Thread? = null
    private var controlThread: Thread? = null

    internal val localControlPort: Int
        get() = controlSocket?.localPort ?: 0

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
        jitterBuffer.reset()
        clientControlAddress = null
        clientControlPort = 0
        rxPackets = 0L
        unmatchedAlacPackets = 0L
        unencryptedPrefix0 = -1
        unencryptedPrefix1 = -1
        decoder.configure(codec, sampleRate, csd0, samplesPerFrame)
    }

    fun start(peer: InetAddress?, remoteControlPort: Int? = null): Boolean {
        clientControlAddress = peer
        clientControlPort = remoteControlPort?.takeIf { it in 1..65535 } ?: 0
        if (running.get()) return true
        val udp = bindUdp(DEFAULT_AUDIO_PORT) ?: return false
        val ctrlUdp = bindUdp(DEFAULT_CONTROL_PORT) ?: bindUdp(0)
        if (ctrlUdp == null) {
            udp.close()
            return false
        }

        socket = udp
        controlSocket = ctrlUdp
        running.set(true)
        workerThread = Thread({ receiveLoop(udp) }, "TVPlayAir-RaopAudioUdp").apply {
            isDaemon = true
            start()
        }
        controlThread = Thread({ controlLoop(ctrlUdp) }, "TVPlayAir-RaopControlUdp").apply {
            isDaemon = true
            start()
        }
        Log.i(TAG, "RAOP audio UDP receiver listening on port ${udp.localPort} (control=${ctrlUdp.localPort})")
        return true
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
        running.set(false)
        jitterBuffer.reset()
        clientControlAddress = null
        clientControlPort = 0
        runCatching { socket?.close() }
        socket = null
        runCatching { controlSocket?.close() }
        controlSocket = null
        workerThread?.let {
            it.interrupt()
            runCatching { it.join(500L) }
        }
        workerThread = null
        controlThread?.let {
            it.interrupt()
            runCatching { it.join(500L) }
        }
        controlThread = null
        decoder.release()
    }

    private fun controlLoop(ctrlUdp: DatagramSocket) {
        val rxBuf = ByteArray(MAX_UDP_PACKET)
        val packet = DatagramPacket(rxBuf, rxBuf.size)
        while (running.get() && !ctrlUdp.isClosed) {
            packet.length = rxBuf.size
            try {
                ctrlUdp.receive(packet)
            } catch (error: IOException) {
                if (!ctrlUdp.isClosed) Log.w(TAG, "Control socket read failed", error)
                break
            }
            if (ctrlUdp.isClosed || packet.length < 4) continue
            if (clientControlAddress == null && packet.address != null) {
                clientControlAddress = packet.address
            }
            if (clientControlPort <= 0 && packet.port > 0) {
                clientControlPort = packet.port
            }
            val payloadType = rxBuf[1].toInt() and 0x7F
            if (payloadType == 0x56 && packet.length > 4 + RtpAudioPacketParser.MIN_RTP_HEADER_BYTES) {
                val parsed = RtpAudioPacketParser.parse(rxBuf, packet.length - 4, baseOffset = 4) ?: continue
                if (parsed.payloadType == 96) {
                    enqueueRtpPacket(parsed.sequenceNumber, rxBuf, parsed.payloadOffset, parsed.payloadLength)
                }
            }
        }
    }

    private fun sendRetransmitRequest(firstSeq: Int, count: Int) {
        val ctrlUdp = controlSocket ?: return
        val addr = clientControlAddress ?: return
        val port = clientControlPort
        if (ctrlUdp.isClosed || port <= 0) return
        val req = buildRetransmitRequest(firstSeq, count)
        runCatching {
            ctrlUdp.send(DatagramPacket(req, req.size, addr, port))
        }
    }

    private fun receiveLoop(udp: DatagramSocket) {
        val rxBuf = ByteArray(MAX_UDP_PACKET)
        val packet = DatagramPacket(rxBuf, rxBuf.size)
        while (running.get() && !udp.isClosed) {
            packet.length = rxBuf.size
            try {
                udp.receive(packet)
            } catch (error: IOException) {
                if (!udp.isClosed) Log.w(TAG, "Audio socket read failed", error)
                break
            }
            if (udp.isClosed) break
            if (clientControlAddress == null && packet.address != null) {
                clientControlAddress = packet.address
            }
            val parsed = RtpAudioPacketParser.parse(rxBuf, packet.length) ?: continue
            // Only process RTP payloadType 96 (audio data); ignore RTCP/Sync (84) and retransmit headers (86).
            if (parsed.payloadType != 96) continue
            enqueueRtpPacket(parsed.sequenceNumber, rxBuf, parsed.payloadOffset, parsed.payloadLength)
        }
    }

    private fun enqueueRtpPacket(seq: Int, buf: ByteArray, offset: Int, length: Int) {
        val payloadCopy = buf.copyOfRange(offset, offset + length)
        val result = jitterBuffer.push(seq, payloadCopy)
        result.missingRange?.let { (firstMissing, count) ->
            sendRetransmitRequest(firstMissing, count)
        }
        if (result.readyPayloads.isEmpty()) return
        synchronized(decodeLock) {
            for (payload in result.readyPayloads) {
                decodeAudioPayload(payload, 0, payload.size)
            }
        }
    }

    private fun decodeAudioPayload(buf: ByteArray, offset: Int, length: Int) {
        val key = aesKey
        val iv = aesIv
        val generation = generationProvider()
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
                decoder.decodeAndPublish(plain, 0, plain.size, generation)
            }
            return
        }
        if (key != null && iv != null) {
            if (length < 16) {
                if (length >= 2 && unencryptedPrefix0 == -1) {
                    unencryptedPrefix0 = buf[offset].toInt() and 0xFF
                    unencryptedPrefix1 = buf[offset + 1].toInt() and 0xFF
                }
                decoder.decodeAndPublish(buf, offset, length, generation)
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
            decoder.decodeAndPublish(plain, 0, plain.size, generation)
        } else {
            decoder.decodeAndPublish(buf, offset, length, generation)
        }
    }
}
