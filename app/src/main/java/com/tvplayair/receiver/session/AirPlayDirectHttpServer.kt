package com.tvplayair.receiver.session

import android.media.MediaFormat
import android.util.Log
import com.tvplayair.receiver.video.DirectPlaybackTelemetry
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

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
        private const val MAX_HEADER_BYTES = 16 * 1024
        private const val MAX_BODY_BYTES = 64 * 1024
        private const val CLIENT_SO_TIMEOUT_MS = 30_000
        private const val EVENT_ACK_TIMEOUT_MS = 5_000
    }

    private val running = AtomicBoolean(false)
    private val generationId = AtomicInteger(1)
    private val playbackEvents = AirPlayPlaybackEvents()
    private val idleTelemetry = DirectPlaybackTelemetry(0.0, 0.0, 0f, isPlaying = false, isBuffering = false)
    private val latestTelemetry = AtomicReference(idleTelemetry)

    @Volatile
    private var serverSocket: ServerSocket? = null
    @Volatile
    private var activeConfig = AirPlayServerConfig()
    private var acceptThread: Thread? = null
    private val pairingSession = AirPlayPairingSession()
    private val mirrorReceiver = AirPlayMirrorStreamReceiver(
        generationProvider = { generationId.get() },
        onCodecConfigReady = { isHevc, width, height, generation ->
            val config = activeConfig
            val mimeType = if (isHevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
            val defaultWidth = if (isHevc) config.maxWidth else config.maxWidth.coerceAtMost(1920)
            val defaultHeight = if (isHevc) config.maxHeight else config.maxHeight.coerceAtMost(1080)
            var w = width.takeIf { it > 0 } ?: defaultWidth
            var h = height.takeIf { it > 0 } ?: defaultHeight
            if (!isHevc && maxOf(w, h) > 1920) {
                val scale = 1920.0 / maxOf(w, h).toDouble()
                w = ((w * scale).toInt() and 1.inv()).coerceAtLeast(16)
                h = ((h * scale).toInt() and 1.inv()).coerceAtLeast(16)
            }
            listener.onVideoConfigChanged(mimeType, w, h, generation)
        }
    )
    private val raopRtspServer = RaopRtspServer(
        listener = listener,
        configProvider = { activeConfig },
        mirrorReceiver = mirrorReceiver,
        pairingSession = pairingSession,
        sharedGenerationId = generationId,
        httpConnectionHandler = ::handleHttpConnection
    )

    override fun start(config: AirPlayServerConfig): Boolean {
        activeConfig = config
        config.ed25519PrivateKey?.let { pairingSession.setEdPrivateKey(it) }
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
        playbackEvents.finish(playbackId) { latestTelemetry.set(idleTelemetry) }
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
            handleHttpConnection(sock, BufferedInputStream(sock.getInputStream()))
        }
    }

    internal fun handleHttpConnection(
        sock: Socket,
        input: BufferedInputStream,
        firstHeader: String? = null
    ) {
        sock.soTimeout = CLIENT_SO_TIMEOUT_MS
        sock.tcpNoDelay = true
        val output = sock.getOutputStream()
        val peer = sock.inetAddress?.hostAddress ?: "unknown"
        var pendingHeader = firstHeader

        while (running.get()) {
            val headerText = pendingHeader
                ?: readUntilHeaderEnd(input)?.toString(StandardCharsets.UTF_8)
                ?: break
            pendingHeader = null

            val firstLine = headerText.substringBefore("\r\n")
            if (RtspMessageParser.isRtspRequestLine(firstLine) &&
                !RaopRtspServer.isDirectVideoControlRequest(firstLine)
            ) {
                raopRtspServer.handleConnection(sock, input, headerText)
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
            Log.i(TAG, "<- $peer $method $fullPath (ct=$contentType len=${body.size})")

            val sessionId = headers["x-apple-session-id"]?.takeIf { value ->
                value.length in 1..128 && value.all { it in '!'..'~' }
            }
            val sessionKey = AirPlayPlaybackEvents.SessionKey(peer, sessionId)
            if (method == "POST" && path == "/reverse") {
                handleReverseChannel(sock, input, output, sessionKey)
                return
            }
            val keepAlive = !headers["connection"].equals("close", ignoreCase = true)
            dispatchRequest(method, path, fullPath, body, output, sessionKey)
            if (!keepAlive) break
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
            output.write(
                ("HTTP/1.1 101 Switching Protocols\r\n" +
                    "Upgrade: PTTH/1.0\r\nConnection: Upgrade\r\n\r\n")
                    .toByteArray(StandardCharsets.US_ASCII)
            )
            output.flush()
            while (running.get() && !socket.isClosed) {
                val event = channel.events.poll(1, TimeUnit.SECONDS) ?: continue
                output.write(event.request(key.appleSessionId))
                output.flush()
                val acknowledgement = readUntilHeaderEnd(input)?.toString(StandardCharsets.US_ASCII)
                    ?: break
                val status = acknowledgement.substringBefore("\r\n").split(' ')
                if (status.getOrNull(0) != "HTTP/1.1" || status.getOrNull(1) != "200") break
            }
        } finally {
            playbackEvents.detach(channel)
        }
    }

    private fun dispatchRequest(
        method: String,
        path: String,
        fullPath: String,
        bodyBytes: ByteArray,
        output: OutputStream,
        sessionKey: AirPlayPlaybackEvents.SessionKey
    ) {
        when {
            method == "POST" && path == "/pair-setup" -> {
                val reply = pairingSession.handlePairSetup(bodyBytes)
                if (reply != null) {
                    Log.i(TAG, "-> /pair-setup 200 OK (${reply.size}B)")
                    writeBinaryResponse(output, 200, "OK", "application/octet-stream", reply)
                } else {
                    Log.w(TAG, "-> /pair-setup 400 (len=${bodyBytes.size})")
                    writeResponse(output, 400, "Bad Request")
                }
            }
            method == "POST" && path == "/pair-verify" -> {
                val reply = pairingSession.handlePairVerify(bodyBytes)
                if (reply != null) {
                    Log.i(TAG, "-> /pair-verify 200 OK (${reply.size}B verified=${pairingSession.isVerified})")
                    writeBinaryResponse(output, 200, "OK", "application/octet-stream", reply)
                } else {
                    Log.w(TAG, "-> /pair-verify 403 Forbidden (len=${bodyBytes.size})")
                    writeResponse(output, 403, "Forbidden")
                }
            }
            method == "POST" && path == "/fp-setup" -> {
                val reply = FairPlayNative.handleFpSetup(bodyBytes)
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
                    playbackEvents.start(sessionKey) { playbackId ->
                        latestTelemetry.set(idleTelemetry.copy(isBuffering = true))
                        listener.onDirectVideoUrl(parsed.url, parsed.startPosition, playbackId)
                    }
                    Log.i(TAG, "-> /play 200 OK url=${parsed.url} startPosition=${parsed.startPosition}")
                    writeResponse(output, 200, "OK")
                } else {
                    Log.w(TAG, "-> /play had no direct URL (${AirPlayHttpRequestParser.summarizePlist(bodyBytes)})")
                    writeResponse(output, 200, "OK")
                }
            }
            method == "POST" && path == "/action" -> {
                val parsed = AirPlayHttpRequestParser.parsePlayBody(bodyBytes)
                if (parsed != null) {
                    playbackEvents.start(sessionKey) { playbackId ->
                        latestTelemetry.set(idleTelemetry.copy(isBuffering = true))
                        listener.onDirectVideoUrl(parsed.url, parsed.startPosition, playbackId)
                    }
                    Log.i(TAG, "-> /action started direct video url=${parsed.url} pos=${parsed.startPosition}")
                } else {
                    Log.i(TAG, "-> /action 200 OK (${AirPlayHttpRequestParser.summarizePlist(bodyBytes)})")
                }
                val reply = BinaryPlistWriter.encode(mapOf("errorCode" to 0L))
                writeBinaryResponse(output, 200, "OK", "application/x-apple-binary-plist", reply)
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
                writeResponse(output, 200, "OK", "text/x-apple-plist+xml", info)
            }
            method == "GET" && path == "/playback-info" -> {
                val plist = AirPlayPlaybackInfoFormatter.formatPlist(latestTelemetry.get())
                writeResponse(output, 200, "OK", "text/x-apple-plist+xml", plist)
            }
            method == "PUT" && path == "/setProperty" -> {
                val parsed = AirPlayHttpRequestParser.parsePlayBody(bodyBytes)
                if (parsed != null) {
                    playbackEvents.start(sessionKey) { playbackId ->
                        latestTelemetry.set(idleTelemetry.copy(isBuffering = true))
                        listener.onDirectVideoUrl(parsed.url, parsed.startPosition, playbackId)
                    }
                    Log.i(TAG, "-> PUT /setProperty started direct video url=${parsed.url}")
                } else if (fullPath.contains("selectedMediaArray", ignoreCase = true)) {
                    Log.i(TAG, "-> PUT $fullPath (${AirPlayHttpRequestParser.summarizePlist(bodyBytes)})")
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
        val headers = buildString {
            append("HTTP/1.1 ").append(statusCode).append(' ').append(reason).append("\r\n")
            append("Content-Type: ").append(contentType).append("\r\n")
            append("Content-Length: ").append(bodyBytes.size).append("\r\n\r\n")
        }
        output.write(headers.toByteArray(StandardCharsets.US_ASCII))
        if (bodyBytes.isNotEmpty()) output.write(bodyBytes)
        output.flush()
    }
}
