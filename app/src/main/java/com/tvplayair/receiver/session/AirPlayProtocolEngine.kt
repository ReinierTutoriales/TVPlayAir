package com.tvplayair.receiver.session

import com.tvplayair.receiver.video.DirectPlaybackTelemetry
import java.util.Locale

data class AirPlayServerConfig(
    val airPlayPort: Int = 7000,
    val maxWidth: Int = 3840,
    val maxHeight: Int = 2160,
    val hevcEnabled: Boolean = true,
    val deviceId: String = "58:55:CA:1A:E2:88",
    val ed25519PrivateKey: ByteArray? = null
)

interface AirPlayProtocolListener {
    fun onVideoConfigChanged(mimeType: String, width: Int, height: Int, generationId: Int)
    fun onDirectVideoUrl(url: String, startPosition: Double)
    fun onDirectVideoCommand(command: String, value: Double)
    fun onClientConnected(clientName: String, dacpId: String, activeRemote: String)
    fun onClientDisconnected()
}

/**
 * Stable internal boundary for the AirPlay/RAOP protocol engine inside :session.
 * Allows replacing the underlying C/JVM protocol core without touching UI or presentation.
 */
interface AirPlayProtocolEngine {
    fun start(config: AirPlayServerConfig): Boolean
    fun stop()
    fun updatePlaybackTelemetry(telemetry: DirectPlaybackTelemetry)
    fun currentGenerationId(): Int
}

data class ParsedDirectPlayRequest(
    val url: String,
    val startPosition: Double
)

object AirPlayHttpRequestParser {
    private val xmlLocationRegex = Regex(
        "<key>\\s*Content-Location\\s*</key>\\s*<string>([^<]+)</string>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val xmlStartPosRegex = Regex(
        "<key>\\s*Start-Position\\s*</key>\\s*<(?:real|integer)>([^<]+)</(?:real|integer)>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    fun parsePlayBody(body: ByteArray): ParsedDirectPlayRequest? {
        if (!BinaryPlistParser.isBinaryPlist(body)) {
            return parseTextPlayBody(body.toString(Charsets.UTF_8))
        }

        val fields = BinaryPlistParser.parse(body) as? Map<*, *> ?: return null
        val url = (fields["Content-Location"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null
        val position = (fields["Start-Position"] as? Number)?.toDouble()
        return ParsedDirectPlayRequest(url, normalizeStartPosition(position))
    }

    private fun parseTextPlayBody(body: String): ParsedDirectPlayRequest? {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return null

        if (trimmed.startsWith("<")) {
            val url = xmlLocationRegex.find(trimmed)?.groupValues?.get(1)?.trim()
                ?.replace("&amp;", "&")
                ?: return null
            val pos = xmlStartPosRegex.find(trimmed)?.groupValues?.get(1)?.trim()?.toDoubleOrNull()
            return if (url.isNotEmpty()) ParsedDirectPlayRequest(url, normalizeStartPosition(pos)) else null
        }

        var url: String? = null
        var startPosition = 0.0
        for (line in trimmed.lineSequence()) {
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            val key = line.substring(0, idx).trim().lowercase(Locale.US)
            val value = line.substring(idx + 1).trim()
            when (key) {
                "content-location" -> if (value.isNotEmpty()) url = value
                "start-position" -> startPosition = normalizeStartPosition(value.toDoubleOrNull())
            }
        }
        return url?.let { ParsedDirectPlayRequest(it, startPosition) }
    }

    private fun normalizeStartPosition(position: Double?): Double =
        position?.takeIf { it.isFinite() && it > 0.0 } ?: 0.0

    fun parseQueryDouble(pathWithQuery: String, paramName: String): Double? {
        val qIdx = pathWithQuery.indexOf('?')
        if (qIdx < 0) return null
        val query = pathWithQuery.substring(qIdx + 1)
        for (pair in query.split('&')) {
            val eqIdx = pair.indexOf('=')
            if (eqIdx <= 0) continue
            val key = pair.substring(0, eqIdx).trim()
            if (key.equals(paramName, ignoreCase = true)) {
                return pair.substring(eqIdx + 1).trim().toDoubleOrNull()
            }
        }
        return null
    }
}

object AirPlayPlaybackInfoFormatter {
    fun formatPlist(telemetry: DirectPlaybackTelemetry): String {
        val duration = telemetry.durationSec.coerceAtLeast(0.0)
        val position = telemetry.positionSec.coerceIn(0.0, if (duration > 0.0) duration else Double.MAX_VALUE)
        val rate = telemetry.rate.coerceAtLeast(0f)
        val readyToPlay = duration > 0.0 || telemetry.isPlaying
        val bufferEmpty = telemetry.isBuffering
        val likelyToKeepUp = readyToPlay && !bufferEmpty

        val durationStr = String.format(Locale.US, "%.3f", duration)
        val positionStr = String.format(Locale.US, "%.3f", position)
        val rateStr = String.format(Locale.US, "%.3f", rate)

        return buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            append("<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" ")
            append("\"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n")
            append("<plist version=\"1.0\">\n<dict>\n")
            append("  <key>duration</key><real>").append(durationStr).append("</real>\n")
            append("  <key>position</key><real>").append(positionStr).append("</real>\n")
            append("  <key>rate</key><real>").append(rateStr).append("</real>\n")
            append("  <key>readyToPlay</key>").append(if (readyToPlay) "<true/>" else "<false/>").append("\n")
            append("  <key>playbackBufferEmpty</key>").append(if (bufferEmpty) "<true/>" else "<false/>").append("\n")
            append("  <key>playbackBufferFull</key><false/>\n")
            append("  <key>playbackLikelyToKeepUp</key>").append(if (likelyToKeepUp) "<true/>" else "<false/>").append("\n")
            append("  <key>loadedTimeRanges</key><array><dict>")
            append("<key>duration</key><real>").append(durationStr).append("</real>")
            append("<key>start</key><real>0.000</real>")
            append("</dict></array>\n")
            append("  <key>seekableTimeRanges</key><array><dict>")
            append("<key>duration</key><real>").append(durationStr).append("</real>")
            append("<key>start</key><real>0.000</real>")
            append("</dict></array>\n")
            append("</dict>\n</plist>\n")
        }
    }
}
