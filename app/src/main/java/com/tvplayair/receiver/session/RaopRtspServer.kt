package com.tvplayair.receiver.session

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.tvplayair.receiver.diagnostics.PLAYBACK_DIAGNOSTICS_TAG
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * AirPlay/RAOP RTSP control server (default port 5000) isolated inside :session.
 * Handles OPTIONS, GET /info, ANNOUNCE, SETUP, RECORD, GET_PARAMETER, SET_PARAMETER,
 * FLUSH and TEARDOWN, and drives RaopAudioRtpReceiver for 48 kHz PCM audio ingest.
 */
internal class RaopRtspServer(
    private val listener: AirPlayProtocolListener,
    private val configProvider: () -> AirPlayServerConfig,
    private val mirrorReceiver: AirPlayMirrorStreamReceiver? = null,
    private val connectionCrypto: () -> AirPlayConnectionCrypto,
    private val sharedGenerationId: AtomicInteger = AtomicInteger(1),
    private val httpConnectionHandler: ((Socket, BufferedInputStream, AirPlayConnectionCrypto, String) -> Unit)? = null
) {
    companion object {
        private const val TAG = "RaopRtspServer"
        const val DEFAULT_RAOP_PORT = 5000
        private const val MAX_HEADER_BYTES = 16 * 1024
        private const val MAX_BUFFERED_BODY_BYTES = 512 * 1024
        private const val MAX_DRAIN_BODY_BYTES = 16 * 1024 * 1024
        private const val CLIENT_SO_TIMEOUT_MS = 30_000
        private const val PUBLIC_METHODS =
            "ANNOUNCE, SETUP, RECORD, PAUSE, FLUSH, TEARDOWN, OPTIONS, GET_PARAMETER, SET_PARAMETER, POST, GET"
        private val VIDEO_CONTROL_PATHS = setOf(
            "/server-info",
            "/reverse",
            "/play",
            "/action",
            "/scrub",
            "/rate",
            "/stop",
            "/playback-info",
            "/setProperty",
            "/getProperty",
            "/fp-setup2"
        )

        fun isDirectVideoControlRequest(firstLine: String): Boolean {
            val parts = firstLine.trim().split(' ')
            val path = parts.getOrNull(1)?.substringBefore('?') ?: return false
            return path in VIDEO_CONTROL_PATHS
        }
    }

    private val running = AtomicBoolean(false)
    private val generationId = sharedGenerationId
    private val nowPlaying = RaopNowPlayingSession(listener::onNowPlaying)
    private val audioReceiver = RaopAudioRtpReceiver { generationId.get() }
    private val timingAndEventServers = AirPlayTimingAndEventServers()

    @Volatile
    private var serverSocket: ServerSocket? = null
    @Volatile
    private var currentVolumeDb: Float = 0.0f
    private var acceptThread: Thread? = null

    fun currentGenerationId(): Int = generationId.get()

    fun start(port: Int = DEFAULT_RAOP_PORT): Boolean {
        if (running.get()) return true
        val socket = runCatching {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(port))
            }
        }.onFailure {
            Log.e(TAG, "Failed to bind RAOP RTSP port $port", it)
        }.getOrNull() ?: return false

        if (!timingAndEventServers.start()) {
            runCatching { socket.close() }
            return false
        }
        serverSocket = socket
        running.set(true)
        acceptThread = Thread({ acceptLoop(socket) }, "TVPlayAir-RaopRtsp").apply {
            isDaemon = true
            start()
        }
        Log.i(TAG, "RAOP RTSP server listening on port $port")
        return true
    }

    fun stop() {
        running.set(false)
        synchronized(nowPlaying) {
            nowPlaying.clear()
            audioReceiver.stop()
        }
        timingAndEventServers.stop()
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptThread?.let {
            it.interrupt()
            runCatching { it.join(500L) }
        }
        acceptThread = null
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running.get() && !socket.isClosed) {
            val client = runCatching { socket.accept() }.getOrNull() ?: break
            Thread({ handleSession(client) }, "TVPlayAir-RtspClient").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun handleSession(client: Socket) {
        client.use { socket ->
            connectionCrypto().use { crypto ->
                handleConnection(socket, BufferedInputStream(socket.getInputStream()), crypto)
            }
        }
    }

    internal fun handleConnection(
        socket: Socket,
        input: BufferedInputStream,
        crypto: AirPlayConnectionCrypto,
        firstHeader: String? = null
    ) {
        val peer = socket.inetAddress?.hostAddress ?: "unknown"
        val source = crypto
        var endReason = "rtsp_server_stopped"
        try {
            socket.soTimeout = CLIENT_SO_TIMEOUT_MS
            val output = socket.getOutputStream()
            var pendingHeader = firstHeader
            while (running.get()) {
                val headerText = pendingHeader ?: readRtspControlHeader(input, MAX_HEADER_BYTES) {
                    val keepControl = running.get() && mirrorReceiver?.hasRecentVideo(source) == true
                    if (keepControl) Log.i(TAG, "RTSP control idle while its mirror transport is producing video")
                    keepControl
                }?.toString(StandardCharsets.UTF_8)
                if (headerText == null) {
                    endReason = "rtsp_peer_eof"
                    nowPlaying.withOwner(source) { Log.i(TAG, "Active RTSP audio control reached EOF") }
                    break
                }
                pendingHeader = null

                val firstLine = headerText.substringBefore("\r\n")
                val httpDelegate = httpConnectionHandler
                if (httpDelegate != null &&
                    (!RtspMessageParser.isRtspRequestLine(firstLine) ||
                        isDirectVideoControlRequest(firstLine))
                ) {
                    endReason = "rtsp_http_handoff"
                    httpDelegate.invoke(socket, input, crypto, headerText)
                    return
                }

                val preliminary = RtspMessageParser.parseRequest(headerText) ?: run {
                    endReason = "rtsp_invalid_request"
                    return
                }
                val lengthHeader = preliminary.header("content-length")
                val contentLength = if (lengthHeader == null) 0 else {
                    lengthHeader.toIntOrNull()?.takeIf { it in 0..MAX_DRAIN_BODY_BYTES } ?: run {
                        endReason = "rtsp_invalid_length"
                        return
                    }
                }
                val body = if (contentLength <= MAX_BUFFERED_BODY_BYTES) {
                    readExact(input, contentLength).also {
                        if (it.size != contentLength) {
                            endReason = "rtsp_incomplete_body"
                            return
                        }
                    }
                } else {
                    if (!drainExact(input, contentLength)) {
                        endReason = "rtsp_incomplete_body"
                        break
                    }
                    ByteArray(0)
                }
                val request = preliminary.copy(body = body)
                Log.i(
                    TAG,
                    "<- $peer ${request.method} ${request.uri} " +
                        "(cseq=${request.cseq()} ct=${request.header("content-type").orEmpty()} len=${body.size})"
                )

                val dacpId = request.header("dacp-id").orEmpty()
                val activeRemote = request.header("active-remote").orEmpty()
                val userAgent = request.header("user-agent").orEmpty().ifBlank { "Apple Device" }
                if (dacpId.isNotEmpty() && activeRemote.isNotEmpty()) {
                    synchronized(nowPlaying) {
                        if (mirrorReceiver?.allowsAudio(source) != false) {
                            listener.onClientConnected(userAgent, dacpId, activeRemote)
                        }
                    }
                }

                if (!dispatchRtsp(request, output, source)) {
                    endReason = "rtsp_teardown"
                    break
                }
            }
        } catch (error: IOException) {
            endReason = when (error) {
                is SocketTimeoutException -> "rtsp_io_timeout"
                is EOFException -> "rtsp_incomplete_header"
                else -> "rtsp_io_error"
            }
            Log.i(TAG, "RTSP connection ended for $peer (${error.javaClass.simpleName})")
        } finally {
            mirrorReceiver?.closeStreamForOwner(source, endReason)
            stopAudioForOwner(source)
        }
    }

    private fun dispatchRtsp(request: RtspRequest, output: OutputStream, source: AirPlayConnectionCrypto): Boolean {
        val cseq = request.cseq()
        when (request.method) {
            "OPTIONS" -> {
                output.write(
                    RtspMessageParser.formatResponse(
                        200, "OK", cseq, mapOf("Public" to PUBLIC_METHODS)
                    )
                )
            }
            "GET" -> {
                if (request.uri.endsWith("/info")) {
                    val cfg = configProvider()
                    val bin = AirPlayInfoPlist.formatInfoBinary(
                        deviceId = cfg.deviceId,
                        maxWidth = cfg.maxWidth,
                        maxHeight = cfg.maxHeight,
                        hevcEnabled = cfg.hevcEnabled
                    )
                    Log.i(TAG, "-> GET /info 200 OK bplist00 (${bin.size}B)")
                    output.write(
                        RtspMessageParser.formatResponse(
                            200, "OK", cseq, mapOf("Content-Type" to "application/x-apple-binary-plist"), bin
                        )
                    )
                } else {
                    output.write(RtspMessageParser.formatResponse(404, "Not Found", cseq))
                }
            }
            "ANNOUNCE" -> {
                source.announcedAudioSdp = String(request.body, StandardCharsets.UTF_8)
                output.write(RtspMessageParser.formatResponse(200, "OK", cseq))
            }
            "POST" -> {
                when {
                    request.uri.endsWith("/pair-setup") -> {
                        val reply = source.pairing.handlePairSetup(request.body)
                        if (reply != null) {
                            Log.i(TAG, "-> /pair-setup 200 OK (${reply.size}B)")
                            output.write(
                                RtspMessageParser.formatResponse(
                                    200, "OK", cseq,
                                    mapOf("Content-Type" to "application/octet-stream"),
                                    reply
                                )
                            )
                        } else {
                            Log.w(TAG, "-> /pair-setup 400 (len=${request.body.size})")
                            output.write(RtspMessageParser.formatResponse(400, "Bad Request", cseq))
                        }
                    }
                    request.uri.endsWith("/pair-verify") -> {
                        val reply = source.pairing.handlePairVerify(request.body)
                        if (reply != null) {
                            Log.i(TAG, "-> /pair-verify 200 OK (${reply.size}B verified=${source.pairing.isVerified})")
                            output.write(
                                RtspMessageParser.formatResponse(
                                    200, "OK", cseq,
                                    if (reply.isNotEmpty()) mapOf("Content-Type" to "application/octet-stream") else emptyMap(),
                                    reply
                                )
                            )
                        } else {
                            Log.w(TAG, "-> /pair-verify 403 Forbidden (len=${request.body.size})")
                            output.write(RtspMessageParser.formatResponse(403, "Forbidden", cseq))
                        }
                    }
                    request.uri.endsWith("/fp-setup") -> {
                        val reply = source.fairPlay.handleFpSetup(request.body)
                        if (reply != null) {
                            Log.i(TAG, "-> /fp-setup 200 OK (${reply.size}B)")
                            output.write(
                                RtspMessageParser.formatResponse(
                                    200, "OK", cseq,
                                    mapOf("Content-Type" to "application/octet-stream"),
                                    reply
                                )
                            )
                        } else {
                            Log.w(TAG, "-> /fp-setup 400 Bad Request (len=${request.body.size})")
                            output.write(RtspMessageParser.formatResponse(400, "Bad Request", cseq))
                        }
                    }
                    else -> {
                        Log.i(TAG, "-> POST ${request.uri} 200 OK")
                        output.write(RtspMessageParser.formatResponse(200, "OK", cseq))
                    }
                }
            }
            "SETUP" -> {
                if (BinaryPlistParser.isBinaryPlist(request.body)) {
                    handleBinaryPlistSetup(request, output, source)
                } else {
                    val transport = RtspMessageParser.parseTransport(request.header("transport"))
                    val accepted = synchronized(nowPlaying) {
                        if (mirrorReceiver?.allowsAudio(source) == false) {
                            rejectConcurrentAudio(output, cseq)
                            false
                        } else {
                            source.announcedAudioSdp?.let(::configureFromSdp)
                            if (audioReceiver.start(RaopAudioRtpReceiver.DEFAULT_AUDIO_PORT)) {
                                activateAudioSource(source, request)
                                true
                            } else {
                                output.write(RtspMessageParser.formatResponse(503, "Service Unavailable", cseq))
                                false
                            }
                        }
                    }
                    if (!accepted) {
                        output.flush()
                        return true
                    }
                    val responseTransport = buildString {
                        append("RTP/AVP/UDP;unicast;mode=record")
                        append(";server_port=").append(RaopAudioRtpReceiver.DEFAULT_AUDIO_PORT)
                        append(";control_port=").append(transport.controlPort ?: RaopAudioRtpReceiver.DEFAULT_CONTROL_PORT)
                        append(";timing_port=").append(transport.timingPort ?: RaopAudioRtpReceiver.DEFAULT_TIMING_PORT)
                    }
                    output.write(
                        RtspMessageParser.formatResponse(
                            200, "OK", cseq,
                            mapOf("Transport" to responseTransport, "Session" to "1")
                        )
                    )
                }
            }
            "RECORD" -> {
                remoteFor(request)?.let { nowPlaying.updateRemote(source, it) }
                nowPlaying.withOwner(source) { listener.onAudioStreamStarted(generationId.get()) }
                Log.i(TAG, "-> RECORD 200 OK (cseq=$cseq gen=${generationId.get()})")
                output.write(
                    RtspMessageParser.formatResponse(
                        200, "OK", cseq, mapOf("Audio-Latency" to "2205")
                    )
                )
            }
            "GET_PARAMETER" -> {
                val bodyText = String(request.body, StandardCharsets.UTF_8)
                if (bodyText.contains("volume", ignoreCase = true)) {
                    val volBody = RtspMessageParser.formatVolumeBody(currentVolumeDb)
                    Log.i(TAG, "-> GET_PARAMETER 200 OK volume=$currentVolumeDb")
                    output.write(
                        RtspMessageParser.formatResponse(
                            200, "OK", cseq,
                            mapOf("Content-Type" to "text/parameters"),
                            volBody
                        )
                    )
                } else {
                    Log.i(TAG, "-> GET_PARAMETER 200 OK")
                    output.write(RtspMessageParser.formatResponse(200, "OK", cseq))
                }
            }
            "SET_PARAMETER" -> {
                val timestamp = RaopMetadataParser.timestamp(request.header("rtp-info"))
                when (request.header("content-type")?.substringBefore(';')?.trim()?.lowercase(Locale.US)) {
                    "application/x-dmap-tagged" -> RaopMetadataParser.track(request.body)?.let {
                        nowPlaying.track(source, timestamp, it, remoteFor(request))
                    }
                    "image/jpeg", "image/png" -> artworkThumbnail(request.body)?.let {
                        nowPlaying.artwork(source, timestamp, it)
                    }
                    "image/none" -> nowPlaying.artwork(source, timestamp, null)
                    "text/parameters", null -> nowPlaying.withOwner(source) { updateVolume(request.body) }
                }
                output.write(RtspMessageParser.formatResponse(200, "OK", cseq))
            }
            "FLUSH" -> {
                nowPlaying.withOwner(source) {
                    listener.onAudioStreamStarted(generationId.incrementAndGet())
                }
                Log.i(TAG, "-> FLUSH 200 OK (cseq=$cseq gen=${generationId.get()})")
                output.write(RtspMessageParser.formatResponse(200, "OK", cseq))
            }
            "TEARDOWN" -> {
                val scope = airPlayTeardownScope(request.body)
                output.write(RtspMessageParser.formatResponse(200, "OK", cseq))
                output.flush()
                when (scope) {
                    AirPlayTeardownScope.AUDIO -> stopAudioForOwner(source)
                    AirPlayTeardownScope.MIRROR -> mirrorReceiver?.closeStreamForOwner(source, "rtsp_mirror_teardown")
                    AirPlayTeardownScope.SESSION -> Unit
                }
                return scope != AirPlayTeardownScope.SESSION
            }
            else -> {
                output.write(RtspMessageParser.formatResponse(200, "OK", cseq))
            }
        }
        output.flush()
        return true
    }

    private fun stopAudioForOwner(source: AirPlayConnectionCrypto) {
        nowPlaying.withOwner(source) {
            nowPlaying.clear(source)
            stopAudioStream()
        }
    }

    private fun stopAudioStream() {
        audioReceiver.stop()
        listener.onAudioStreamStopped()
    }

    private fun activateAudioSource(source: Any, request: RtspRequest) {
        nowPlaying.activate(source, remoteFor(request)) {
            listener.onAudioStreamStarted(generationId.incrementAndGet())
        }
    }

    private fun updateVolume(body: ByteArray) {
        val bodyText = String(body, StandardCharsets.UTF_8)
        for (line in bodyText.lineSequence()) {
            val idx = line.indexOf(':')
            if (idx > 0 && line.substring(0, idx).trim().equals("volume", ignoreCase = true)) {
                line.substring(idx + 1).trim().toFloatOrNull()?.let { currentVolumeDb = it }
            }
        }
        RtspMessageParser.parseVolumeLinear(bodyText)?.let { gain ->
            Log.i(TAG, "RAOP volume update: linear=$gain (db=$currentVolumeDb)")
        }
    }

    private fun artworkThumbnail(bytes: ByteArray): ByteArray? {
        if (bytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val largestSide = maxOf(bounds.outWidth, bounds.outHeight)
        if (bounds.outWidth !in 1..8192 || bounds.outHeight !in 1..8192) return null
        val maxBytes = 128 * 1024
        if (largestSide <= 768 && bytes.size <= maxBytes) return bytes

        val options = BitmapFactory.Options().apply {
            inSampleSize = 1
            while (largestSide / inSampleSize > 768) inSampleSize *= 2
        }
        val output = ByteArrayOutputStream()
        while (true) {
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
            try {
                for (quality in intArrayOf(85, 65)) {
                    output.reset()
                    if (bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output) &&
                        output.size() <= maxBytes
                    ) return output.toByteArray()
                }
            } finally {
                bitmap.recycle()
            }
            if (largestSide / options.inSampleSize <= 384) return null
            options.inSampleSize *= 2
        }
    }

    private fun remoteFor(request: RtspRequest): RaopRemote? {
        val id = request.header("dacp-id")?.takeIf(String::isNotBlank) ?: return null
        val token = request.header("active-remote")?.takeIf(String::isNotBlank) ?: return null
        return RaopRemote(id, token)
    }

    private fun handleBinaryPlistSetup(request: RtspRequest, output: OutputStream, source: AirPlayConnectionCrypto) {
        val cseq = request.cseq()
        val parsed = BinaryPlistParser.parse(request.body) as? Map<*, *>
        Log.i(TAG, "SETUP bplist keys=${parsed?.keys}")
        val ekey = parsed?.get("ekey") as? ByteArray
        val eiv = parsed?.get("eiv") as? ByteArray
        if (ekey != null && ekey.size == 72) {
            val decrypted = source.fairPlay.decryptKey(ekey)
            if (decrypted == null || decrypted.size != 16) {
                Log.w(TAG, "SETUP failed to decrypt FairPlay ekey")
                output.write(RtspMessageParser.formatResponse(400, "Bad Request", cseq))
                return
            }
            source.fairplayAesKey = decrypted
            Log.i(TAG, "SETUP decrypted FairPlay ekey (16B)")
        }
        if (eiv != null && eiv.size == 16) {
            source.sessionIv = eiv.copyOf()
        }

        (parsed?.get("sessionUUID") as? String)?.takeIf(String::isNotBlank)?.let {
            source.mediaSessionId = it
        }
        val streams = airPlaySetupStreams(parsed)
        val responseMap = if (streams.isEmpty()) {
            mapOf(
                "eventPort" to AirPlayTimingAndEventServers.DEFAULT_EVENT_PORT.toLong(),
                "timingPort" to RaopAudioRtpReceiver.DEFAULT_TIMING_PORT.toLong()
            )
        } else synchronized(nowPlaying) {
            // Apply mirror ownership before offering a companion audio port, even if audio is listed first.
            val videoResponses = streams.filter { it["type"] == 110L }
                .mapNotNull { configureStream(it, request, source) }
            val audioStreams = streams.filter { it["type"] == 96L }
            if (audioStreams.isNotEmpty() && mirrorReceiver?.allowsAudio(source) == false) {
                rejectConcurrentAudio(output, cseq)
                return
            }
            mapOf("streams" to (videoResponses + audioStreams.mapNotNull { configureStream(it, request, source) }))
        }

        val encoded = BinaryPlistWriter.encode(responseMap)
        output.write(
            RtspMessageParser.formatResponse(
                200, "OK", cseq,
                mapOf("Content-Type" to "application/x-apple-binary-plist"),
                encoded
            )
        )
    }

    private fun rejectConcurrentAudio(output: OutputStream, cseq: String) {
        Log.i(PLAYBACK_DIAGNOSTICS_TAG, "route=raop event=setup_rejected reason=mirror_owns_audio")
        output.write(RtspMessageParser.formatResponse(455, "Method Not Valid in This State", cseq))
    }

    private fun configureStream(
        stream: Map<*, *>,
        request: RtspRequest,
        source: AirPlayConnectionCrypto
    ): Map<String, Long>? = when (stream["type"]) {
        110L -> { // AirPlay Screen Mirroring video stream
            val connId = (stream["streamConnectionID"] as? Long) ?: 0L
            val ecdhSecret = source.pairing.sharedSecret
            val key16 = source.fairplayAesKey ?: ecdhSecret?.takeIf { it.size >= 16 }?.copyOfRange(0, 16)
            if (key16 != null && connId != 0L) {
                mirrorReceiver?.configureStreamCipherCandidates(source, key16, ecdhSecret, connId)
                if (nowPlaying.retireAudioForMirror(source)) stopAudioStream()
                Log.i(TAG, "Configured mirror AES-CTR stream cipher candidates (connId=$connId hasEcdh=${ecdhSecret != null})")
            }
            mapOf("dataPort" to AirPlayMirrorStreamReceiver.DEFAULT_MIRROR_PORT.toLong(), "type" to 110L)
        }
        96L -> { // AirPlay Audio stream
            val sampleRate = (stream["sr"] as? Long)?.toInt() ?: PcmResampler.TARGET_SAMPLE_RATE
            val ct = (stream["ct"] as? Long)?.toInt() ?: 8
            val spf = (stream["spf"] as? Long)?.toInt() ?: 480
            val shk = (stream["shk"] as? ByteArray)?.takeIf { it.size >= 16 }?.copyOfRange(0, 16)
            val rawKey = source.fairplayAesKey ?: shk
            val ecdhSecret = source.pairing.sharedSecret
            val pairedKey = if (rawKey != null && ecdhSecret != null && ecdhSecret.size >= 16) {
                AirPlayPairingSession.sha512Trunc16(rawKey, ecdhSecret)
            } else {
                rawKey
            }
            val extraKeys = buildList {
                if (shk != null) {
                    add(shk)
                    if (ecdhSecret != null && ecdhSecret.size >= 16) {
                        add(AirPlayPairingSession.sha512Trunc16(shk, ecdhSecret))
                    }
                }
            }
            val codec = when (ct) {
                8 -> RaopAudioCodec.AAC_ELD
                4 -> RaopAudioCodec.AAC_LC
                2 -> RaopAudioCodec.ALAC
                else -> if (sampleRate == 44100) RaopAudioCodec.PCM_L16_44K1 else RaopAudioCodec.PCM_L16_48K
            }
            Log.i(
                TAG,
                "SETUP audio stream: ct=$ct sr=$sampleRate spf=$spf codec=$codec " +
                    "hasKey=${pairedKey != null} hasShk=${shk != null} hasEcdh=${ecdhSecret != null} hasIv=${source.sessionIv != null} keys=${stream.keys}"
            )
            audioReceiver.configureSession(
                codec = codec,
                sampleRate = sampleRate,
                sessionAesKey = pairedKey,
                sessionAesIv = source.sessionIv,
                samplesPerFrame = spf,
                alternateAesKey = rawKey,
                extraAesKeys = extraKeys
            )
            if (audioReceiver.start(RaopAudioRtpReceiver.DEFAULT_AUDIO_PORT)) {
                activateAudioSource(source, request)
            }
            mapOf(
                "dataPort" to RaopAudioRtpReceiver.DEFAULT_AUDIO_PORT.toLong(),
                "controlPort" to RaopAudioRtpReceiver.DEFAULT_CONTROL_PORT.toLong(),
                "type" to 96L
            )
        }
        else -> null
    }

    private fun configureFromSdp(sdp: String) {
        val lower = sdp.lowercase(Locale.US)
        val codec = when {
            lower.contains("aac-eld") || lower.contains("mpeg4-generic") -> RaopAudioCodec.AAC_ELD
            lower.contains("l16/44100") -> RaopAudioCodec.PCM_L16_44K1
            else -> RaopAudioCodec.PCM_L16_48K
        }
        val rate = if (lower.contains("/44100")) 44100 else PcmResampler.TARGET_SAMPLE_RATE
        audioReceiver.configureSession(codec = codec, sampleRate = rate)
    }

    private fun readExact(input: BufferedInputStream, length: Int): ByteArray {
        val buf = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val n = input.read(buf, offset, length - offset)
            if (n <= 0) break
            offset += n
        }
        return if (offset == length) buf else buf.copyOf(offset)
    }

    private fun drainExact(input: BufferedInputStream, length: Int): Boolean {
        val scratch = ByteArray(8192)
        var remaining = length
        while (remaining > 0) {
            val n = input.read(scratch, 0, remaining.coerceAtMost(scratch.size))
            if (n <= 0) return false
            remaining -= n
        }
        return true
    }
}

internal fun airPlaySetupStreams(plist: Map<*, *>?): List<Map<*, *>> =
    (plist?.get("streams") as? List<*>).orEmpty()
        .filterIsInstance<Map<*, *>>()
        .filter { it["type"] == 96L || it["type"] == 110L }
        .distinctBy { it["type"] }


internal enum class AirPlayTeardownScope { AUDIO, MIRROR, SESSION }

internal fun airPlayTeardownScope(body: ByteArray): AirPlayTeardownScope {
    val plist = BinaryPlistParser.parse(body) as? Map<*, *> ?: return AirPlayTeardownScope.SESSION
    val streams = plist["streams"] as? List<*> ?: return AirPlayTeardownScope.SESSION
    val types = streams.map { (it as? Map<*, *>)?.get("type") }.toSet()
    return when (types) {
        setOf(96L) -> AirPlayTeardownScope.AUDIO
        setOf(110L) -> AirPlayTeardownScope.MIRROR
        else -> AirPlayTeardownScope.SESSION
    }
}

internal fun readRtspControlHeader(
    input: BufferedInputStream,
    maxBytes: Int,
    keepIdleControl: () -> Boolean
): ByteArray? {
    val out = ByteArrayOutputStream()
    var matched = 0
    while (out.size() < maxBytes) {
        val b = try {
            input.read()
        } catch (error: SocketTimeoutException) {
            if (out.size() == 0 && keepIdleControl()) continue
            throw error
        }
        if (b == -1) {
            if (out.size() != 0) throw EOFException("Incomplete RTSP header")
            return null
        }
        out.write(b)
        matched = when {
            (matched == 0 || matched == 2) && b == '\r'.code -> matched + 1
            (matched == 1 || matched == 3) && b == '\n'.code -> matched + 1
            b == '\r'.code -> 1
            else -> 0
        }
        if (matched == 4) return out.toByteArray()
    }
    throw IOException("RTSP header exceeds size limit")
}
