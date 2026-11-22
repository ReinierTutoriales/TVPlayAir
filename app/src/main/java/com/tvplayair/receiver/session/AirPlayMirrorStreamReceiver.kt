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
            width = if (payloadType == PAYLOAD_TYPE_CODEC_CONFIG) dimension(buf, 16, 40) else 0,
            height = if (payloadType == PAYLOAD_TYPE_CODEC_CONFIG) dimension(buf, 20, 44) else 0
        )
    }

    private fun dimension(header: ByteBuffer, offset: Int, fallbackOffset: Int): Int {
        val first = header.getFloat(offset).takeIf { it in 16f..8192f }?.toInt() ?: 0
        val second = header.getFloat(fallbackOffset).takeIf { it in 16f..8192f }?.toInt() ?: 0
        return maxOf(first, second)
    }
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
    @Volatile
    private var candidateCiphers: List<Pair<String, Cipher>> = emptyList()
    private var acceptThread: Thread? = null

    fun configureStreamCipher(key16: ByteArray, iv16: ByteArray) {
        if (key16.size != 16 || iv16.size != 16) return
        streamCipher = createCtrCipher(key16, iv16)
        candidateCiphers = emptyList()
    }

    fun configureStreamCipherCandidates(
        fpKey16: ByteArray,
        ecdhSecret32: ByteArray?,
        connId: Long
    ) {
        if (fpKey16.size != 16) return
        val rawKey = AirPlayPairingSession.deriveMirrorStreamKey(fpKey16, connId)
        val rawIv = AirPlayPairingSession.deriveMirrorStreamIv(fpKey16, connId)
        if (ecdhSecret32 == null || ecdhSecret32.size < 16) {
            configureStreamCipher(rawKey, rawIv)
            return
        }
        val eaFpThenEcdh = AirPlayPairingSession.sha512Trunc16(fpKey16, ecdhSecret32)
        val eaEcdhThenFp = AirPlayPairingSession.sha512Trunc16(ecdhSecret32, fpKey16)
        val streamThenEcdhKey = AirPlayPairingSession.sha512Trunc16(rawKey, ecdhSecret32)
        val streamThenEcdhIv = AirPlayPairingSession.sha512Trunc16(rawIv, ecdhSecret32)

        val candidates = listOf(
            "fp_then_ecdh" to createCtrCipher(
                AirPlayPairingSession.deriveMirrorStreamKey(eaFpThenEcdh, connId),
                AirPlayPairingSession.deriveMirrorStreamIv(eaFpThenEcdh, connId)
            ),
            "stream_then_ecdh" to createCtrCipher(streamThenEcdhKey, rawIv),
            "stream_and_iv_then_ecdh" to createCtrCipher(streamThenEcdhKey, streamThenEcdhIv),
            "raw_fp" to createCtrCipher(rawKey, rawIv),
            "ecdh_then_fp" to createCtrCipher(
                AirPlayPairingSession.deriveMirrorStreamKey(eaEcdhThenFp, connId),
                AirPlayPairingSession.deriveMirrorStreamIv(eaEcdhThenFp, connId)
            )
        )
        streamCipher = candidates.first().second
        candidateCiphers = candidates
    }

    private fun createCtrCipher(key16: ByteArray, iv16: ByteArray): Cipher =
        Cipher.getInstance("AES/CTR/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key16, "AES"), IvParameterSpec(iv16))
        }

    fun clearStreamCipher() {
        streamCipher = null
        candidateCiphers = emptyList()
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
        val candidates = if (decrypt) candidateCiphers else emptyList()
        if (candidates.isNotEmpty()) {
            val encryptedPacket = ByteArray(length)
            if (!readExact(input, encryptedPacket, length)) return false
            var selectedName: String? = null
            var selectedCipher: Cipher? = null
            var selectedPlain: ByteArray? = null
            for ((name, cipher) in candidates) {
                val plain = cipher.update(encryptedPacket, 0, length) ?: continue
                if (isValidLengthPrefixedNals(plain, plain.size)) {
                    selectedName = name
                    selectedCipher = cipher
                    selectedPlain = plain
                    break
                }
            }
            val chosenPlain = selectedPlain ?: candidates.first().second.let {
                selectedName = "${candidates.first().first}_fallback"
                selectedCipher = it
                // Re-init not needed if we keep first candidate's output, but we already called update on it!
                encryptedPacket
            }
            streamCipher = selectedCipher
            candidateCiphers = emptyList()
            if (selectedPlain != null) {
                Log.i(TAG, "Selected mirror AES-CTR candidate: $selectedName (len=$length)")
                directPayload.put(chosenPlain)
            } else {
                Log.w(TAG, "No mirror AES-CTR candidate matched NAL framing (len=$length)")
                return false
            }
            directPayload.flip()
            return true
        }

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

    private fun isValidLengthPrefixedNals(data: ByteArray, size: Int): Boolean {
        if (size < 5) return false
        var pos = 0
        while (pos + 4 <= size) {
            val nalLen = ((data[pos].toInt() and 0xFF) shl 24) or
                ((data[pos + 1].toInt() and 0xFF) shl 16) or
                ((data[pos + 2].toInt() and 0xFF) shl 8) or
                (data[pos + 3].toInt() and 0xFF)
            pos += 4
            if (nalLen <= 0 || nalLen > size - pos) return false
            if ((data[pos].toInt() and 0x80) != 0) return false // forbidden_zero_bit must be 0
            pos += nalLen
        }
        return pos == size
    }
}
