package com.tvplayair.receiver.session

import android.util.Log
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
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

    fun parse(packet: ByteArray, length: Int): RtpAudioPacket? {
        if (length < MIN_RTP_HEADER_BYTES || packet.size < length) return null
        val b0 = packet[0].toInt() and 0xFF
        val version = (b0 ushr 6) and 0x03
        if (version != 2) return null

        val hasPadding = (b0 and 0x20) != 0
        val hasExtension = (b0 and 0x10) != 0
        val csrcCount = b0 and 0x0F

        val b1 = packet[1].toInt() and 0xFF
        val payloadType = b1 and 0x7F
        val seq = ((packet[2].toInt() and 0xFF) shl 8) or (packet[3].toInt() and 0xFF)
        val ts = ((packet[4].toLong() and 0xFF) shl 24) or
            ((packet[5].toLong() and 0xFF) shl 16) or
            ((packet[6].toLong() and 0xFF) shl 8) or
            (packet[7].toLong() and 0xFF)

        var headerLen = MIN_RTP_HEADER_BYTES + csrcCount * 4
        if (headerLen > length) return null

        if (hasExtension) {
            if (headerLen + 4 > length) return null
            val extWords = ((packet[headerLen + 2].toInt() and 0xFF) shl 8) or
                (packet[headerLen + 3].toInt() and 0xFF)
            headerLen += 4 + extWords * 4
            if (headerLen > length) return null
        }

        var payloadEnd = length
        if (hasPadding) {
            val padBytes = packet[length - 1].toInt() and 0xFF
            if (padBytes <= 0 || headerLen + padBytes > length) return null
            payloadEnd -= padBytes
        }

        val payloadLen = payloadEnd - headerLen
        if (payloadLen <= 0) return null

        return RtpAudioPacket(
            payloadType = payloadType,
            sequenceNumber = seq,
            timestamp = ts,
            payloadOffset = headerLen,
            payloadLength = payloadLen
        )
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
    }

    private val running = AtomicBoolean(false)
    private val decoder = RaopAudioDecoder()

    @Volatile
    private var socket: DatagramSocket? = null
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
    private var lastSeqNumber: Int = -1
    @Volatile
    private var unencryptedPrefix0: Int = -1
    @Volatile
    private var unencryptedPrefix1: Int = -1
    private var workerThread: Thread? = null

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
        lastSeqNumber = -1
        unencryptedPrefix0 = -1
        unencryptedPrefix1 = -1
        decoder.configure(codec, sampleRate, csd0, samplesPerFrame)
    }

    fun start(port: Int = DEFAULT_AUDIO_PORT): Boolean {
        if (running.get()) return true
        val udp = runCatching {
            DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(port))
            }
        }.onFailure {
            Log.e(TAG, "Failed to bind RAOP UDP audio port $port", it)
        }.getOrNull() ?: return false

        socket = udp
        running.set(true)
        workerThread = Thread({ receiveLoop(udp) }, "TVPlayAir-RaopAudioUdp").apply {
            isDaemon = true
            start()
        }
        Log.i(TAG, "RAOP audio UDP receiver listening on port $port")
        return true
    }

    fun stop() {
        running.set(false)
        runCatching { socket?.close() }
        socket = null
        workerThread?.let {
            it.interrupt()
            runCatching { it.join(500L) }
        }
        workerThread = null
        decoder.release()
    }

    private fun receiveLoop(udp: DatagramSocket) {
        val rxBuf = ByteArray(MAX_UDP_PACKET)
        val packet = DatagramPacket(rxBuf, rxBuf.size)
        var rxPackets = 0L
        var unmatchedAlacPackets = 0L
        var lastAlacSelector: RaopAlacKeySelector? = null
        while (running.get() && !udp.isClosed) {
            packet.length = rxBuf.size
            try {
                udp.receive(packet)
            } catch (error: IOException) {
                if (!udp.isClosed) Log.w(TAG, "Audio socket read failed", error)
                break
            }
            if (udp.isClosed) break
            val parsed = RtpAudioPacketParser.parse(rxBuf, packet.length) ?: continue
            // Only process RTP payloadType 96 (audio data); ignore RTCP/Sync (84) and retransmit headers (86).
            if (parsed.payloadType != 96) continue
            val prevSeq = lastSeqNumber
            if (prevSeq != -1) {
                val delta = (parsed.sequenceNumber - prevSeq + 65536) % 65536
                if (delta == 0 || delta > 32768) continue
            }
            lastSeqNumber = parsed.sequenceNumber

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
                    "RAOP audio rx: pkts=$rxPackets len=${parsed.payloadLength} gen=$generation " +
                        "encrypted=${key != null && iv != null} alacUnmatched=$unmatchedAlacPackets " +
                        decoder.pcmStats()
                )
            }
            if (selector != null && parsed.payloadLength >= 16) {
                val previousIndex = selector.selectedIndex
                val plain = selector.decrypt(rxBuf, parsed.payloadOffset, parsed.payloadLength)
                if (plain == null) {
                    unmatchedAlacPackets++
                } else {
                    if (previousIndex == null) {
                        Log.i(TAG, "Selected RAOP ALAC key idx=${selector.selectedIndex} after $unmatchedAlacPackets unmatched packets")
                    }
                    decoder.decodeAndPublish(plain, 0, plain.size, generation)
                }
                continue
            }
            if (key != null && iv != null) {
                if (parsed.payloadLength < 16) {
                    if (parsed.payloadLength >= 2 && unencryptedPrefix0 == -1) {
                        unencryptedPrefix0 = rxBuf[parsed.payloadOffset].toInt() and 0xFF
                        unencryptedPrefix1 = rxBuf[parsed.payloadOffset + 1].toInt() and 0xFF
                    }
                    decoder.decodeAndPublish(rxBuf, parsed.payloadOffset, parsed.payloadLength, generation)
                    continue
                }
                var plain = SessionCrypto.decryptRaopAudioCbc(
                    key, iv, rxBuf, parsed.payloadOffset, parsed.payloadLength
                )
                if (!keyConfirmed && plain.size >= 16) {
                    val candidates = candidateAesKeys
                    var matchedIdx = -1
                    for (idx in candidates.indices) {
                        val cand = candidates[idx]
                        val candPlain = if (idx == 0) plain else SessionCrypto.decryptRaopAudioCbc(
                            cand, iv, rxBuf, parsed.payloadOffset, parsed.payloadLength
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
                    Log.i(
                        TAG,
                        "Selected RAOP AES key idx=$matchedIdx (len=${plain.size} hdr=${plain[0].toInt() and 0xFF},${plain[1].toInt() and 0xFF})"
                    )
                    keyConfirmed = true
                }
                decoder.decodeAndPublish(plain, 0, plain.size, generation)
            } else {
                decoder.decodeAndPublish(rxBuf, parsed.payloadOffset, parsed.payloadLength, generation)
            }
        }
    }
}
