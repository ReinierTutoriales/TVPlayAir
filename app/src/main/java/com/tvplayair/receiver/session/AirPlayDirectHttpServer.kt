package com.tvplayair.receiver.session

import android.util.Log
import com.tvplayair.receiver.video.DirectPlaybackTelemetry
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
    }

    private val running = AtomicBoolean(false)
    private val generationId = AtomicInteger(1)
    private val latestTelemetry = AtomicReference(
        DirectPlaybackTelemetry(0.0, 0.0, 0f, isPlaying = false, isBuffering = false)
    )

    @Volatile
    private var serverSocket: ServerSocket? = null
    @Volatile
    private var activeConfig = AirPlayServerConfig()
    private var acceptThread: Thread? = null
    private val pairingSession = AirPlayPairingSession()
    private val mirrorReceiver = AirPlayMirrorStreamReceiver(
        generationProvider = { generationId.get() },
        isHevcProvider = { activeConfig.hevcEnabled }
    )
    private val raopRtspServer = RaopRtspServer(
        listener = listener,
        configProvider = { activeConfig },
        mirrorReceiver = mirrorReceiver,
        pairingSession = pairingSession,
        sharedGenerationId = generationId
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

        serverSocket = socket
        running.set(true)
        acceptThread = Thread({ acceptLoop(socket) }, "TVPlayAir-AirPlayHttp").apply {
            isDaemon = true
            start()
        }
        mirrorReceiver.start()
        raopRtspServer.start()
        Log.i(TAG, "AirPlay control HTTP server listening on port ${config.airPlayPort}")
        return true
    }

    override fun stop() {
        running.set(false)
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

    override fun updatePlaybackTelemetry(telemetry: DirectPlaybackTelemetry) {
        latestTelemetry.set(telemetry)
    }

    override fun currentGenerationId(): Int = generationId.get()

    private fun acceptLoop(socket: ServerSocket) {
        while (running.get()) {
            val client = runCatching { socket.accept() }.getOrNull() ?: continue
            Thread({ handleClient(client) }, "TVPlayAir-AirPlayClient").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun handleClient(client: Socket) {
        client.use { sock ->
            sock.soTimeout = CLIENT_SO_TIMEOUT_MS
            sock.tcpNoDelay = true
            val input = BufferedInputStream(sock.getInputStream())
            val output = sock.getOutputStream()
            val peer = sock.inetAddress?.hostAddress ?: "unknown"

            while (running.get()) {
                val headerBytes = readUntilHeaderEnd(input) ?: break
                val headerText = String(headerBytes, StandardCharsets.UTF_8)
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
                val contentType = headers["content-type"].orEmpty()
                Log.i(TAG, "<- $peer $method $fullPath (ct=$contentType len=${body.size})")

                val keepAlive = !headers["connection"].equals("close", ignoreCase = true)
                dispatchRequest(method, path, fullPath, body, output)
                if (!keepAlive) break
            }
        }
    }

    private fun dispatchRequest(
        method: String,
        path: String,
        fullPath: String,
        bodyBytes: ByteArray,
        output: OutputStream
    ) {
        val body = String(bodyBytes, StandardCharsets.UTF_8)
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
                val parsed = AirPlayHttpRequestParser.parsePlayBody(body)
                if (parsed != null) {
                    generationId.incrementAndGet()
                    listener.onDirectVideoUrl(parsed.url, parsed.startPositionSec)
                    Log.i(TAG, "-> /play 200 OK url=${parsed.url} pos=${parsed.startPositionSec}")
                    writeResponse(output, 200, "OK")
                } else {
                    Log.w(TAG, "-> /play 400 Bad Request")
                    writeResponse(output, 400, "Bad Request")
                }
            }
            method == "POST" && path == "/scrub" -> {
                val position = AirPlayHttpRequestParser.parseQueryDouble(fullPath, "position")
                if (position != null) {
                    listener.onDirectVideoCommand("seek", position)
                    writeResponse(output, 200, "OK")
                } else {
                    writeResponse(output, 400, "Bad Request")
                }
            }
            method == "POST" && path == "/rate" -> {
                val rate = AirPlayHttpRequestParser.parseQueryDouble(fullPath, "value")
                if (rate != null) {
                    listener.onDirectVideoCommand(if (rate > 0.0) "play" else "pause", rate)
                    writeResponse(output, 200, "OK")
                } else {
                    writeResponse(output, 400, "Bad Request")
                }
            }
            method == "POST" && path == "/stop" -> {
                listener.onDirectVideoCommand("stop", 0.0)
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
            method == "POST" && path == "/reverse" -> {
                val resp = "HTTP/1.1 101 Switching Protocols\r\n" +
                    "Upgrade: PTTH/1.0\r\n" +
                    "Connection: Upgrade\r\n\r\n"
                output.write(resp.toByteArray(StandardCharsets.US_ASCII))
                output.flush()
            }
            method == "GET" && path == "/playback-info" -> {
                val plist = AirPlayPlaybackInfoFormatter.formatPlist(latestTelemetry.get())
                writeResponse(output, 200, "OK", "text/x-apple-plist+xml", plist)
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
