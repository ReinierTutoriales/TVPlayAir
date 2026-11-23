package com.tvplayair.receiver.control

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

enum class DacpCommand(val wirePath: String) {
    PLAY("play"),
    PAUSE("pause"),
    PLAY_PAUSE("playpause"),
    NEXT("nextitem"),
    PREVIOUS("previtem"),
    VOLUME_UP("volumeup"),
    VOLUME_DOWN("volumedown"),
    STOP("stop"),
    BEGIN_FF("beginff"),
    BEGIN_REW("beginrew"),
    PLAY_RESUME("playresume");

    companion object {
        fun fromWireName(name: String): DacpCommand? =
            entries.firstOrNull {
                it.wirePath.equals(name, ignoreCase = true) ||
                    it.name.equals(name, ignoreCase = true)
            }
    }
}

data class DacpHttpRequest(
    val url: String,
    val activeRemoteHeader: String
)

object DacpRequestBuilder {
    const val DACP_SERVICE_TYPE = "_dacp._tcp."

    fun expectedServiceName(dacpId: String): String? {
        val normalized = dacpId.trim()
        if (normalized.isEmpty()) return null
        return "iTunes_Ctrl_$normalized"
    }

    fun buildRequest(
        host: String,
        port: Int,
        activeRemote: String,
        command: DacpCommand
    ): DacpHttpRequest? {
        val cleanHost = host.trim()
        val cleanToken = activeRemote.trim()
        if (cleanHost.isEmpty() || port !in 1..65535 || cleanToken.isEmpty()) return null
        val formattedHost = if (cleanHost.contains(':') && !cleanHost.startsWith("[")) {
            "[$cleanHost]"
        } else {
            cleanHost
        }
        return DacpHttpRequest(
            url = "http://$formattedHost:$port/ctrl-int/1/${command.wirePath}",
            activeRemoteHeader = cleanToken
        )
    }
}

/**
 * Mandatory bidirectional DACP remote controller.
 * Resolves the Apple device's _dacp._tcp service (iTunes_Ctrl_<DACP-ID>) and sends
 * HTTP GET /ctrl-int/1/<command> with the Active-Remote header.
 */
class DacpController(
    context: Context,
    private val onAvailabilityChanged: () -> Unit = {}
) {
    companion object {
        private const val TAG = "DacpController"
        private const val CONNECT_TIMEOUT_MS = 2_000
        private const val READ_TIMEOUT_MS = 2_000
    }

    private val nsdManager: NsdManager? =
        context.applicationContext.getSystemService(NsdManager::class.java)
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "TVPlayAir-DACP").apply { isDaemon = true }
    }

    @Volatile
    private var targetServiceName: String? = null
    @Volatile
    private var activeRemoteToken: String? = null
    @Volatile
    private var resolvedHost: String? = null
    @Volatile
    private var resolvedPort: Int = -1

    private var discoveryListener: NsdManager.DiscoveryListener? = null
    @Volatile
    private var targetGeneration = 0L

    @Synchronized
    fun onClientConnected(dacpId: String, activeRemote: String) {
        val expectedName = DacpRequestBuilder.expectedServiceName(dacpId) ?: return
        if (activeRemote.isBlank()) return
        if (expectedName == targetServiceName && activeRemote == activeRemoteToken) return

        targetGeneration++
        stopDiscovery()
        targetServiceName = expectedName
        activeRemoteToken = activeRemote
        setResolvedEndpoint(null, -1)
        startDiscovery()
    }

    @Synchronized
    fun onClientDisconnected() {
        targetGeneration++
        stopDiscovery()
        targetServiceName = null
        activeRemoteToken = null
        setResolvedEndpoint(null, -1)
    }

    @Synchronized
    fun sendCommand(commandName: String, stillCurrent: () -> Boolean): Boolean {
        val command = DacpCommand.fromWireName(commandName) ?: return false
        val host = resolvedHost ?: return false
        val port = resolvedPort
        val token = activeRemoteToken ?: return false
        val request = DacpRequestBuilder.buildRequest(host, port, token, command) ?: return false
        val generation = targetGeneration

        executor.execute {
            if (!stillCurrent() || generation != targetGeneration) return@execute
            var connection: HttpURLConnection? = null
            runCatching {
                connection = (URL(request.url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    setRequestProperty("Active-Remote", request.activeRemoteHeader)
                    setRequestProperty("Viewer-Only-Client", "1")
                }
                val code = connection!!.responseCode
                Log.i(TAG, "DACP ${command.wirePath} -> HTTP $code")
            }.onFailure {
                Log.w(TAG, "DACP command ${command.wirePath} failed", it)
            }
            connection?.disconnect()
        }
        return true
    }

    fun release() {
        onClientDisconnected()
        executor.shutdownNow()
    }

    private fun startDiscovery() {
        val manager = nsdManager ?: return
        val generation = targetGeneration
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "DACP discovery start failed: $errorCode")
                synchronized(this@DacpController) {
                    if (generation == targetGeneration) stopDiscovery()
                }
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                val expected = targetServiceName ?: return
                if (generation != targetGeneration || !serviceInfo.serviceName.equals(expected, ignoreCase = true)) return
                resolveService(manager, serviceInfo, generation)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                synchronized(this@DacpController) {
                    if (generation == targetGeneration && serviceInfo.serviceName.equals(targetServiceName, true)) {
                        setResolvedEndpoint(null, -1)
                    }
                }
            }
        }
        runCatching {
            manager.discoverServices(
                DacpRequestBuilder.DACP_SERVICE_TYPE,
                NsdManager.PROTOCOL_DNS_SD,
                listener
            )
            discoveryListener = listener
        }.onFailure {
            Log.w(TAG, "Failed to start DACP discovery", it)
        }
    }

    @Suppress("DEPRECATION")
    private fun resolveService(manager: NsdManager, serviceInfo: NsdServiceInfo, generation: Long) {
        runCatching {
            manager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "DACP resolve failed for ${info.serviceName}: $errorCode")
                }

                override fun onServiceResolved(info: NsdServiceInfo) {
                    val addr: InetAddress = info.host ?: return
                    synchronized(this@DacpController) {
                        if (generation != targetGeneration) return
                        setResolvedEndpoint(addr.hostAddress, info.port)
                    }
                    Log.i(
                        TAG,
                        String.format(
                            Locale.US,
                            "Resolved DACP endpoint %s -> %s:%d",
                            info.serviceName,
                            resolvedHost,
                            resolvedPort
                        )
                    )
                }
            })
        }
    }

    @Synchronized
    fun canSendCommands(): Boolean = resolvedHost != null && resolvedPort in 1..65535

    private fun setResolvedEndpoint(host: String?, port: Int) {
        val available = canSendCommands()
        resolvedHost = host
        resolvedPort = port
        if (available != canSendCommands()) onAvailabilityChanged()
    }

    private fun stopDiscovery() {
        val manager = nsdManager ?: return
        val listener = discoveryListener ?: return
        discoveryListener = null
        runCatching { manager.stopServiceDiscovery(listener) }
    }
}
