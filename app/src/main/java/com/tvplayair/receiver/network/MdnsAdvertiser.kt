package com.tvplayair.receiver.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.tvplayair.receiver.diagnostics.PLAYBACK_DIAGNOSTICS_TAG
import com.tvplayair.receiver.session.ReceiverAvailability
import java.util.UUID

data class AirPlayAdvertiseConfig(
    val serviceName: String = "TVPlayAir",
    val deviceId: String = "58:55:CA:1A:E2:88",
    val airPlayPort: Int = 7000,
    val raopPort: Int = 5000,
    val maxWidth: Int = 3840,
    val maxHeight: Int = 2160,
    val hevcEnabled: Boolean = true,
    val piUuid: String = UUID.randomUUID().toString(),
    val publicKeyHex: String = "0".repeat(64)
)

object MdnsTxtRecords {
    const val AIRPLAY_SERVICE_TYPE = "_airplay._tcp."
    const val RAOP_SERVICE_TYPE = "_raop._tcp."
    private const val AIRPLAY_FEATURES = (0x1CL shl 32) or 0x5A7FFFF3L
    private const val SCREEN_MULTI_CODEC = 1L shl 42

    fun airPlayFeatures(hevcEnabled: Boolean): Long =
        if (hevcEnabled) AIRPLAY_FEATURES or SCREEN_MULTI_CODEC else AIRPLAY_FEATURES

    private fun featureTxt(hevcEnabled: Boolean): String {
        val features = airPlayFeatures(hevcEnabled)
        val low = (features and 0xFFFFFFFFL).toString(16).uppercase()
        val high = (features ushr 32).toString(16).uppercase()
        return "0x$low,0x$high"
    }
    const val AIRPLAY_FLAGS = "0x44"
    const val AIRPLAY_MODEL = "AppleTV3,2"

    fun buildAirPlayTxt(config: AirPlayAdvertiseConfig): Map<String, String> = linkedMapOf(
        "acl" to "0",
        "deviceid" to config.deviceId,
        "features" to featureTxt(config.hevcEnabled),
        "flags" to AIRPLAY_FLAGS,
        "model" to AIRPLAY_MODEL,
        "overscanned" to "0",
        "pi" to config.piUuid,
        "pk" to config.publicKeyHex,
        "rsf" to "0x0",
        "srcvers" to "220.68",
        "vv" to "2"
    )

    fun buildAirPlayServiceName(config: AirPlayAdvertiseConfig, attempt: Int = 0): String =
        buildInstanceName(config, "", attempt)

    fun buildRaopServiceName(config: AirPlayAdvertiseConfig, attempt: Int = 0): String =
        buildInstanceName(config, "${config.deviceId.replace(":", "").uppercase()}@", attempt)

    private fun buildInstanceName(config: AirPlayAdvertiseConfig, prefix: String, attempt: Int): String {
        val deviceSuffix = config.deviceId.replace(":", "").takeLast(6).uppercase()
        val suffix = if (attempt == 0) "" else " (TVPlayAir $deviceSuffix-$attempt)"
        // The service instance is a single DNS label with a 63-octet limit (RFC 6763).
        val byteLimit = 63 - prefix.toByteArray(Charsets.UTF_8).size - suffix.toByteArray(Charsets.UTF_8).size
        val name = ReceiverIdentityHelper.sanitizeServiceName(config.serviceName, byteLimit)
        return "$prefix$name$suffix"
    }

    fun buildRaopTxt(config: AirPlayAdvertiseConfig): Map<String, String> = linkedMapOf(
        "am" to AIRPLAY_MODEL,
        "ch" to "2",
        "cn" to "0,2,3",
        "da" to "true",
        "et" to "0,3,5",
        "ft" to featureTxt(config.hevcEnabled),
        "md" to "0,1,2",
        "pk" to config.publicKeyHex,
        "pw" to "false",
        "sf" to AIRPLAY_FLAGS,
        "sr" to "44100",
        "ss" to "16",
        "sv" to "false",
        "tp" to "UDP",
        "vn" to "65537",
        "vs" to "220.68",
        "vv" to "2"
    )
}

/**
 * Publishes _airplay._tcp (7000) and _raop._tcp (5000) via Android NsdManager
 * and holds WIFI_MODE_FULL_LOW_LATENCY + MulticastLock inside the :session process.
 */
class MdnsAdvertiser(
    context: Context,
    private val onAirPlayAvailability: (ReceiverAvailability) -> Unit
) {
    companion object {
        private const val TAG = "MdnsAdvertiser"
        private val retryDelaysMs = longArrayOf(500, 1_500, 4_000)
    }

    private val appContext = context.applicationContext
    private val nsdManager: NsdManager? = appContext.getSystemService(NsdManager::class.java)
    private val wifiManager: WifiManager? = appContext.getSystemService(WifiManager::class.java)
    private val connectivityManager: ConnectivityManager? =
        appContext.getSystemService(ConnectivityManager::class.java)

    private var wifiLock: WifiManager.WifiLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private val advertisements = mutableMapOf<String, Advertisement>()
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var activeNetwork: Network? = null
    private var activeConfig: AirPlayAdvertiseConfig? = null

    @Synchronized
    fun start(config: AirPlayAdvertiseConfig) {
        if (isSameMdnsAdvertisement(activeConfig, config)) return
        if (activeConfig != null) stop()
        activeConfig = config
        acquireLocks()
        registerAdvertisements(config)
        registerNetworkCallback()
    }

    @Synchronized
    fun stop() {
        activeConfig = null
        unregisterNetworkCallback()
        unregisterAdvertisements()
        releaseLocks()
    }

    private fun reregisterOnNetworkChange() {
        val config = activeConfig ?: return
        Log.i(TAG, "Network changed; re-registering AirPlay and RAOP mDNS advertisements")
        unregisterAdvertisements()
        registerAdvertisements(config)
    }

    private fun registerAdvertisements(config: AirPlayAdvertiseConfig) {
        if (nsdManager == null) {
            onAirPlayAvailability(ReceiverAvailability.UNAVAILABLE)
            return
        }
        onAirPlayAvailability(ReceiverAvailability.STARTING)
        registerService(
            serviceName = { attempt -> MdnsTxtRecords.buildAirPlayServiceName(config, attempt) },
            serviceType = MdnsTxtRecords.AIRPLAY_SERVICE_TYPE,
            port = config.airPlayPort,
            txt = MdnsTxtRecords.buildAirPlayTxt(config)
        )

        registerService(
            serviceName = { attempt -> MdnsTxtRecords.buildRaopServiceName(config, attempt) },
            serviceType = MdnsTxtRecords.RAOP_SERVICE_TYPE,
            port = config.raopPort,
            txt = MdnsTxtRecords.buildRaopTxt(config)
        )
    }

    private fun unregisterAdvertisements() {
        advertisements.values.forEach { it.stop() }
        advertisements.clear()
    }

    private fun registerNetworkCallback() {
        val cm = connectivityManager ?: return
        if (networkCallback != null) return
        activeNetwork = cm.activeNetwork
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                synchronized(this@MdnsAdvertiser) {
                    if (networkCallback !== this) return
                    Log.i(PLAYBACK_DIAGNOSTICS_TAG,
                        "route=network event=default_available handle=${network.networkHandle}")
                    if (activeNetwork == network) return
                    activeNetwork = network
                    reregisterOnNetworkChange()
                }
            }

            override fun onLost(network: Network) {
                synchronized(this@MdnsAdvertiser) {
                    if (networkCallback !== this) return
                    Log.i(PLAYBACK_DIAGNOSTICS_TAG,
                        "route=network event=default_lost handle=${network.networkHandle}")
                    if (activeNetwork == network) {
                        activeNetwork = null
                        onAirPlayAvailability(ReceiverAvailability.NETWORK_UNAVAILABLE)
                    }
                }
            }
        }
        runCatching {
            cm.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        }.onFailure {
            Log.w(TAG, "Could not register network callback for mDNS recovery", it)
        }
    }

    private fun unregisterNetworkCallback() {
        val cm = connectivityManager ?: return
        val callback = networkCallback ?: return
        networkCallback = null
        activeNetwork = null
        runCatching { cm.unregisterNetworkCallback(callback) }
    }

    private fun isSameMdnsAdvertisement(
        a: AirPlayAdvertiseConfig?,
        b: AirPlayAdvertiseConfig
    ): Boolean = a != null &&
        a.serviceName == b.serviceName &&
        a.deviceId == b.deviceId &&
        a.airPlayPort == b.airPlayPort &&
        a.raopPort == b.raopPort &&
        a.hevcEnabled == b.hevcEnabled &&
        a.piUuid == b.piUuid &&
        a.publicKeyHex == b.publicKeyHex

    private fun registerService(
        serviceName: (Int) -> String,
        serviceType: String,
        port: Int,
        txt: Map<String, String>
    ) {
        val manager = nsdManager ?: return
        val advertisement = Advertisement(manager, serviceType, serviceName, port, txt)
        advertisements[serviceType] = advertisement
        advertisement.register()
    }

    private inner class Advertisement(
        private val manager: NsdManager,
        private val serviceType: String,
        private val serviceName: (Int) -> String,
        private val port: Int,
        private val txt: Map<String, String>
    ) {
        private var listener: NsdManager.RegistrationListener? = null
        private var failures = 0
        private val isAirPlay = serviceType == MdnsTxtRecords.AIRPLAY_SERVICE_TYPE
        private val retry = Runnable {
            synchronized(this@MdnsAdvertiser) {
                if (advertisements[serviceType] === this) register()
            }
        }

        fun register() {
            val info = NsdServiceInfo().apply {
                this.serviceName = this@Advertisement.serviceName(failures)
                this.serviceType = this@Advertisement.serviceType
                this.port = this@Advertisement.port
                txt.forEach { (key, value) -> setAttribute(key, value) }
            }
            val attempt = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                    synchronized(this@MdnsAdvertiser) {
                        if (listener === this) {
                            Log.i(TAG, "Registered $serviceType as ${serviceInfo.serviceName}:${info.port}")
                            if (isAirPlay) onAirPlayAvailability(ReceiverAvailability.AVAILABLE)
                        }
                    }
                }

                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    synchronized(this@MdnsAdvertiser) {
                        if (listener !== this) return
                        Log.w(TAG, "Failed to register $serviceType (code=$errorCode)")
                        scheduleRetry()
                    }
                }

                override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit

                override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "Failed to unregister $serviceType (code=$errorCode)")
                }
            }
            listener = attempt
            try {
                manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, attempt)
            } catch (error: RuntimeException) {
                Log.w(TAG, "Exception registering $serviceType", error)
                scheduleRetry()
            }
        }

        private fun scheduleRetry() {
            listener = null
            val delayMs = retryDelaysMs.getOrNull(failures++)
            if (delayMs == null) {
                Log.e(TAG, "Registration retries exhausted for $serviceType")
                if (isAirPlay) onAirPlayAvailability(ReceiverAvailability.UNAVAILABLE)
                return
            }
            handler.postDelayed(retry, delayMs)
        }

        fun stop() {
            handler.removeCallbacks(retry)
            val attempt = listener ?: return
            listener = null
            runCatching { manager.unregisterService(attempt) }
        }
    }

    private fun acquireLocks() {
        val wifi = wifiManager ?: return
        runCatching {
            wifiLock = wifi.createWifiLock(
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY,
                "TVPlayAir:LowLatencySession"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
        }.onFailure { Log.w(TAG, "Could not acquire low-latency WifiLock", it) }

        runCatching {
            multicastLock = wifi.createMulticastLock("TVPlayAir:Mdns").apply {
                setReferenceCounted(false)
                acquire()
            }
        }.onFailure { Log.w(TAG, "Could not acquire MulticastLock", it) }
    }

    private fun releaseLocks() {
        runCatching {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        }
        wifiLock = null
        runCatching {
            if (multicastLock?.isHeld == true) multicastLock?.release()
        }
        multicastLock = null
    }
}
