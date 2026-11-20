package com.tvplayair.receiver.session

import android.util.Log
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
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
    private val mirrorReceiver: AirPlayMirrorStreamReceiver? = null
) {
    companion object {
        private const val TAG = "RaopRtspServer"
        const val DEFAULT_RAOP_PORT = 5000
        private const val MAX_HEADER_BYTES = 16 * 1024
        private const val MAX_BODY_BYTES = 64 * 1024
        private const val CLIENT_SO_TIMEOUT_MS = 30_000
        private const val PUBLIC_METHODS =
            "ANNOUNCE, SETUP, RECORD, PAUSE, FLUSH, TEARDOWN, OPTIONS, GET_PARAMETER, SET_PARAMETER, POST, GET"
    }

    private val running = AtomicBoolean(false)
    private val generationId = AtomicInteger(1)
    private val audioReceiver = RaopAudioRtpReceiver { generationId.get() }
    private val pairingSession = AirPlayPairingSession()

    @Volatile
    private var serverSocket: ServerSocket? = null
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
        client.use { sock ->
            sock.soTimeout = CLIENT_SO_TIMEOUT_MS
            val input = BufferedInputStream(sock.getInputStream())
            val output = sock.getOutputStream()

            while (running.get()) {
                val headerBytes = readUntilHeaderEnd(input) ?: break
                val headerText = String(headerBytes, StandardCharsets.UTF_8)
                val preliminary = RtspMessageParser.parseRequest(headerText) ?: break
                val contentLength = preliminary.header("content-length")
                    ?.toIntOrNull()?.coerceIn(0, MAX_BODY_BYTES) ?: 0
                val body = if (contentLength > 0) readExact(input, contentLength) else ByteArray(0)
                val request = preliminary.copy(body = body)

                val dacpId = request.header("dacp-id").orEmpty()
                val activeRemote = request.header("active-remote").orEmpty()
                val userAgent = request.header("user-agent").orEmpty().ifBlank { "Apple Device" }
                if (dacpId.isNotEmpty() && activeRemote.isNotEmpty()) {
                    listener.onClientConnected(userAgent, dacpId, activeRemote)
                }

                val keepAlive = dispatchRtsp(request, output)
                if (!keepAlive) break
            }
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
                    val xml = AirPlayInfoPlist.formatInfoXml(
                        deviceId = cfg.deviceId,
                        maxWidth = cfg.maxWidth,
                        maxHeight = cfg.maxHeight,
                        hevcEnabled = cfg.hevcEnabled
                    ).toByteArray(StandardCharsets.UTF_8)
                    output.write(
                        RtspMessageParser.formatResponse(
                            200, "OK", cseq, mapOf("Content-Type" to "text/x-apple-plist+xml"), xml
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
                            output.write(
                                RtspMessageParser.formatResponse(
                                    200, "OK", cseq,
                                    mapOf("Content-Type" to "application/octet-stream"),
                                    reply
                                )
                            )
                        } else {
                            output.write(RtspMessageParser.formatResponse(400, "Bad Request", cseq))
                        }
                    }
                    request.uri.endsWith("/pair-verify") -> {
                        val reply = pairingSession.handlePairVerify(request.body)
                        if (reply != null) {
                            output.write(
                                RtspMessageParser.formatResponse(
                                    200, "OK", cseq,
                                    if (reply.isNotEmpty()) mapOf("Content-Type" to "application/octet-stream") else emptyMap(),
                                    reply
                                )
                            )
                        } else {
                            output.write(RtspMessageParser.formatResponse(403, "Forbidden", cseq))
                        }
                    }
                    else -> output.write(RtspMessageParser.formatResponse(200, "OK", cseq))
                }
            }
            "SETUP" -> {
                if (BinaryPlistParser.isBinaryPlist(request.body)) {
                    handleBinaryPlistSetup(request.body, cseq, output)
                } else {
                    val transport = RtspMessageParser.parseTransport(request.header("transport"))
                    audioReceiver.start(RaopAudioRtpReceiver.DEFAULT_AUDIO_PORT)
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
                output.write(
                    RtspMessageParser.formatResponse(
                        200, "OK", cseq, mapOf("Audio-Latency" to "2205")
                    )
                )
            }
            "GET_PARAMETER", "SET_PARAMETER" -> {
                val bodyText = String(request.body, StandardCharsets.UTF_8)
                RtspMessageParser.parseVolumeLinear(bodyText)?.let { gain ->
                    Log.i(TAG, "RAOP volume update: linear=$gain")
                }
                output.write(RtspMessageParser.formatResponse(200, "OK", cseq))
            }
            "FLUSH" -> {
                generationId.incrementAndGet()
                output.write(RtspMessageParser.formatResponse(200, "OK", cseq))
            }
            "TEARDOWN" -> {
                audioReceiver.stop()
                mirrorReceiver?.clearStreamCipher()
                listener.onClientDisconnected()
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
        val streams = parsed?.get("streams") as? List<*>
        val firstStream = streams?.firstOrNull() as? Map<*, *>
        val streamType = (firstStream?.get("type") as? Long)?.toInt()

        val responseMap: Map<String, Any> = when (streamType) {
            110 -> { // AirPlay Screen Mirroring video stream
                val connId = (firstStream["streamConnectionID"] as? Long) ?: 0L
                val shared = pairingSession.sharedSecret
                if (shared != null && shared.size >= 16 && connId != 0L) {
                    val key16 = shared.copyOfRange(0, 16)
                    val streamKey = AirPlayPairingSession.deriveMirrorStreamKey(key16, connId)
                    val streamIv = AirPlayPairingSession.deriveMirrorStreamIv(key16, connId)
                    mirrorReceiver?.configureStreamCipher(streamKey, streamIv)
                }
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
                val codec = when (ct) {
                    8 -> RaopAudioCodec.AAC_ELD
                    4 -> RaopAudioCodec.AAC_LC
                    else -> RaopAudioCodec.PCM_L16_48K
                }
                audioReceiver.configureSession(codec = codec, sampleRate = sampleRate)
                audioReceiver.start(RaopAudioRtpReceiver.DEFAULT_AUDIO_PORT)
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
                    "eventPort" to configProvider().airPlayPort.toLong(),
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
}
