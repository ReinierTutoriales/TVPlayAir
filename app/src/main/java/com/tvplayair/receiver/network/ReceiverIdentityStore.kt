package com.tvplayair.receiver.network

import android.content.Context
import android.provider.Settings
import org.bouncycastle.math.ec.rfc8032.Ed25519
import java.security.SecureRandom
import java.text.Normalizer
import java.util.Locale
import java.util.UUID

data class ReceiverIdentity(
    val serviceName: String,
    val deviceId: String,
    val piUuid: String,
    val ed25519PrivateKey: ByteArray,
    val ed25519PublicKeyHex: String
)

object ReceiverIdentityHelper {
    val ROOM_PRESETS: List<String> = listOf(
        "TVPlayAir Sala",
        "TVPlayAir Habitacion",
        "TVPlayAir Estudio",
        "TVPlayAir Cocina",
        "TVPlayAir"
    )

    /**
     * Formats 6 random bytes as an IEEE 802 locally-administered unicast MAC address
     * so multiple TVPlayAir receivers on the same LAN never collide in AirPlay/mDNS.
     */
    fun formatLocalMac(raw6: ByteArray): String {
        require(raw6.size == 6) { "Expected 6 bytes for MAC address" }
        val b0 = ((raw6[0].toInt() and 0xFE) or 0x02) and 0xFF
        return String.format(
            Locale.US,
            "%02X:%02X:%02X:%02X:%02X:%02X",
            b0,
            raw6[1].toInt() and 0xFF,
            raw6[2].toInt() and 0xFF,
            raw6[3].toInt() and 0xFF,
            raw6[4].toInt() and 0xFF,
            raw6[5].toInt() and 0xFF
        )
    }

    fun toHexLower(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(String.format(Locale.US, "%02x", b.toInt() and 0xFF))
        }
        return sb.toString()
    }

    fun fromHex(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        return runCatching {
            ByteArray(hex.length / 2) { i ->
                hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        }.getOrNull()
    }

    fun deriveEd25519PublicKeyHex(privateKey32: ByteArray): String {
        require(privateKey32.size == Ed25519.SECRET_KEY_SIZE) { "Expected 32-byte Ed25519 private key" }
        val pub = ByteArray(Ed25519.PUBLIC_KEY_SIZE)
        Ed25519.generatePublicKey(privateKey32, 0, pub, 0)
        return toHexLower(pub)
    }

    fun sanitizeServiceName(raw: String?, maxBytes: Int = 48): String {
        val cleaned = Normalizer.normalize(raw.orEmpty(), Normalizer.Form.NFC)
            .replace(Regex("[\\p{Cc}]+"), " ")
            .trim()
            .ifEmpty { "TVPlayAir" }
        var end = 0
        var bytes = 0
        while (end < cleaned.length) {
            val codePoint = cleaned.codePointAt(end)
            val byteCount = when {
                codePoint < 0x80 -> 1
                codePoint < 0x800 -> 2
                codePoint < 0x10000 -> 3
                else -> 4
            }
            if (bytes + byteCount > maxBytes) break
            bytes += byteCount
            end += Character.charCount(codePoint)
        }
        return cleaned.substring(0, end).trimEnd().ifEmpty { "TVPlayAir".take(maxBytes) }
    }

    fun availableNames(systemDeviceName: String?): List<String> {
        val list = ArrayList<String>()
        for (preset in ROOM_PRESETS) {
            list.add(preset)
        }
        val sys = systemDeviceName?.trim().orEmpty()
        if (sys.isNotEmpty()) {
            val prefixed = sanitizeServiceName("TVPlayAir $sys")
            if (list.none { it.equals(prefixed, ignoreCase = true) }) {
                list.add(prefixed)
            }
        }
        return list
    }

    fun nextReceiverName(current: String, systemDeviceName: String?): String {
        val options = availableNames(systemDeviceName)
        val idx = options.indexOfFirst { it.equals(current, ignoreCase = true) }
        return if (idx < 0) options.first() else options[(idx + 1) % options.size]
    }
}

/**
 * Persists a stable per-TV AirPlay identity (unique deviceId MAC, piUuid, Ed25519 keypair
 * and room name) so multiple Android TVs on the same network are cleanly distinguishable on iOS.
 */
class ReceiverIdentityStore(context: Context) {
    companion object {
        private const val PREFS_NAME = "tvplayair_identity"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_PI_UUID = "pi_uuid"
        private const val KEY_SERVICE_NAME = "service_name"
        private const val KEY_ED25519_PRIV = "ed25519_priv"
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun readSystemDeviceName(): String? = runCatching {
        Settings.Global.getString(appContext.contentResolver, Settings.Global.DEVICE_NAME)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    fun loadOrCreate(): ReceiverIdentity {
        val random = SecureRandom()
        val existingMac = prefs.getString(KEY_DEVICE_ID, null)
        val existingUuid = prefs.getString(KEY_PI_UUID, null)
        val existingName = prefs.getString(KEY_SERVICE_NAME, null)
        val existingPrivHex = prefs.getString(KEY_ED25519_PRIV, null)
        val existingPriv = existingPrivHex?.let { ReceiverIdentityHelper.fromHex(it) }
            ?.takeIf { it.size == Ed25519.SECRET_KEY_SIZE }

        val mac = existingMac ?: ByteArray(6).also { random.nextBytes(it) }
            .let { ReceiverIdentityHelper.formatLocalMac(it) }
        val uuid = existingUuid ?: UUID.randomUUID().toString()
        val sysName = readSystemDeviceName()
        // Avoid bare system hostname (e.g. "GoogleTV5124") which collides with Android 14
        // built-in Cast mDNS records; prefix with "TVPlayAir" for conflict-free AirPlay discovery.
        val defaultName = if (!sysName.isNullOrEmpty()) {
            ReceiverIdentityHelper.sanitizeServiceName("TVPlayAir $sysName")
        } else {
            "TVPlayAir"
        }
        val name = existingName
            ?.takeUnless { !sysName.isNullOrEmpty() && it.equals(sysName, ignoreCase = true) }
            ?: defaultName
        val priv = existingPriv ?: ByteArray(Ed25519.SECRET_KEY_SIZE).also {
            Ed25519.generatePrivateKey(random, it)
        }
        val pubHex = ReceiverIdentityHelper.deriveEd25519PublicKeyHex(priv)

        if (existingMac == null || existingUuid == null || name != existingName || existingPriv == null) {
            prefs.edit()
                .putString(KEY_DEVICE_ID, mac)
                .putString(KEY_PI_UUID, uuid)
                .putString(KEY_SERVICE_NAME, name)
                .putString(KEY_ED25519_PRIV, ReceiverIdentityHelper.toHexLower(priv))
                .apply()
        }
        return ReceiverIdentity(
            serviceName = name,
            deviceId = mac,
            piUuid = uuid,
            ed25519PrivateKey = priv,
            ed25519PublicKeyHex = pubHex
        )
    }

    fun saveServiceName(name: String): String {
        val sanitized = ReceiverIdentityHelper.sanitizeServiceName(name)
        prefs.edit().putString(KEY_SERVICE_NAME, sanitized).apply()
        return sanitized
    }
}
