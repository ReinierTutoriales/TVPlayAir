package com.tvplayair.receiver.session

import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.pow

data class RtspRequest(
    val method: String,
    val uri: String,
    val headers: Map<String, String>,
    val body: ByteArray
) {
    fun header(name: String): String? = headers[name.lowercase(Locale.US)]
    fun cseq(): String = header("cseq") ?: "1"
}

data class RtspTransportParams(
    val controlPort: Int?,
    val timingPort: Int?,
    val mode: String?
)

object RtspMessageParser {
    fun isRtspRequestLine(line: String): Boolean =
        line.trimEnd().substringAfterLast(' ', missingDelimiterValue = "") == "RTSP/1.0"

    fun parseRequest(headerText: String, body: ByteArray = ByteArray(0)): RtspRequest? {
        val lines = headerText.split("\r\n").filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null
        val parts = lines[0].trim().split(' ')
        if (parts.size < 2) return null

        val headers = linkedMapOf<String, String>()
        for (i in 1 until lines.size) {
            val colon = lines[i].indexOf(':')
            if (colon > 0) {
                val key = lines[i].substring(0, colon).trim().lowercase(Locale.US)
                val value = lines[i].substring(colon + 1).trim()
                headers[key] = value
            }
        }
        return RtspRequest(
            method = parts[0].uppercase(Locale.US),
            uri = parts[1],
            headers = headers,
            body = body
        )
    }

    fun parseTransport(transportHeader: String?): RtspTransportParams {
        if (transportHeader.isNullOrBlank()) return RtspTransportParams(null, null, null)
        var controlPort: Int? = null
        var timingPort: Int? = null
        var mode: String? = null
        for (token in transportHeader.split(';')) {
            val eq = token.indexOf('=')
            if (eq <= 0) continue
            val key = token.substring(0, eq).trim().lowercase(Locale.US)
            val value = token.substring(eq + 1).trim()
            when (key) {
                "control_port" -> controlPort = value.toIntOrNull()
                "timing_port" -> timingPort = value.toIntOrNull()
                "mode" -> mode = value
            }
        }
        return RtspTransportParams(controlPort, timingPort, mode)
    }

    internal fun formatAudioSetupTransport(dataPort: Int, controlPort: Int, timingPort: Int): String =
        "RTP/AVP/UDP;unicast;mode=record;server_port=$dataPort;control_port=$controlPort;timing_port=$timingPort"

    /**
     * Parses AirPlay SET_PARAMETER "volume: -14.400000" (dB, -30..0, -144 = mute)
     * into linear gain 0.0f..1.0f.
     */
    fun parseVolumeLinear(bodyText: String): Float? {
        for (line in bodyText.lineSequence()) {
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            val key = line.substring(0, idx).trim().lowercase(Locale.US)
            if (key == "volume") {
                val db = line.substring(idx + 1).trim().toDoubleOrNull() ?: return null
                if (db <= -144.0) return 0f
                val clamped = db.coerceIn(-30.0, 0.0)
                return 10.0.pow(clamped / 20.0).toFloat().coerceIn(0f, 1f)
            }
        }
        return null
    }

    fun formatVolumeBody(volumeDb: Float = 0.0f): ByteArray =
        String.format(Locale.US, "volume: %.6f\r\n", volumeDb).toByteArray(Charsets.US_ASCII)

    fun formatResponse(
        statusCode: Int,
        reason: String,
        cseq: String,
        extraHeaders: Map<String, String> = emptyMap(),
        body: ByteArray = ByteArray(0)
    ): ByteArray {
        val header = buildString {
            append("RTSP/1.0 ").append(statusCode).append(' ').append(reason).append("\r\n")
            append("CSeq: ").append(cseq).append("\r\n")
            append("Server: AirTunes/220.68\r\n")
            extraHeaders.forEach { (k, v) ->
                append(k).append(": ").append(v).append("\r\n")
            }
            if (body.isNotEmpty()) {
                append("Content-Length: ").append(body.size).append("\r\n")
            }
            append("\r\n")
        }.toByteArray(Charsets.US_ASCII)

        if (body.isEmpty()) return header
        return header + body
    }
}

/**
 * AirPlay/RAOP session cipher helpers using JCA AES-128-CBC (RAOP audio)
 * and AES-128-CTR (AirPlay mirroring video).
 */
object SessionCrypto {
    /**
     * Per Apple RAOP spec, AES-128-CBC decrypts only full 16-byte blocks;
     * any trailing remainder (< 16 bytes) is left unencrypted.
     */
    fun decryptRaopAudioCbc(
        key16: ByteArray,
        iv16: ByteArray,
        input: ByteArray,
        offset: Int = 0,
        length: Int = input.size - offset
    ): ByteArray {
        require(key16.size == 16 && iv16.size == 16) { "AES-128 requires 16-byte key and IV" }
        val result = input.copyOfRange(offset, offset + length)
        val fullBlockLen = length - (length % 16)
        if (fullBlockLen <= 0) return result

        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key16, "AES"), IvParameterSpec(iv16))
        val decrypted = cipher.doFinal(result, 0, fullBlockLen)
        System.arraycopy(decrypted, 0, result, 0, fullBlockLen)
        return result
    }
}
