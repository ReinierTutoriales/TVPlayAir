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
    val ptsUs: Long,
    val width: Int = 0,
    val height: Int = 0
)

object AirPlayMirrorHeaderParser {
    const val HEADER_BYTES = 128
    const val PAYLOAD_TYPE_VIDEO = 0
    const val PAYLOAD_TYPE_CODEC_CONFIG = 1
    const val PAYLOAD_TYPE_HEARTBEAT = 2
    const val MAX_MIRROR_PAYLOAD_BYTES = 480 * 1024 - MirrorFrameGate.PACKET_HEADER_BYTES
    private const val HVC1 = 0x68766331
    private const val HVCC = 0x68766343
    private const val VISUAL_SAMPLE_ENTRY_BYTES = 86

    internal fun unwrapCodecConfig(data: ByteBuffer): ByteBuffer? {
        if (data.limit() < 8) return data
        val box = data.duplicate().order(ByteOrder.BIG_ENDIAN)
        if (box.getInt(4) != HVC1) return data
        val end = box.getInt(0)
        if (end !in VISUAL_SAMPLE_ENTRY_BYTES..box.limit()) return null
        var offset = VISUAL_SAMPLE_ENTRY_BYTES
        while (end - offset >= 8) {
            val size = box.getInt(offset)
            if (size !in 8..(end - offset)) return null
            if (box.getInt(offset + 4) == HVCC) {
                box.limit(offset + size)
                box.position(offset + 8)
                return box.slice()
            }
            offset += size
        }
        return null
    }

    internal fun isHevcCodecConfig(data: ByteBuffer): Boolean? {
        if (data.limit() < 7 || data.get(0) != 1.toByte()) return null
        if ((data.get(4).toInt() and 0xFC) == 0xFC &&
            (data.get(5).toInt() and 0xE0) == 0xE0
        ) return false
        return true.takeIf { data.limit() >= 23 }
    }

    /**
     * Parses the standard 128-byte little-endian AirPlay Mirroring TCP packet header:
     * - bytes 0..3: uint32 payloadSize
     * - byte 4: payload type (0 = video NALs, 1 = codec config, 2 = heartbeat)
     * - byte 5: subtype, including 0x10 for encrypted IDR frames
     * - bytes 8..15: uint64 NTP timestamp (32.32 fixed-point seconds -> microseconds)
     */
    fun parse(headerBytes: ByteArray): AirPlayMirrorHeader? {
        if (headerBytes.size < HEADER_BYTES) return null
        val buf = ByteBuffer.wrap(headerBytes, 0, HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        val payloadSize = buf.getInt(0)
        val payloadType = buf.get(4).toInt() and 0xFF
        val ntpRaw = buf.getLong(8)

        if (payloadSize < 0 || payloadSize > MAX_MIRROR_PAYLOAD_BYTES) return null
        val ntpSeconds = (ntpRaw ushr 32) and 0xFFFFFFFFL
        val ntpFraction = ntpRaw and 0xFFFFFFFFL
        val microsFraction = (ntpFraction * 1_000_000L) ushr 32
        val ptsUs = ntpSeconds * 1_000_000L + microsFraction

        return AirPlayMirrorHeader(
            payloadSize = payloadSize,
            payloadType = payloadType,
            ptsUs = ptsUs,
            width = if (payloadType == PAYLOAD_TYPE_CODEC_CONFIG) dimension(buf, 40, 16) else 0,
            height = if (payloadType == PAYLOAD_TYPE_CODEC_CONFIG) dimension(buf, 44, 20) else 0
        )
    }

    private fun dimension(header: ByteBuffer, offset: Int, fallbackOffset: Int): Int =
        header.getFloat(offset).takeIf { it in 16f..8192f }?.toInt()
            ?: header.getFloat(fallbackOffset).takeIf { it in 16f..8192f }?.toInt()
            ?: 0
}

/**
 * Receives AirPlay Mirroring TCP video stream packets (default port 7100) inside :session
 * and feeds length-prefixed H.265/H.264 NAL units into the native SPSC video SharedRingBuffer
 * using a single reusable DirectByteBuffer (zero per-frame heap allocations).
 */
class AirPlayMirrorStreamReceiver(
    private val generationProvider: () -> Int,
    private val onCodecConfigReady: (isHevc: Boolean, width: Int, height: Int, generationId: Int) -> Unit
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
        var streamIsHevc: Boolean? = null
        var streamWidth = 0
        var streamHeight = 0
        client.use { sock ->
            sock.soTimeout = CLIENT_TIMEOUT_MS
            sock.tcpNoDelay = true
            Log.i(TAG, "Mirror TCP client connected from ${sock.inetAddress?.hostAddress}")
            val input = BufferedInputStream(sock.getInputStream())
            while (running.get()) {
                if (!readExact(input, headerScratch, AirPlayMirrorHeaderParser.HEADER_BYTES)) {
                    break
                }
                val header = AirPlayMirrorHeaderParser.parse(headerScratch)
                if (header == null) {
                    val hdrBuf = ByteBuffer.wrap(headerScratch, 0, AirPlayMirrorHeaderParser.HEADER_BYTES)
                        .order(ByteOrder.LITTLE_ENDIAN)
                    Log.w(
                        TAG,
                        "Invalid mirror header: size=${hdrBuf.getInt(0)} type=${hdrBuf.getShort(4).toInt() and 0xFFFF}"
                    )
                    break
                }
                if (header.payloadSize == 0) continue
                val isVideo = header.payloadType == AirPlayMirrorHeaderParser.PAYLOAD_TYPE_VIDEO
                val isConfig = header.payloadType == AirPlayMirrorHeaderParser.PAYLOAD_TYPE_CODEC_CONFIG
                if (!readIntoDirectBuffer(input, header.payloadSize, decrypt = isVideo)) {
                    break
                }
                val payload = if (isConfig) AirPlayMirrorHeaderParser.unwrapCodecConfig(directPayload) ?: continue
                    else directPayload
                val isHevc = if (isConfig) AirPlayMirrorHeaderParser.isHevcCodecConfig(payload)
                    else streamIsHevc.takeIf { isVideo }
                if (isHevc == null) continue
                val generationId = generationProvider()
                packetCount++
                if (packetCount == 1L || packetCount % 120L == 0L) {
                    Log.i(TAG, "Mirror TCP rx: packets=$packetCount type=${header.payloadType} size=${header.payloadSize} gen=${generationProvider()}")
                }
                val extraFlags = if (isConfig) {
                    MirrorFrameGate.FLAG_CODEC_CONFIG
                } else {
                    0
                }
                val writeRes = NativeSharedMemory.nativeWriteVideoFrame(
                    directBuffer = payload,
                    size = payload.remaining(),
                    ptsUs = header.ptsUs,
                    isHevc = isHevc,
                    isLengthPrefixed = isVideo,
                    generationId = generationId,
                    extraFlags = extraFlags
                )
                if (writeRes == NativeSharedMemory.VIDEO_WRITE_OK && isConfig) {
                    if (streamIsHevc != isHevc || streamWidth != header.width || streamHeight != header.height) {
                        streamIsHevc = isHevc
                        streamWidth = header.width
                        streamHeight = header.height
                        onCodecConfigReady(isHevc, streamWidth, streamHeight, generationId)
                    }
                } else if (writeRes != NativeSharedMemory.VIDEO_WRITE_OK &&
                    (packetCount <= 3L || packetCount % 60L == 0L)
                ) {
                    Log.w(TAG, "nativeWriteVideoFrame failed: res=$writeRes size=${header.payloadSize} type=${header.payloadType}")
                }
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
