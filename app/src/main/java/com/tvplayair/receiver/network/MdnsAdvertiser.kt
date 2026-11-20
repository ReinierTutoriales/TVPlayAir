package com.tvplayair.receiver.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import java.util.UUID

data class AirPlayAdvertiseConfig(
    val serviceName: String = "TVPlayAir",
    val deviceId: String = "58:55:CA:1A:E2:88",
    val airPlayPort: Int = 7000,
    val raopPort: Int = 5000,
    val maxWidth: Int = 3840,
    val maxHeight: Int = 2160,
    val hevcEnabled: Boolean = true,
    val piUuid: String = UUID.randomUUID().toString()
)

object MdnsTxtRecords {
    const val AIRPLAY_SERVICE_TYPE = "_airplay._tcp."
    const val RAOP_SERVICE_TYPE = "_raop._tcp."
    const val AIRPLAY_FEATURES = "0x5A7FFFF7,0x1E"
    const val AIRPLAY_FLAGS = "0x44"
    const val AIRPLAY_MODEL = "AppleTV11,1"

    fun buildAirPlayTxt(config: AirPlayAdvertiseConfig): Map<String, String> = linkedMapOf(
        "deviceid" to config.deviceId,
        "features" to AIRPLAY_FEATURES,
        "flags" to AIRPLAY_FLAGS,
        "model" to AIRPLAY_MODEL,
        "overscanned" to "0",
        "pi" to config.piUuid,
        "srcvers" to "220.68",
        "vv" to "2"
    )

    fun buildRaopServiceName(config: AirPlayAdvertiseConfig): String {
        val hexId = config.deviceId.replace(":", "").uppercase()
        return "$hexId@${config.serviceName}"
    }

    fun buildRaopTxt(config: AirPlayAdvertiseConfig): Map<String, String> = linkedMapOf(
        "am" to AIRPLAY_MODEL,
        "ch" to "2",
        "cn" to "0,1,2,3",
        "da" to "true",
        "et" to "0,3,5",
        "md" to "0,1,2",
        "pw" to "false",
        "sf" to AIRPLAY_FLAGS,
        "sr" to "48000",
        "ss" to "16",
        "sv" to "false",
        "tp" to "UDP",
        "vn" to "65537",
        "vs" to "220.68"
    )
}

/**
 * Publishes _airplay._tcp (7000) and _raop._tcp (5000) via Android NsdManager
 * and holds WIFI_MODE_FULL_LOW_LATENCY + MulticastLock inside the :session process.
 */
class MdnsAdvertiser(context: Context) {
    companion object {
        private const val TAG = "MdnsAdvertiser"
    }

    private val appContext = context.applicationContext
    private val nsdManager: NsdManager? = appContext.getSystemService(NsdManager::class.java)
    private val wifiManager: WifiManager? = appContext.getSystemService(WifiManager::class.java)

    private var wifiLock: WifiManager.WifiLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var airPlayListener: NsdManager.RegistrationListener? = null
    private var raopListener: NsdManager.RegistrationListener? = null
    private var started = false

    @Synchronized
    fun start(config: AirPlayAdvertiseConfig) {
        if (started) stop()
        acquireLocks()
        registerService(
            serviceName = config.serviceName,
            serviceType = MdnsTxtRecords.AIRPLAY_SERVICE_TYPE,
            port = config.airPlayPort,
            txt = MdnsTxtRecords.buildAirPlayTxt(config)
        )?.also { airPlayListener = it }

        registerService(
            serviceName = MdnsTxtRecords.buildRaopServiceName(config),
            serviceType = MdnsTxtRecords.RAOP_SERVICE_TYPE,
            port = config.raopPort,
            txt = MdnsTxtRecords.buildRaopTxt(config)
        )?.also { raopListener = it }

        started = true
    }

    @Synchronized
    fun stop() {
        started = false
        unregisterSafe(airPlayListener)
        airPlayListener = null
        unregisterSafe(raopListener)
        raopListener = null
        releaseLocks()
    }

    private fun registerService(
        serviceName: String,
        serviceType: String,
        port: Int,
        txt: Map<String, String>
    ): NsdManager.RegistrationListener? {
        val manager = nsdManager ?: return null
        val info = NsdServiceInfo().apply {
            this.serviceName = serviceName
            this.serviceType = serviceType
            this.port = port
            txt.forEach { (k, v) -> setAttribute(k, v) }
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                Log.i(TAG, "Registered $serviceType as ${serviceInfo.serviceName}:$port")
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Failed to register $serviceType (code=$errorCode)")
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                Log.i(TAG, "Unregistered $serviceType (${serviceInfo.serviceName})")
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "Failed to unregister $serviceType (code=$errorCode)")
            }
        }
        return runCatching {
            manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
            listener
        }.onFailure {
            Log.e(TAG, "Exception registering $serviceType", it)
        }.getOrNull()
    }

    private fun unregisterSafe(listener: NsdManager.RegistrationListener?) {
        val manager = nsdManager ?: return
        if (listener != null) {
            runCatching { manager.unregisterService(listener) }
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
