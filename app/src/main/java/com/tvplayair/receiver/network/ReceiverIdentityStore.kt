package com.tvplayair.receiver.network

import android.content.Context
import android.provider.Settings
import java.security.SecureRandom
import java.util.Locale
import java.util.UUID

data class ReceiverIdentity(
    val serviceName: String,
    val deviceId: String,
    val piUuid: String
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

    fun sanitizeServiceName(raw: String?): String {
        val cleaned = raw.orEmpty()
            .replace(Regex("[\\r\\n\\t]+"), " ")
            .trim()
            .take(48)
        return cleaned.ifEmpty { "TVPlayAir" }
    }

    fun availableNames(systemDeviceName: String?): List<String> {
        val list = ArrayList<String>()
        val sys = systemDeviceName?.trim().orEmpty()
        if (sys.isNotEmpty()) {
            list.add(sanitizeServiceName(sys))
        }
        for (preset in ROOM_PRESETS) {
            if (list.none { it.equals(preset, ignoreCase = true) }) {
                list.add(preset)
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
 * Persists a stable per-TV AirPlay identity (unique deviceId MAC, piUuid and room name)
 * so multiple Android TVs on the same network are cleanly distinguishable on iOS.
 */
class ReceiverIdentityStore(context: Context) {
    companion object {
        private const val PREFS_NAME = "tvplayair_identity"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_PI_UUID = "pi_uuid"
        private const val KEY_SERVICE_NAME = "service_name"
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun readSystemDeviceName(): String? = runCatching {
        Settings.Global.getString(appContext.contentResolver, Settings.Global.DEVICE_NAME)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    fun loadOrCreate(): ReceiverIdentity {
        val existingMac = prefs.getString(KEY_DEVICE_ID, null)
        val existingUuid = prefs.getString(KEY_PI_UUID, null)
        val existingName = prefs.getString(KEY_SERVICE_NAME, null)

        val mac = existingMac ?: ByteArray(6).also { SecureRandom().nextBytes(it) }
            .let { ReceiverIdentityHelper.formatLocalMac(it) }
        val uuid = existingUuid ?: UUID.randomUUID().toString()
        val name = existingName ?: ReceiverIdentityHelper.sanitizeServiceName(readSystemDeviceName())

        if (existingMac == null || existingUuid == null || existingName == null) {
            prefs.edit()
                .putString(KEY_DEVICE_ID, mac)
                .putString(KEY_PI_UUID, uuid)
                .putString(KEY_SERVICE_NAME, name)
                .apply()
        }
        return ReceiverIdentity(serviceName = name, deviceId = mac, piUuid = uuid)
    }

    fun saveServiceName(name: String): String {
        val sanitized = ReceiverIdentityHelper.sanitizeServiceName(name)
        prefs.edit().putString(KEY_SERVICE_NAME, sanitized).apply()
        return sanitized
    }
}
