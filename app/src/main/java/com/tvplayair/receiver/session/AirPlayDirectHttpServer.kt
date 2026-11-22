package com.tvplayair.receiver.session

import android.media.MediaFormat
import android.util.Log
import com.tvplayair.receiver.video.DirectPlaybackTelemetry
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPInputStream

/**
 * Lightweight AirPlay HTTP control endpoint isolated inside :session.
 * Handles POST /play, POST /scrub, POST /rate, POST /stop, GET /playback-info
 * and extracts DACP-ID / Active-Remote headers for reverse remote control.
 */
class AirPlayDirectHttpServer(
    private val listener: AirPlayProtocolListener
) : AirPlayProtocolEngine {

    companion object {
        private const val TAG = "AirPlayDirectHttpServer"
        private const val MAX_BODY_BYTES = 2 * 1024 * 1024
        private const val CLIENT_SO_TIMEOUT_MS = 30_000
        private const val EVENT_ACK_TIMEOUT_MS = 5_000
        private const val FCUP_TIMEOUT_MS = 4_000L
    }

    private val running = AtomicBoolean(false)
    private val generationId = AtomicInteger(1)
    private val playbackEvents = AirPlayPlaybackEvents()
    private val idleTelemetry = DirectPlaybackTelemetry(0.0, 0.0, 0f, isPlaying = false, isBuffering = false)
    private val latestTelemetry = AtomicReference(idleTelemetry)
    private val fcupRequests = FcupRequests()

    @Volatile
    private var serverSocket: ServerSocket? = null
    @Volatile
    private var activeConfig = AirPlayServerConfig()
    private var acceptThread: Thread? = null
    private val fallbackPairingKey by lazy { ByteArray(32).also(SecureRandom()::nextBytes) }

    private fun newConnectionCrypto() = AirPlayConnectionCrypto {
        activeConfig.ed25519PrivateKey?.takeIf { it.size == 32 } ?: fallbackPairingKey
    }
    private val mirrorReceiver = AirPlayMirrorStreamReceiver(
        generationProvider = { generationId.get() },
        onCodecConfigReady = { isHevc, width, height, generation ->
            val config = activeConfig
            val mimeType = if (isHevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
            val defaultWidth = if (isHevc) config.maxWidth else config.maxWidth.coerceAtMost(1920)
            val defaultHeight = if (isHevc) config.maxHeight else config.maxHeight.coerceAtMost(1080)
            val w = width.takeIf { it > 0 } ?: defaultWidth
            val h = height.takeIf { it > 0 } ?: defaultHeight
            listener.onVideoConfigChanged(mimeType, w, h, generation)
        }
    )
    private val raopRtspServer = RaopRtspServer(
        listener = listener,
        configProvider = { activeConfig },
        mirrorReceiver = mirrorReceiver,
        connectionCrypto = ::newConnectionCrypto,
        sharedGenerationId = generationId,
        httpConnectionHandler = ::handleHttpConnection
    )

    override fun start(config: AirPlayServerConfig): Boolean {
        activeConfig = config
        if (running.get()) return true
        val socket = runCatching {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(config.airPlayPort))
            }
        }.onFailure {
            Log.e(TAG, "Failed to bind AirPlay control port ${config.airPlayPort}", it)
        }.getOrNull() ?: return false

        if (!mirrorReceiver.start()) {
            runCatching { socket.close() }
            return false
        }
        if (!raopRtspServer.start()) {
            mirrorReceiver.stop()
            runCatching { socket.close() }
            return false
        }

        serverSocket = socket
        running.set(true)
        acceptThread = Thread({ acceptLoop(socket) }, "TVPlayAir-AirPlayHttp").apply {
            isDaemon = true
            start()
        }
        Log.i(TAG, "AirPlay control HTTP server listening on port ${config.airPlayPort}")
        return true
    }

    override fun stop() {
        running.set(false)
        playbackEvents.clear()
        fcupRequests.reset()
        raopRtspServer.stop()
        mirrorReceiver.stop()
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptThread?.let {
            it.interrupt()
            runCatching { it.join(500L) }
        }
        acceptThread = null
    }

    override fun updatePlaybackTelemetry(playbackId: Long, telemetry: DirectPlaybackTelemetry) {
        playbackEvents.update(playbackId, telemetry) { latestTelemetry.set(telemetry) }
    }

    override fun finishDirectPlayback(playbackId: Long) {
        playbackEvents.finish(playbackId) {
            fcupRequests.reset()
            latestTelemetry.set(idleTelemetry)
        }
    }

    override fun currentGenerationId(): Int = generationId.get()

    private fun acceptLoop(socket: ServerSocket) {
        while (running.get()) {
            val client = runCatching { socket.accept() }.getOrNull() ?: continue
            Thread({
                try {
                    handleClient(client)
                } catch (error: IOException) {
                    Log.i(TAG, "AirPlay connection ended (${error.javaClass.simpleName})")
                }
            }, "TVPlayAir-AirPlayClient").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun handleClient(client: Socket) {
        client.use { sock ->
            newConnectionCrypto().use { crypto ->
                handleHttpConnection(sock, BufferedInputStream(sock.getInputStream()), crypto)
            }
        }
    }

    private val currentRequestProtocol = ThreadLocal<String>()
    private val currentRequestCSeq = ThreadLocal<String?>()

    internal fun handleHttpConnection(
        sock: Socket,
        input: BufferedInputStream,
        crypto: AirPlayConnectionCrypto,
        firstHeader: String? = null
    ) {
        sock.soTimeout = CLIENT_SO_TIMEOUT_MS
        sock.tcpNoDelay = true
        val output = sock.getOutputStream()
        val peer = sock.inetAddress?.hostAddress ?: "unknown"
        var pendingHeader = firstHeader
        var awaitingNextFcupRequest = false

        while (running.get()) {
            val headerText = pendingHeader
                ?: readAirPlayHttpHeader(input, awaitingNextFcupRequest)?.toString(StandardCharsets.UTF_8)
                ?: break
            pendingHeader = null

            val firstLine = headerText.substringBefore("\r\n")
            if (RtspMessageParser.isRtspRequestLine(firstLine) &&
                !RaopRtspServer.isDirectVideoControlRequest(firstLine)
            ) {
                raopRtspServer.handleConnection(sock, input, crypto, headerText)
                return
            }
            val lines = headerText.split("\r\n")
            if (lines.isEmpty()) break

            val requestParts = lines[0].split(' ')
            if (requestParts.size < 2) break
            val method = requestParts[0].uppercase(Locale.US)
            val fullPath = requestParts[1]
            val path = fullPath.substringBefore('?')

            val headers = mutableMapOf<String, String>()
            for (i in 1 until lines.size) {
                val line = lines[i]
                val colon = line.indexOf(':')
                if (colon > 0) {
                    headers[line.substring(0, colon).trim().lowercase(Locale.US)] =
                        line.substring(colon + 1).trim()
                }
            }

            val dacpId = headers["dacp-id"].orEmpty()
            val activeRemote = headers["active-remote"].orEmpty()
            val userAgent = headers["user-agent"].orEmpty().ifBlank { "Apple Device" }
            if (dacpId.isNotEmpty() && activeRemote.isNotEmpty()) {
                listener.onClientConnected(userAgent, dacpId, activeRemote)
            }

            val contentLength = headers["content-length"]?.toIntOrNull()?.coerceIn(0, MAX_BODY_BYTES) ?: 0
            val body = if (contentLength > 0) readExactBytes(input, contentLength) else ByteArray(0)
            if (body.size != contentLength) break
            val contentType = headers["content-type"].orEmpty()
            val isRtsp = RtspMessageParser.isRtspRequestLine(firstLine)
            val cseq = headers["cseq"]
            currentRequestProtocol.set(if (isRtsp) "RTSP/1.0" else "HTTP/1.1")
            currentRequestCSeq.set(cseq)
            Log.i(TAG, "<- $peer $method $path (proto=${if (isRtsp) "RTSP/1.0" else "HTTP/1.1"} cseq=${cseq ?: "-"} ct=$contentType len=${body.size})")

            val sessionId = headers["x-apple-session-id"]?.takeIf { value ->
                value.length in 1..128 && value.all { it in '!'..'~' }
            }
            val sessionKey = AirPlayPlaybackEvents.SessionKey(peer, sessionId)
            if (method == "POST" && path == "/reverse") {
                handleReverseChannel(sock, input, output, sessionKey)
                return
            }
            val keepAlive = !headers["connection"].equals("close", ignoreCase = true)
            dispatchRequest(method, path, fullPath, body, output, sessionKey, crypto)
            if (!keepAlive) break
            awaitingNextFcupRequest = sock.inetAddress?.isLoopbackAddress == true &&
                method == "GET" && path.startsWith("/__fcup/")
        }
    }

    private fun handleReverseChannel(
        socket: Socket,
        input: BufferedInputStream,
        output: OutputStream,
        key: AirPlayPlaybackEvents.SessionKey
    ) {
        val channel = AirPlayPlaybackEvents.Channel(key, socket)
        playbackEvents.attach(channel)
        try {
            socket.soTimeout = EVENT_ACK_TIMEOUT_MS
            val cseq = currentRequestCSeq.get()
            val responseHeaders = buildString {
                append("HTTP/1.1 101 Switching Protocols\r\n")
                if (!cseq.isNullOrBlank()) {
                    append("CSeq: ").append(cseq).append("\r\n")
                }
                append("Upgrade: PTTH/1.0\r\nConnection: Upgrade\r\n\r\n")
            }
            output.write(responseHeaders.toByteArray(StandardCharsets.US_ASCII))
            output.flush()
            while (running.get() && !socket.isClosed) {
                val event = channel.events.poll(1, TimeUnit.SECONDS) ?: continue
                val reqBytes = event.request(key.appleSessionId)
                Log.i(TAG, "-> /reverse POST /event (${reqBytes.size}B fcup=${event.customXmlBody != null})")
                output.write(reqBytes)
                output.flush()
                val acknowledgement = readAirPlayHttpHeader(input)?.toString(StandardCharsets.US_ASCII)
                    ?: break
                val firstAckLine = acknowledgement.substringBefore("\r\n")
                Log.i(TAG, "<- /reverse ack: $firstAckLine")
                val status = firstAckLine.split(' ')
                val protoOk = status.getOrNull(0) == "HTTP/1.1" || status.getOrNull(0) == "RTSP/1.0"
                if (!protoOk || status.getOrNull(1) != "200") break
                val ackLen = acknowledgement.lineSequence()
                    .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
                    ?.substringAfter(':')
                    ?.trim()
                    ?.toIntOrNull() ?: 0
                if (ackLen > 0) {
                    readExactBytes(input, ackLen.coerceAtMost(MAX_BODY_BYTES))
                }
            }
        } finally {
            playbackEvents.detach(channel)
        }
    }

    private fun buildLocalFcupProxyUrl(customUrl: String, playbackId: Long): String {
        val encoded = URLEncoder.encode(customUrl, StandardCharsets.UTF_8.name())
        return "http://127.0.0.1:${activeConfig.airPlayPort}/__fcup/$playbackId/playlist.m3u8?url=$encoded"
    }

    private fun startDirectPlayback(
        sessionKey: AirPlayPlaybackEvents.SessionKey,
        parsed: ParsedDirectPlayRequest
    ) {
        playbackEvents.start(sessionKey) { playbackId ->
            fcupRequests.reset(playbackId)
            latestTelemetry.set(idleTelemetry.copy(isBuffering = true))
            val playableUrl = if (parsed.url.startsWith("http://", ignoreCase = true) ||
                parsed.url.startsWith("https://", ignoreCase = true)
            ) parsed.url else buildLocalFcupProxyUrl(parsed.url, playbackId)
            listener.onDirectVideoUrl(playableUrl, parsed.startPosition, playbackId)
        }
    }

    private fun handleFcupResponsePlist(bodyBytes: ByteArray): Boolean {
        val root = BinaryPlistParser.parse(bodyBytes) as? Map<*, *> ?: return false
        val params = (root["params"] as? Map<*, *>) ?: root
        val reqId = ((params["FCUP_Response_RequestID"]
            ?: params["FCUP_Response_ID"]
            ?: params["requestID"]
            ?: root["FCUP_Response_RequestID"]
            ?: root["FCUP_Response_ID"]) as? Number)?.toInt()
        val respUrl = ((params["FCUP_Response_URL"]
            ?: params["URL"]
            ?: params["url"]
            ?: root["FCUP_Response_URL"]) as? String)?.trim()?.takeIf(String::isNotEmpty)
        val rawData = params["FCUP_Response_Data"]
            ?: params["data"]
            ?: root["FCUP_Response_Data"]
            ?: findFirstByteArrayInMap(params)
        val rawBytes = when (rawData) {
            is ByteArray -> rawData
            is String -> rawData.toByteArray(StandardCharsets.UTF_8)
            else -> null
        } ?: return false

        val dataBytes = if (rawBytes.size >= 2 &&
            rawBytes[0] == 0x1f.toByte() &&
            rawBytes[1] == 0x8b.toByte()
        ) {
            runCatching { GZIPInputStream(ByteArrayInputStream(rawBytes)).readBytes() }
                .getOrDefault(rawBytes)
        } else {
            rawBytes
        }

        Log.i(TAG, "<- FCUP response id=$reqId len=${dataBytes.size}")

        if (!fcupRequests.complete(reqId, respUrl, dataBytes)) {
            Log.i(TAG, "Ignoring unmatched FCUP response id=$reqId")
        }
        return true
    }

    private fun findFirstByteArrayInMap(map: Map<*, *>): ByteArray? {
        for ((k, v) in map) {
            val keyStr = k as? String ?: continue
            if (keyStr.contains("data", ignoreCase = true) && v is ByteArray && v.isNotEmpty()) {
                return v
            }
        }
        return null
    }

    private fun resolveRelativeFcupUri(baseUrl: String, ref: String): String =
        runCatching { URI(baseUrl).resolve(ref.trim()).toString() }.getOrDefault(ref.trim())

    private fun expandYtCondensedM3u8(m3u8Text: String): String {
        if (!m3u8Text.contains("#YT-EXT-CONDENSED-")) return m3u8Text
        var baseUri: String? = null
        var params: List<String> = emptyList()
        var prefix: String? = null
        val baseUriRegex = Regex("""BASE-URI="([^"]+)"""")
        val paramsRegex = Regex("""PARAMS="([^"]+)"""")
        val prefixRegex = Regex("""PREFIX="([^"]+)"""")
        return buildString {
            for (rawLine in m3u8Text.lineSequence()) {
                val trimmed = rawLine.trim()
                if (trimmed.startsWith("#YT-EXT-CONDENSED-URL:") || trimmed.startsWith("#YT-EXT-CONDENSED-URI:")) {
                    baseUri = baseUriRegex.find(trimmed)?.groupValues?.getOrNull(1)
                    params = paramsRegex.find(trimmed)?.groupValues?.getOrNull(1)
                        ?.split(',')
                        ?.filter { it.isNotEmpty() }
                        .orEmpty()
                    prefix = prefixRegex.find(trimmed)?.groupValues?.getOrNull(1)
                    continue
                }
                val activeBase = baseUri
                val activePrefix = prefix
                if (activeBase != null && activePrefix != null && params.isNotEmpty() &&
                    !trimmed.startsWith("#") && trimmed.startsWith(activePrefix)
                ) {
                    val rest = trimmed.removePrefix(activePrefix)
                    val parts = rest.split('/')
                    val sb = StringBuilder(activeBase)
                    val count = minOf(params.size, parts.size)
                    for (i in 0 until count) {
                        sb.append('/').append(params[i]).append('/').append(parts[i])
                    }
                    for (i in count until parts.size) {
                        sb.append('/').append(parts[i])
                    }
                    append(sb.toString()).append('\n')
                } else {
                    append(rawLine).append('\n')
                }
            }
        }
    }

    private fun rewriteM3u8ForFcupProxy(m3u8Text: String, baseUrl: String, playbackId: Long): String {
        val expandedText = expandYtCondensedM3u8(m3u8Text)
        val uriAttrRegex = Regex("""URI="([^"]+)"""")
        return buildString {
            for (rawLine in expandedText.lineSequence()) {
                val line = rawLine.trim()
                if (line.isEmpty()) {
                    append("\n")
                    continue
                }
                if (line.startsWith("#")) {
                    val rewrittenTag = uriAttrRegex.replace(rawLine) { match ->
                        val rawUri = match.groupValues[1]
                        val resolved = resolveRelativeFcupUri(baseUrl, rawUri)
                        if (resolved.startsWith("http://", ignoreCase = true) ||
                            resolved.startsWith("https://", ignoreCase = true)
                        ) {
                            """URI="$resolved""""
                        } else {
                            """URI="${buildLocalFcupProxyUrl(resolved, playbackId)}""""
                        }
                    }
                    append(rewrittenTag).append("\n")
                } else {
                    val resolved = resolveRelativeFcupUri(baseUrl, line)
                    if (resolved.startsWith("http://", ignoreCase = true) ||
                        resolved.startsWith("https://", ignoreCase = true)
                    ) {
                        append(resolved).append("\n")
                    } else {
                        append(buildLocalFcupProxyUrl(resolved, playbackId)).append("\n")
                    }
                }
            }
        }
    }

    private fun dispatchRequest(
        method: String,
        path: String,
        fullPath: String,
        bodyBytes: ByteArray,
        output: OutputStream,
        sessionKey: AirPlayPlaybackEvents.SessionKey,
        crypto: AirPlayConnectionCrypto
    ) {
        when {
            method == "GET" && path.startsWith("/__fcup/") -> {
                val playbackId = path.removePrefix("/__fcup/").substringBefore('/').toLongOrNull()
                val encodedUrl = fullPath.substringAfter("url=", "").substringBefore('&')
                val targetUrl = runCatching {
                    URLDecoder.decode(encodedUrl, StandardCharsets.UTF_8.name())
                }.getOrDefault("")
                if (targetUrl.isBlank() || playbackId == null) {
                    writeResponse(output, 400, "Bad Request")
                    return
                }
                val reverseReady = playbackEvents.reverseSignal(playbackId)
                if (reverseReady == null) {
                    writeResponse(output, 410, "Gone")
                    return
                }
                val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(FCUP_TIMEOUT_MS)
                val ready = runCatching { reverseReady.get(FCUP_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
                if (ready.isFailure) {
                    val active = playbackEvents.reverseSignal(playbackId) != null
                    if (active) writeResponse(output, 504, "Gateway Timeout")
                    else writeResponse(output, 410, "Gone")
                    return
                }
                // Match the lock order used by startDirectPlayback.
                val pending = playbackEvents.withPlayback(playbackId) {
                    fcupRequests.acquire(playbackId, targetUrl) { id ->
                        playbackEvents.sendFcupRequest(targetUrl, id).also { sent ->
                            Log.i(TAG, "-> FCUP request id=$id sent=$sent")
                        }
                    }
                }
                if (pending == null) {
                    val active = playbackEvents.reverseSignal(playbackId) != null
                    if (active) writeResponse(output, 503, "Service Unavailable")
                    else writeResponse(output, 410, "Gone")
                    return
                }
                val dataBytes = runCatching {
                    pending.result.get(deadline - System.nanoTime(), TimeUnit.NANOSECONDS)
                }.onFailure { error ->
                    fcupRequests.cancel(pending)
                    Log.w(TAG, "FCUP request id=${pending.id} ended: ${error.javaClass.simpleName}")
                }.getOrNull()

                if (dataBytes == null) {
                    if (playbackEvents.reverseSignal(playbackId) != null) {
                        writeResponse(output, 504, "Gateway Timeout")
                    } else {
                        writeResponse(output, 410, "Gone")
                    }
                    return
                }
                val text = runCatching { dataBytes.toString(StandardCharsets.UTF_8) }.getOrNull()
                if (text != null && text.trimStart().startsWith("#EXTM3U")) {
                    val rewritten = rewriteM3u8ForFcupProxy(text, targetUrl, playbackId)
                    Log.i(TAG, "-> /__fcup 200 OK m3u8 (${rewritten.length}B) id=${pending.id}")
                    writeResponse(output, 200, "OK", "application/vnd.apple.mpegurl", rewritten)
                } else {
                    Log.i(TAG, "-> /__fcup 200 OK binary (${dataBytes.size}B) id=${pending.id}")
                    writeBinaryResponse(output, 200, "OK", "application/octet-stream", dataBytes)
                }
            }
            method == "POST" && path == "/pair-setup" -> {
                val reply = crypto.pairing.handlePairSetup(bodyBytes)
                if (reply != null) {
                    Log.i(TAG, "-> /pair-setup 200 OK (${reply.size}B)")
                    writeBinaryResponse(output, 200, "OK", "application/octet-stream", reply)
                } else {
                    Log.w(TAG, "-> /pair-setup 400 (len=${bodyBytes.size})")
                    writeResponse(output, 400, "Bad Request")
                }
            }
            method == "POST" && path == "/pair-verify" -> {
                val reply = crypto.pairing.handlePairVerify(bodyBytes)
                if (reply != null) {
                    Log.i(TAG, "-> /pair-verify 200 OK (${reply.size}B verified=${crypto.pairing.isVerified})")
                    writeBinaryResponse(output, 200, "OK", "application/octet-stream", reply)
                } else {
                    Log.w(TAG, "-> /pair-verify 403 Forbidden (len=${bodyBytes.size})")
                    writeResponse(output, 403, "Forbidden")
                }
            }
            method == "POST" && path == "/fp-setup" -> {
                val reply = crypto.fairPlay.handleFpSetup(bodyBytes)
                if (reply != null) {
                    Log.i(TAG, "-> /fp-setup 200 OK (${reply.size}B)")
                    writeBinaryResponse(output, 200, "OK", "application/octet-stream", reply)
                } else {
                    Log.w(TAG, "-> /fp-setup 400 Bad Request (len=${bodyBytes.size})")
                    writeResponse(output, 400, "Bad Request")
                }
            }
            method == "POST" && path == "/play" -> {
                val parsed = AirPlayHttpRequestParser.parsePlayBody(bodyBytes)
                if (parsed != null) {
                    startDirectPlayback(sessionKey, parsed)
                    Log.i(
                        TAG,
                        "-> /play 200 OK startPosition=${parsed.startPosition}"
                    )
                    writeResponse(output, 200, "OK")
                } else {
                    Log.w(TAG, "-> /play had no direct URL (${bodyBytes.size}B)")
                    writeResponse(output, 200, "OK")
                }
            }
            method == "POST" && path == "/action" -> {
                if (handleFcupResponsePlist(bodyBytes)) {
                    writeResponse(output, 200, "OK")
                } else {
                    val parsed = AirPlayHttpRequestParser.parsePlayBody(bodyBytes)
                    if (parsed != null) {
                        startDirectPlayback(sessionKey, parsed)
                        Log.i(TAG, "-> /action started direct video pos=${parsed.startPosition}")
                    } else {
                        Log.i(TAG, "-> /action 200 OK (${bodyBytes.size}B)")
                    }
                    writeResponse(output, 200, "OK")
                }
            }
            method == "GET" && path == "/scrub" -> {
                val t = latestTelemetry.get()
                val scrubBody = String.format(
                    Locale.US,
                    "duration: %.6f\r\nposition: %.6f\r\n",
                    t.durationSec.coerceAtLeast(0.0),
                    t.positionSec.coerceAtLeast(0.0)
                )
                writeResponse(output, 200, "OK", "text/parameters", scrubBody)
            }
            method == "POST" && path == "/scrub" -> {
                val position = AirPlayHttpRequestParser.parseQueryDouble(fullPath, "position")
                if (position != null) {
                    playbackEvents.control(sessionKey) { id -> listener.onDirectVideoCommand("seek", position, id) }
                    writeResponse(output, 200, "OK")
                } else {
                    writeResponse(output, 400, "Bad Request")
                }
            }
            method == "POST" && path == "/rate" -> {
                val rate = AirPlayHttpRequestParser.parseQueryDouble(fullPath, "value")
                if (rate != null) {
                    Log.i(TAG, "AirPlay playback rate=$rate")
                    playbackEvents.control(sessionKey) { id ->
                        listener.onDirectVideoCommand(if (rate > 0.0) "play" else "pause", rate, id)
                    }
                    writeResponse(output, 200, "OK")
                } else {
                    writeResponse(output, 400, "Bad Request")
                }
            }
            method == "POST" && path == "/stop" -> {
                playbackEvents.control(sessionKey, stop = true) { id ->
                    fcupRequests.reset()
                    latestTelemetry.set(idleTelemetry)
                    listener.onDirectVideoCommand("stop", 0.0, id)
                }
                writeResponse(output, 200, "OK")
            }
            method == "GET" && path == "/info" -> {
                val info = AirPlayInfoPlist.formatInfoBinary(
                    deviceId = activeConfig.deviceId,
                    maxWidth = activeConfig.maxWidth,
                    maxHeight = activeConfig.maxHeight,
                    hevcEnabled = activeConfig.hevcEnabled
                )
                Log.i(TAG, "-> /info 200 OK bplist00 (${info.size}B)")
                writeBinaryResponse(output, 200, "OK", "application/x-apple-binary-plist", info)
            }
            method == "GET" && path == "/server-info" -> {
                val info = AirPlayInfoPlist.formatServerInfoXml(activeConfig.deviceId)
                Log.i(TAG, "-> GET /server-info 200 OK (${info.length}B)")
                writeResponse(output, 200, "OK", "text/x-apple-plist+xml", info)
            }
            method == "GET" && path == "/playback-info" -> {
                val plist = AirPlayPlaybackInfoFormatter.formatPlist(latestTelemetry.get())
                writeResponse(output, 200, "OK", "text/x-apple-plist+xml", plist)
            }
            method == "PUT" && path == "/setProperty" -> {
                if (!handleFcupResponsePlist(bodyBytes)) {
                    val parsed = AirPlayHttpRequestParser.parsePlayBody(bodyBytes)
                    if (parsed != null) {
                        startDirectPlayback(sessionKey, parsed)
                        Log.i(TAG, "-> PUT /setProperty started direct video")
                    } else if (fullPath.contains("selectedMediaArray", ignoreCase = true)) {
                        Log.i(TAG, "-> PUT /setProperty selectedMediaArray (${bodyBytes.size}B)")
                    }
                }
                val reply = BinaryPlistWriter.encode(mapOf("errorCode" to 0L))
                Log.i(TAG, "-> PUT /setProperty 200 OK")
                writeBinaryResponse(output, 200, "OK", "application/x-apple-binary-plist", reply)
            }
            (method == "GET" || method == "POST") && path == "/getProperty" -> {
                val reply = BinaryPlistWriter.encode(mapOf("errorCode" to 0L))
                Log.i(TAG, "-> $method /getProperty 200 OK")
                writeBinaryResponse(output, 200, "OK", "application/x-apple-binary-plist", reply)
            }
            method == "POST" && (path == "/audioMode" || path == "/fp-setup2") -> {
                Log.i(TAG, "-> POST $path 200 OK")
                writeResponse(output, 200, "OK")
            }
            else -> {
                Log.w(TAG, "-> $method $path 404 Not Found")
                writeResponse(output, 404, "Not Found")
            }
        }
    }

    private fun readExactBytes(input: BufferedInputStream, length: Int): ByteArray {
        val buffer = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(buffer, offset, length - offset)
            if (read <= 0) break
            offset += read
        }
        return if (offset == length) buffer else buffer.copyOf(offset)
    }

    private fun writeResponse(
        output: OutputStream,
        statusCode: Int,
        reason: String,
        contentType: String = "text/plain",
        body: String = ""
    ) {
        writeBinaryResponse(
            output,
            statusCode,
            reason,
            contentType,
            body.toByteArray(StandardCharsets.UTF_8)
        )
    }

    private fun writeBinaryResponse(
        output: OutputStream,
        statusCode: Int,
        reason: String,
        contentType: String,
        bodyBytes: ByteArray
    ) {
        val protocol = currentRequestProtocol.get() ?: "HTTP/1.1"
        val cseq = currentRequestCSeq.get()
        val headers = buildString {
            append(protocol).append(' ').append(statusCode).append(' ').append(reason).append("\r\n")
            if (protocol == "RTSP/1.0") {
                append("Server: AirTunes/366.0\r\n")
            }
            if (!cseq.isNullOrBlank()) {
                append("CSeq: ").append(cseq).append("\r\n")
            }
            append("Content-Type: ").append(contentType).append("\r\n")
            append("Content-Length: ").append(bodyBytes.size).append("\r\n\r\n")
        }
        output.write(headers.toByteArray(StandardCharsets.US_ASCII))
        if (bodyBytes.isNotEmpty()) output.write(bodyBytes)
        output.flush()
    }
}

internal fun readAirPlayHttpHeader(
    input: BufferedInputStream,
    allowIdleTimeout: Boolean = false
): ByteArray? {
    val out = ByteArrayOutputStream()
    var matched = 0
    while (out.size() < 16 * 1024) {
        val b = try {
            input.read()
        } catch (error: SocketTimeoutException) {
            if (allowIdleTimeout && out.size() == 0) return null
            throw error
        }
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
