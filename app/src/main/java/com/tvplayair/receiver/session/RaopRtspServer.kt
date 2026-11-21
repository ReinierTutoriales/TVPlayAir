package com.tvplayair.receiver.session

import android.media.MediaFormat
import android.util.Log
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * AirPlay/RAOP RTSP control server (default port 5000) isolated inside :session.
 * Handles OPTIONS, GET /info, ANNOUNCE, SETUP, RECORD, GET_PARAMETER, SET_PARAMETER,
 * FLUSH and TEARDOWN, and drives RaopAudioRtpReceiver for 48 kHz PCM audio ingest.
 */
class RaopRtspServer(
    private val listener: AirPlayProtocolListener,
    private val configProvider: () -> AirPlayServerConfig,
    private val mirrorReceiver: AirPlayMirrorStreamReceiver? = null,
    private val pairingSession: AirPlayPairingSession = AirPlayPairingSession(),
    private val sharedGenerationId: AtomicInteger = AtomicInteger(1)
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
    }

    private val running = AtomicBoolean(false)
    private val generationId = sharedGenerationId
    private val activeSessions = AtomicInteger(0)
    private val audioReceiver = RaopAudioRtpReceiver { generationId.get() }
    private val timingAndEventServers = AirPlayTimingAndEventServers()

    @Volatile
    private var serverSocket: ServerSocket? = null
    @Volatile
    private var fairplayAesKey: ByteArray? = null
    @Volatile
    private var sessionEiv: ByteArray? = null
    @Volatile
    private var currentVolumeDb: Float = 0.0f
    private var acceptThread: Thread? = null

    fun currentGenerationId(): Int = generationId.get()

    fun start(port: Int = DEFAULT_RAOP_PORT): Boolean {
        configProvider().ed25519PrivateKey?.let { pairingSession.setEdPrivateKey(it) }
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
        timingAndEventServers.stop()
        audioReceiver.stop()
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
            Thread({ handleSession(client) }, "TVPlayAir-RtspClient").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun handleSession(client: Socket) {
        client.use { socket ->
            handleConnection(socket, BufferedInputStream(socket.getInputStream()))
        }
    }

    internal fun handleConnection(
        socket: Socket,
        input: BufferedInputStream,
        firstHeader: String? = null
    ) {
        val peer = socket.inetAddress?.hostAddress ?: "unknown"
        activeSessions.incrementAndGet()
        try {
            socket.soTimeout = CLIENT_SO_TIMEOUT_MS
            val output = socket.getOutputStream()
            var pendingHeader = firstHeader
            while (running.get()) {
                val headerText = pendingHeader
                    ?: readUntilHeaderEnd(input)?.toString(StandardCharsets.UTF_8)
                    ?: break
                pendingHeader = null

                val preliminary = RtspMessageParser.parseRequest(headerText) ?: break
                val lengthHeader = preliminary.header("content-length")
                val contentLength = if (lengthHeader == null) 0 else {
                    lengthHeader.toIntOrNull()?.takeIf { it in 0..MAX_DRAIN_BODY_BYTES } ?: break
                }
                val body = if (contentLength <= MAX_BUFFERED_BODY_BYTES) {
                    readExact(input, contentLength).also { if (it.size != contentLength) return }
                } else {
                    if (!drainExact(input, contentLength)) break
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
                    listener.onClientConnected(userAgent, dacpId, activeRemote)
                }

                if (!dispatchRtsp(request, output)) break
            }
        } catch (error: IOException) {
            Log.i(TAG, "RTSP connection ended for $peer (${error.javaClass.simpleName})")
        } finally {
            activeSessions.decrementAndGet()
        }
    }

    private fun dispatchRtsp(request: RtspRequest, output: OutputStream): Boolean {
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
                configureFromSdp(String(request.body, StandardCharsets.UTF_8))
                output.write(RtspMessageParser.formatResponse(200, "OK", cseq))
            }
            "POST" -> {
                when {
                    request.uri.endsWith("/pair-setup") -> {
                        val reply = pairingSession.handlePairSetup(request.body)
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
                        val reply = pairingSession.handlePairVerify(request.body)
                        if (reply != null) {
                            Log.i(TAG, "-> /pair-verify 200 OK (${reply.size}B verified=${pairingSession.isVerified})")
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
                        val reply = FairPlayNative.handleFpSetup(request.body)
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
                    handleBinaryPlistSetup(request.body, cseq, output)
                } else {
                    val transport = RtspMessageParser.parseTransport(request.header("transport"))
                    audioReceiver.start(RaopAudioRtpReceiver.DEFAULT_AUDIO_PORT)
                    listener.onAudioStreamStarted(generationId.get())
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
                listener.onAudioStreamStarted(generationId.get())
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
                val bodyText = String(request.body, StandardCharsets.UTF_8)
                for (line in bodyText.lineSequence()) {
                    val idx = line.indexOf(':')
                    if (idx > 0 && line.substring(0, idx).trim().equals("volume", ignoreCase = true)) {
                        line.substring(idx + 1).trim().toFloatOrNull()?.let { currentVolumeDb = it }
                    }
                }
                RtspMessageParser.parseVolumeLinear(bodyText)?.let { gain ->
                    Log.i(TAG, "RAOP volume update: linear=$gain (db=$currentVolumeDb)")
                }
                output.write(RtspMessageParser.formatResponse(200, "OK", cseq))
            }
            "FLUSH" -> {
                val newGen = generationId.incrementAndGet()
                listener.onAudioStreamStarted(newGen)
                Log.i(TAG, "-> FLUSH 200 OK (cseq=$cseq gen=$newGen)")
                output.write(RtspMessageParser.formatResponse(200, "OK", cseq))
            }
            "TEARDOWN" -> {
                if (activeSessions.get() <= 1) {
                    audioReceiver.stop()
                    mirrorReceiver?.clearStreamCipher()
                    fairplayAesKey = null
                    sessionEiv = null
                    FairPlayNative.nativeReset()
                    listener.onClientDisconnected()
                }
                output.write(RtspMessageParser.formatResponse(200, "OK", cseq))
                output.flush()
                return false
            }
            else -> {
                output.write(RtspMessageParser.formatResponse(200, "OK", cseq))
            }
        }
        output.flush()
        return true
    }

    private fun handleBinaryPlistSetup(body: ByteArray, cseq: String, output: OutputStream) {
        val parsed = BinaryPlistParser.parse(body) as? Map<*, *>
        Log.i(TAG, "SETUP bplist keys=${parsed?.keys}")
        val ekey = parsed?.get("ekey") as? ByteArray
        val eiv = parsed?.get("eiv") as? ByteArray
        if (ekey != null && ekey.size == 72) {
            val decrypted = FairPlayNative.nativeDecryptKey(ekey)
            if (decrypted != null && decrypted.size == 16) {
                fairplayAesKey = decrypted
                Log.i(TAG, "SETUP decrypted FairPlay ekey (16B)")
            } else {
                Log.w(TAG, "SETUP failed to decrypt FairPlay ekey")
            }
        }
        if (eiv != null && eiv.size == 16) {
            sessionEiv = eiv.copyOf()
        }

        val streams = parsed?.get("streams") as? List<*>
        val firstStream = streams?.firstOrNull() as? Map<*, *>
        val streamType = (firstStream?.get("type") as? Long)?.toInt()

        val responseMap: Map<String, Any> = when (streamType) {
            110 -> { // AirPlay Screen Mirroring video stream
                val connId = (firstStream["streamConnectionID"] as? Long) ?: 0L
                val key16 = fairplayAesKey ?: pairingSession.sharedSecret?.takeIf { it.size >= 16 }?.copyOfRange(0, 16)
                if (key16 != null && connId != 0L) {
                    val streamKey = AirPlayPairingSession.deriveMirrorStreamKey(key16, connId)
                    val streamIv = AirPlayPairingSession.deriveMirrorStreamIv(key16, connId)
                    mirrorReceiver?.configureStreamCipher(streamKey, streamIv)
                    Log.i(TAG, "Configured mirror AES-CTR stream cipher (connId=$connId)")
                }
                val cfg = configProvider()
                listener.onVideoConfigChanged(MediaFormat.MIMETYPE_VIDEO_AVC, cfg.maxWidth, cfg.maxHeight, generationId.get())
                linkedMapOf(
                    "streams" to listOf(
                        linkedMapOf<String, Any>(
                            "dataPort" to AirPlayMirrorStreamReceiver.DEFAULT_MIRROR_PORT.toLong(),
                            "type" to 110L
                        )
                    )
                )
            }
            96 -> { // AirPlay Audio stream
                val sampleRate = (firstStream["sr"] as? Long)?.toInt() ?: PcmResampler.TARGET_SAMPLE_RATE
                val ct = (firstStream["ct"] as? Long)?.toInt() ?: 8
                val spf = (firstStream["spf"] as? Long)?.toInt() ?: 480
                val shk = (firstStream["shk"] as? ByteArray)?.takeIf { it.size >= 16 }?.copyOfRange(0, 16)
                val effectiveKey = fairplayAesKey ?: shk
                val codec = when (ct) {
                    8 -> RaopAudioCodec.AAC_ELD
                    4 -> RaopAudioCodec.AAC_LC
                    else -> if (sampleRate == 44100) RaopAudioCodec.PCM_L16_44K1 else RaopAudioCodec.PCM_L16_48K
                }
                Log.i(
                    TAG,
                    "SETUP audio stream: ct=$ct sr=$sampleRate spf=$spf codec=$codec " +
                        "hasKey=${effectiveKey != null} hasIv=${sessionEiv != null}"
                )
                audioReceiver.configureSession(
                    codec = codec,
                    sampleRate = sampleRate,
                    sessionAesKey = effectiveKey,
                    sessionAesIv = sessionEiv,
                    samplesPerFrame = spf
                )
                audioReceiver.start(RaopAudioRtpReceiver.DEFAULT_AUDIO_PORT)
                listener.onAudioStreamStarted(generationId.get())
                linkedMapOf(
                    "streams" to listOf(
                        linkedMapOf<String, Any>(
                            "dataPort" to RaopAudioRtpReceiver.DEFAULT_AUDIO_PORT.toLong(),
                            "controlPort" to RaopAudioRtpReceiver.DEFAULT_CONTROL_PORT.toLong(),
                            "type" to 96L
                        )
                    )
                )
            }
            else -> { // Initial session SETUP (eiv/ekey + eventPort/timingPort)
                linkedMapOf(
                    "eventPort" to AirPlayTimingAndEventServers.DEFAULT_EVENT_PORT.toLong(),
                    "timingPort" to RaopAudioRtpReceiver.DEFAULT_TIMING_PORT.toLong()
                )
            }
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

    private fun readUntilHeaderEnd(input: BufferedInputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        var matched = 0
        while (out.size() < MAX_HEADER_BYTES) {
            val b = input.read()
            if (b == -1) return null
            out.write(b)
            matched = when {
                (matched == 0 || matched == 2) && b == '\r'.code -> matched + 1
                (matched == 1 || matched == 3) && b == '\n'.code -> matched + 1
                b == '\r'.code -> 1
                else -> 0
            }
            if (matched == 4) return out.toByteArray()
        }
        return null
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
