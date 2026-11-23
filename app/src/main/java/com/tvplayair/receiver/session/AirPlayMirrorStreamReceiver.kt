package com.tvplayair.receiver.session

import android.os.SystemClock
import android.util.Log
import com.tvplayair.receiver.diagnostics.PLAYBACK_DIAGNOSTICS_TAG
import com.tvplayair.receiver.video.MirrorFrameGate
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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
 * using a reusable DirectByteBuffer.
 */
class AirPlayMirrorStreamReceiver(
    private val onCodecConfigReady: (
        socket: Socket, isHevc: Boolean, width: Int, height: Int, generationId: Int, codecConfig: ByteArray, opensProjection: Boolean
    ) -> Unit,
    private val onStreamEnded: () -> Unit
) {
    companion object {
        private const val TAG = "AirPlayMirrorReceiver"
        const val DEFAULT_MIRROR_PORT = 7100
        private const val CLIENT_TIMEOUT_MS = 10_000
        private const val MIRROR_SOCKET_TIMEOUT_MS = 60_000

        internal fun createCtrCipher(key16: ByteArray, iv16: ByteArray): Cipher =
            Cipher.getInstance("AES/CTR/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key16, "AES"), IvParameterSpec(iv16))
            }

        internal fun selectCtrCandidate(
            encryptedPacket: ByteArray,
            length: Int,
            candidates: List<CipherCandidate>
        ): Triple<String, Cipher, ByteArray>? {
            for (candidate in candidates) {
                val probeCipher = createCtrCipher(candidate.key16, candidate.iv16)
                val plain = probeCipher.update(encryptedPacket, 0, length) ?: continue
                if (isValidLengthPrefixedNals(plain, plain.size)) {
                    return Triple(candidate.name, probeCipher, plain)
                }
            }
            return null
        }

        internal fun isValidLengthPrefixedNals(data: ByteArray, size: Int): Boolean {
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

    private val running = AtomicBoolean(false)
    private val videoGeneration = AtomicInteger()
    private val streamLock = Any()
    private var activeClient: Socket? = null
    private var activeCloseReason: String? = null
    private var activeTrace: ConnectionTrace? = null

    private class ConnectionTrace {
        val startedMs = SystemClock.elapsedRealtime()
        var generation = 0
        var packets = 0L
        @Volatile var lastVideoAtMs = -1L
        var reason = "loop_ended"
    }
    private var activeSetup: CipherSetup? = null
    private var pendingSetup: CipherSetup? = null

    private data class CipherSetup(val owner: AirPlayConnectionCrypto, val connectionId: Long, val state: CipherState)

    internal data class CipherCandidate(val name: String, val key16: ByteArray, val iv16: ByteArray)

    private class CipherState(var selected: Cipher?, var candidates: List<CipherCandidate>)
    private val directPayload: ByteBuffer = ByteBuffer.allocateDirect(
        AirPlayMirrorHeaderParser.MAX_MIRROR_PAYLOAD_BYTES
    )
    private val readChunk = ByteArray(8192)

    @Volatile
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    internal fun configureStreamCipherCandidates(
        owner: AirPlayConnectionCrypto,
        fpKey16: ByteArray,
        ecdhSecret32: ByteArray?,
        connId: Long
    ) {
        if (fpKey16.size != 16) return
        val rawKey = AirPlayPairingSession.deriveMirrorStreamKey(fpKey16, connId)
        val rawIv = AirPlayPairingSession.deriveMirrorStreamIv(fpKey16, connId)
        if (ecdhSecret32 == null || ecdhSecret32.size < 16) {
            installSetup(CipherSetup(owner, connId, CipherState(createCtrCipher(rawKey, rawIv), emptyList())))
            return
        }
        val eaFpThenEcdh = AirPlayPairingSession.sha512Trunc16(fpKey16, ecdhSecret32)
        val eaEcdhThenFp = AirPlayPairingSession.sha512Trunc16(ecdhSecret32, fpKey16)
        val streamThenEcdhKey = AirPlayPairingSession.sha512Trunc16(rawKey, ecdhSecret32)
        val streamThenEcdhIv = AirPlayPairingSession.sha512Trunc16(rawIv, ecdhSecret32)

        val candidates = listOf(
            CipherCandidate(
                "fp_then_ecdh",
                AirPlayPairingSession.deriveMirrorStreamKey(eaFpThenEcdh, connId),
                AirPlayPairingSession.deriveMirrorStreamIv(eaFpThenEcdh, connId)
            ),
            CipherCandidate("stream_then_ecdh", streamThenEcdhKey, rawIv),
            CipherCandidate("stream_and_iv_then_ecdh", streamThenEcdhKey, streamThenEcdhIv),
            CipherCandidate("raw_fp", rawKey, rawIv),
            CipherCandidate(
                "ecdh_then_fp",
                AirPlayPairingSession.deriveMirrorStreamKey(eaEcdhThenFp, connId),
                AirPlayPairingSession.deriveMirrorStreamIv(eaEcdhThenFp, connId)
            )
        )
        installSetup(CipherSetup(owner, connId, CipherState(null, candidates)))
    }

    private fun installSetup(setup: CipherSetup) = synchronized(streamLock) {
        val existing = pendingSetup ?: activeSetup
        if (existing != null && existing.owner === setup.owner && existing.connectionId == setup.connectionId) {
            return@synchronized
        }
        pendingSetup = setup
        closeActiveStream("new_setup")
    }

    internal fun allowsAudio(source: AirPlayConnectionCrypto): Boolean = synchronized(streamLock) {
        val owner = pendingSetup?.owner ?: activeSetup?.owner?.takeIf { activeClient?.isClosed == false }
        canUseRaopDuringMirroring(owner, source)
    }

    internal fun hasRecentVideo(owner: AirPlayConnectionCrypto): Boolean = synchronized(streamLock) {
        val lastVideoAtMs = activeTrace?.lastVideoAtMs ?: -1L
        activeSetup?.owner === owner && activeClient?.isClosed == false && lastVideoAtMs >= 0L &&
            SystemClock.elapsedRealtime() - lastVideoAtMs < CLIENT_TIMEOUT_MS
    }

    internal fun closeStreamForOwner(owner: AirPlayConnectionCrypto, reason: String) {
        synchronized(streamLock) {
            if (pendingSetup?.owner === owner) pendingSetup = null
            if (activeSetup?.owner === owner) {
                Log.i(TAG, "Closing mirror TCP stream after its RTSP control ended")
                closeActiveStream(reason)
            }
        }
    }

    @Synchronized
    fun start(port: Int = DEFAULT_MIRROR_PORT): Boolean {
        if (running.get()) return true
        // A retired accept thread still owns the reusable buffers and the SPSC producer.
        if (acceptThread?.isAlive == true) return false
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

    @Synchronized
    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        closeActiveStream("receiver_stopped")
        serverSocket = null
        acceptThread?.let {
            it.interrupt()
            runCatching { it.join(500L) }
        }
        if (acceptThread?.isAlive != true) acceptThread = null
        synchronized(streamLock) { pendingSetup = null }
    }

    internal fun closeActiveStream(reason: String) {
        synchronized(streamLock) {
            activeClient?.let { socket ->
                if (!socket.isClosed) {
                    activeCloseReason = reason
                    runCatching { socket.close() }
                }
            }
        }
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = runCatching { socket.accept() }.getOrNull() ?: break
            val trace = ConnectionTrace()
            val setup = synchronized(streamLock) {
                if (socket.isClosed) {
                    runCatching { client.close() }
                    return
                }
                activeClient = client
                activeCloseReason = null
                activeTrace = trace
                pendingSetup.also {
                    activeSetup = it
                    pendingSetup = null
                }
            }
            try {
                handleMirrorClient(client, setup?.state ?: CipherState(null, emptyList()), trace)
            } catch (_: SocketTimeoutException) {
                trace.reason = "read_timeout"
            } catch (error: IOException) {
                trace.reason = error.javaClass.simpleName
            } finally {
                val reason = synchronized(streamLock) {
                    if (activeClient !== client) return@synchronized trace.reason
                    val reason = activeCloseReason ?: trace.reason
                    activeClient = null
                    activeSetup = null
                    activeCloseReason = null
                    activeTrace = null
                    reason
                }
                Log.i(PLAYBACK_DIAGNOSTICS_TAG,
                    "route=mirror_transport generation=${trace.generation} event=closed reason=$reason " +
                        "packets=${trace.packets} elapsedMs=${SystemClock.elapsedRealtime() - trace.startedMs}")
                if (trace.generation != 0 && !socket.isClosed) onStreamEnded()
            }
        }
    }

    private fun handleMirrorClient(client: Socket, cipherState: CipherState, trace: ConnectionTrace) {
        val headerScratch = ByteArray(AirPlayMirrorHeaderParser.HEADER_BYTES)
        var streamIsHevc: Boolean? = null
        var streamWidth = 0
        var streamHeight = 0
        var streamConfig: ByteArray? = null
        client.use { sock ->
            sock.soTimeout = MIRROR_SOCKET_TIMEOUT_MS
            sock.tcpNoDelay = true
            Log.i(TAG, "Mirror TCP client connected from ${sock.inetAddress?.hostAddress}")
            val input = BufferedInputStream(sock.getInputStream())
            while (running.get() && !sock.isClosed) {
                if (!readExact(input, headerScratch, AirPlayMirrorHeaderParser.HEADER_BYTES)) {
                    trace.reason = "header_eof"
                    break
                }
                val header = AirPlayMirrorHeaderParser.parse(headerScratch)
                if (header == null) {
                    trace.reason = "invalid_header"
                    val hdrBuf = ByteBuffer.wrap(headerScratch, 0, AirPlayMirrorHeaderParser.HEADER_BYTES)
                        .order(ByteOrder.LITTLE_ENDIAN)
                    Log.w(
                        TAG,
                        "Invalid mirror header: size=${hdrBuf.getInt(0)} type=${hdrBuf.getShort(4).toInt() and 0xFFFF}"
                    )
                    break
                }
                if (header.payloadType == AirPlayMirrorHeaderParser.PAYLOAD_TYPE_HEARTBEAT) {
                    trace.lastVideoAtMs = SystemClock.elapsedRealtime()
                }
                if (header.payloadSize == 0) continue
                val isVideo = header.payloadType == AirPlayMirrorHeaderParser.PAYLOAD_TYPE_VIDEO
                val isConfig = header.payloadType == AirPlayMirrorHeaderParser.PAYLOAD_TYPE_CODEC_CONFIG
                if (!readIntoDirectBuffer(input, header.payloadSize, cipherState.takeIf { isVideo })) {
                    trace.reason = "payload_eof"
                    break
                }
                if (sock.isClosed) break
                if (isConfig) {
                    val payload = AirPlayMirrorHeaderParser.unwrapCodecConfig(directPayload) ?: continue
                    val isHevc = AirPlayMirrorHeaderParser.isHevcCodecConfig(payload) ?: continue
                    val codecConfig = NativeSharedMemory.nativeConvertMirrorCodecConfig(
                        payload, payload.remaining(), isHevc
                    ) ?: continue
                    if (streamIsHevc == isHevc && streamWidth == header.width &&
                        streamHeight == header.height && streamConfig.contentEquals(codecConfig)
                    ) continue

                    val opensProjection = streamConfig == null
                    streamIsHevc = isHevc
                    streamWidth = header.width
                    streamHeight = header.height
                    streamConfig = codecConfig
                    trace.generation = videoGeneration.incrementAndGet()
                    // Publish codec setup even if the frame ring is full or the mirror is suspended.
                    Log.i(PLAYBACK_DIAGNOSTICS_TAG,
                        "route=mirror_transport generation=${trace.generation} event=config " +
                            "width=$streamWidth height=$streamHeight hevc=$isHevc")
                    onCodecConfigReady(sock, isHevc, streamWidth, streamHeight, trace.generation, codecConfig, opensProjection)
                    continue
                }
                val isHevc = streamIsHevc ?: continue
                if (!isVideo) continue
                trace.lastVideoAtMs = SystemClock.elapsedRealtime()
                val packetCount = ++trace.packets
                if (packetCount == 1L || packetCount % 120L == 0L) {
                    Log.i(TAG, "Mirror TCP rx: packets=$packetCount size=${header.payloadSize} gen=${trace.generation}")
                }
                val writeRes = NativeSharedMemory.nativeWriteVideoFrame(
                    directBuffer = directPayload,
                    size = directPayload.remaining(),
                    ptsUs = header.ptsUs,
                    isHevc = isHevc,
                    isLengthPrefixed = true,
                    generationId = trace.generation,
                    extraFlags = 0
                )
                if (writeRes != NativeSharedMemory.VIDEO_WRITE_OK &&
                    (packetCount <= 3L || packetCount % 60L == 0L)
                ) {
                    Log.w(TAG, "nativeWriteVideoFrame failed: res=$writeRes size=${header.payloadSize}")
                }
            }
        }
    }

    private fun readExact(input: BufferedInputStream, dst: ByteArray, length: Int): Boolean {
        var offset = 0
        while (offset < length) {
            val n = input.read(dst, offset, length - offset)
            if (n < 0) {
                if (offset != 0) throw EOFException("Incomplete mirror packet")
                return false
            }
            offset += n
        }
        return true
    }

    private fun readIntoDirectBuffer(
        input: BufferedInputStream,
        length: Int,
        cipherState: CipherState?
    ): Boolean {
        directPayload.clear()
        val candidates = cipherState?.candidates.orEmpty()
        if (candidates.isNotEmpty()) {
            val encryptedPacket = ByteArray(length)
            if (!readExact(input, encryptedPacket, length)) throw EOFException("Missing mirror payload")
            val match = selectCtrCandidate(encryptedPacket, length, candidates)
            if (match != null && cipherState != null) {
                val (selectedName, selectedCipher, selectedPlain) = match
                cipherState.selected = selectedCipher
                cipherState.candidates = emptyList()
                Log.i(TAG, "Selected mirror AES-CTR candidate: $selectedName (len=$length)")
                directPayload.put(selectedPlain)
            } else {
                Log.w(TAG, "No mirror AES-CTR candidate matched NAL framing (len=$length)")
                return false
            }
            directPayload.flip()
            return true
        }

        val cipher = cipherState?.selected
        var remaining = length
        while (remaining > 0) {
            val toRead = remaining.coerceAtMost(readChunk.size)
            val n = input.read(readChunk, 0, toRead)
            if (n < 0) throw EOFException("Incomplete mirror payload")
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
