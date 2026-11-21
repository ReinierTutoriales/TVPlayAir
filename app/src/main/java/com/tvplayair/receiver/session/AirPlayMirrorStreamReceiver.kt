package com.tvplayair.receiver.session

import android.util.Log
import com.tvplayair.receiver.video.MirrorFrameGate
import java.io.BufferedInputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

data class AirPlayMirrorHeader(
    val payloadSize: Int,
    val payloadType: Int,
    val ptsUs: Long
)

object AirPlayMirrorHeaderParser {
    const val HEADER_BYTES = 128
    const val PAYLOAD_TYPE_VIDEO = 0
    const val PAYLOAD_TYPE_CODEC_CONFIG = 1
    const val PAYLOAD_TYPE_HEARTBEAT = 2
    const val MAX_MIRROR_PAYLOAD_BYTES = 64 * 1024 - MirrorFrameGate.PACKET_HEADER_BYTES

    /**
     * Parses the standard 128-byte little-endian AirPlay Mirroring TCP packet header:
     * - bytes 0..3: uint32 payloadSize
     * - bytes 4..5: uint16 payloadType (0 = video NALs, 1 = codec config, 2 = heartbeat)
     * - bytes 8..15: uint64 NTP timestamp (32.32 fixed-point seconds -> microseconds)
     */
    fun parse(headerBytes: ByteArray): AirPlayMirrorHeader? {
        if (headerBytes.size < HEADER_BYTES) return null
        val buf = ByteBuffer.wrap(headerBytes, 0, HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        val payloadSize = buf.getInt(0)
        val payloadType = buf.getShort(4).toInt() and 0xFFFF
        val ntpRaw = buf.getLong(8)

        if (payloadSize < 0 || payloadSize > MAX_MIRROR_PAYLOAD_BYTES) return null
        val ntpSeconds = (ntpRaw ushr 32) and 0xFFFFFFFFL
        val ntpFraction = ntpRaw and 0xFFFFFFFFL
        val microsFraction = (ntpFraction * 1_000_000L) ushr 32
        val ptsUs = ntpSeconds * 1_000_000L + microsFraction

        return AirPlayMirrorHeader(
            payloadSize = payloadSize,
            payloadType = payloadType,
            ptsUs = ptsUs
        )
    }
}

/**
 * Receives AirPlay Mirroring TCP video stream packets (default port 7100) inside :session
 * and feeds length-prefixed H.265/H.264 NAL units into the native SPSC video SharedRingBuffer
 * using a single reusable DirectByteBuffer (zero per-frame heap allocations).
 */
class AirPlayMirrorStreamReceiver(
    private val generationProvider: () -> Int,
    private val isHevcProvider: () -> Boolean
) {
    companion object {
        private const val TAG = "AirPlayMirrorReceiver"
        const val DEFAULT_MIRROR_PORT = 7100
        private const val CLIENT_TIMEOUT_MS = 10_000
    }

    private val running = AtomicBoolean(false)
    private val directPayload: ByteBuffer = ByteBuffer.allocateDirect(
        AirPlayMirrorHeaderParser.MAX_MIRROR_PAYLOAD_BYTES
    )
    private val headerScratch = ByteArray(AirPlayMirrorHeaderParser.HEADER_BYTES)
    private val readChunk = ByteArray(8192)

    @Volatile
    private var serverSocket: ServerSocket? = null
    @Volatile
    private var streamCipher: Cipher? = null
    private var acceptThread: Thread? = null

    fun configureStreamCipher(key16: ByteArray, iv16: ByteArray) {
        if (key16.size != 16 || iv16.size != 16) return
        streamCipher = Cipher.getInstance("AES/CTR/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key16, "AES"), IvParameterSpec(iv16))
        }
    }

    fun clearStreamCipher() {
        streamCipher = null
    }

    fun start(port: Int = DEFAULT_MIRROR_PORT): Boolean {
        if (running.get()) return true
        val socket = runCatching {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(port))
            }
        }.onFailure {
            Log.e(TAG, "Failed to bind AirPlay mirror stream port $port", it)
        }.getOrNull() ?: return false

        serverSocket = socket
        running.set(true)
        acceptThread = Thread({ acceptLoop(socket) }, "TVPlayAir-MirrorTcp").apply {
            isDaemon = true
            start()
        }
        Log.i(TAG, "AirPlay mirror TCP receiver listening on port $port")
        return true
    }

    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptThread?.let {
            it.interrupt()
            runCatching { it.join(500L) }
        }
        acceptThread = null
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running.get()) {
            val client = runCatching { socket.accept() }.getOrNull() ?: continue
            handleMirrorClient(client)
        }
    }

    private fun handleMirrorClient(client: Socket) {
        var packetCount = 0L
        client.use { sock ->
            sock.soTimeout = CLIENT_TIMEOUT_MS
            sock.tcpNoDelay = true
            Log.i(TAG, "Mirror TCP client connected from ${sock.inetAddress?.hostAddress}")
            val input = BufferedInputStream(sock.getInputStream())
            while (running.get()) {
                if (!readExact(input, headerScratch, AirPlayMirrorHeaderParser.HEADER_BYTES)) {
                    break
                }
                val header = AirPlayMirrorHeaderParser.parse(headerScratch) ?: break
                if (header.payloadSize == 0 ||
                    header.payloadType == AirPlayMirrorHeaderParser.PAYLOAD_TYPE_HEARTBEAT
                ) {
                    continue
                }
                val isVideo = header.payloadType == AirPlayMirrorHeaderParser.PAYLOAD_TYPE_VIDEO
                if (!readIntoDirectBuffer(input, header.payloadSize, decrypt = isVideo)) {
                    break
                }
                packetCount++
                if (packetCount == 1L || packetCount % 120L == 0L) {
                    Log.i(TAG, "Mirror TCP rx: packets=$packetCount type=${header.payloadType} size=${header.payloadSize} gen=${generationProvider()}")
                }
                val extraFlags = if (header.payloadType == AirPlayMirrorHeaderParser.PAYLOAD_TYPE_CODEC_CONFIG) {
                    MirrorFrameGate.FLAG_CODEC_CONFIG
                } else {
                    0
                }
                NativeSharedMemory.nativeWriteVideoFrame(
                    directBuffer = directPayload,
                    size = header.payloadSize,
                    ptsUs = header.ptsUs,
                    isHevc = isHevcProvider(),
                    isLengthPrefixed = isVideo,
                    generationId = generationProvider(),
                    extraFlags = extraFlags
                )
            }
        }
        Log.i(TAG, "Mirror TCP client closed (packets=$packetCount)")
    }

    private fun readExact(input: BufferedInputStream, dst: ByteArray, length: Int): Boolean {
        var offset = 0
        while (offset < length) {
            val n = runCatching { input.read(dst, offset, length - offset) }.getOrDefault(-1)
            if (n <= 0) return false
            offset += n
        }
        return true
    }

    private fun readIntoDirectBuffer(
        input: BufferedInputStream,
        length: Int,
        decrypt: Boolean
    ): Boolean {
        directPayload.clear()
        val cipher = if (decrypt) streamCipher else null
        var remaining = length
        while (remaining > 0) {
            val toRead = remaining.coerceAtMost(readChunk.size)
            val n = runCatching { input.read(readChunk, 0, toRead) }.getOrDefault(-1)
            if (n <= 0) return false
            if (cipher != null) {
                val plain = cipher.update(readChunk, 0, n)
                if (plain != null && plain.isNotEmpty()) {
                    directPayload.put(plain)
                }
            } else {
                directPayload.put(readChunk, 0, n)
            }
            remaining -= n
        }
        directPayload.flip()
        return true
    }
}
