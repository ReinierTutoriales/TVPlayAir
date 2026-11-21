package com.tvplayair.receiver.session

import android.util.Log
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
    private val rxBuf = ByteArray(MAX_UDP_PACKET)

    @Volatile
    private var socket: DatagramSocket? = null
    @Volatile
    private var aesKey: ByteArray? = null
    @Volatile
    private var aesIv: ByteArray? = null
    private var workerThread: Thread? = null

    fun configureSession(
        codec: RaopAudioCodec,
        sampleRate: Int,
        csd0: ByteArray? = null,
        sessionAesKey: ByteArray? = null,
        sessionAesIv: ByteArray? = null
    ) {
        aesKey = sessionAesKey?.takeIf { it.size == 16 }?.copyOf()
        aesIv = sessionAesIv?.takeIf { it.size == 16 }?.copyOf()
        decoder.configure(codec, sampleRate, csd0)
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
        val packet = DatagramPacket(rxBuf, rxBuf.size)
        var rxPackets = 0L
        while (running.get()) {
            val ok = runCatching { udp.receive(packet); true }.getOrDefault(false)
            if (!ok) continue
            val parsed = RtpAudioPacketParser.parse(rxBuf, packet.length) ?: continue
            val key = aesKey
            val iv = aesIv
            val generation = generationProvider()
            rxPackets++
            if (rxPackets == 1L || rxPackets % 200L == 0L) {
                Log.i(TAG, "RAOP audio rx: pkts=$rxPackets len=${parsed.payloadLength} gen=$generation encrypted=${key != null}")
            }
            if (key != null && iv != null) {
                val plain = SessionCrypto.decryptRaopAudioCbc(
                    key, iv, rxBuf, parsed.payloadOffset, parsed.payloadLength
                )
                decoder.decodeAndPublish(plain, 0, plain.size, generation)
            } else {
                decoder.decodeAndPublish(rxBuf, parsed.payloadOffset, parsed.payloadLength, generation)
            }
        }
    }
}
