package com.tvplayair.receiver.session

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import com.tvplayair.receiver.MainActivity
import com.tvplayair.receiver.control.DacpController
import com.tvplayair.receiver.ipc.ITvPlayCallback
import com.tvplayair.receiver.ipc.ITvPlaySession
import com.tvplayair.receiver.network.AirPlayAdvertiseConfig
import com.tvplayair.receiver.network.MdnsAdvertiser
import com.tvplayair.receiver.network.ReceiverIdentityHelper
import com.tvplayair.receiver.network.ReceiverIdentityStore
import com.tvplayair.receiver.video.DirectPlaybackTelemetry

class SessionService : Service() {
    companion object {
        private const val TAG = "SessionService"
        private const val CHANNEL_ID = "tvplayair_session"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_START_BACKGROUND = "com.tvplayair.receiver.action.START_BACKGROUND"

        fun startInBackground(context: Context) {
            val intent = Intent(context.applicationContext, SessionService::class.java).apply {
                action = ACTION_START_BACKGROUND
            }
            context.applicationContext.startForegroundService(intent)
        }
    }

    private var videoFd: ParcelFileDescriptor? = null
    private var audioFd: ParcelFileDescriptor? = null

    @Volatile
    private var callback: ITvPlayCallback? = null
    @Volatile
    private var pendingDirectUrl: Pair<String, Double>? = null

    private lateinit var identityStore: ReceiverIdentityStore
    private lateinit var mdnsAdvertiser: MdnsAdvertiser
    private lateinit var dacpController: DacpController
    private lateinit var protocolEngine: AirPlayProtocolEngine

    private var advertiseConfig = AirPlayAdvertiseConfig()
    private var ed25519PrivateKey: ByteArray? = null
    private var serverRunning = false

    private val protocolListener = object : AirPlayProtocolListener {
        override fun onVideoConfigChanged(
            mimeType: String,
            width: Int,
            height: Int,
            generationId: Int
        ) {
            ensureUiForeground()
            runCatching { callback?.onVideoConfigChanged(mimeType, width, height, generationId) }
        }

        override fun onDirectVideoUrl(url: String, startPosition: Double) {
            val cb = callback
            if (cb == null) {
                pendingDirectUrl = url to startPosition
                ensureUiForeground()
            } else {
                pendingDirectUrl = null
                runCatching { cb.onDirectVideoUrl(url, startPosition) }
            }
        }

        override fun onDirectVideoCommand(command: String, value: Double) {
            runCatching { callback?.onDirectVideoCommand(command, value) }
        }

        override fun onClientConnected(clientName: String, dacpId: String, activeRemote: String) {
            dacpController.onClientConnected(dacpId, activeRemote)
            ensureUiForeground()
            runCatching { callback?.onClientConnected(clientName, dacpId, activeRemote) }
        }

        override fun onClientDisconnected() {
            dacpController.onClientDisconnected()
            runCatching { callback?.onClientDisconnected() }
        }

        override fun onAudioStreamStarted(generationId: Int) {
            ensureUiForeground()
            runCatching { callback?.onAudioStreamStarted(generationId) }
        }
    }

    private val binder = object : ITvPlaySession.Stub() {
        override fun registerCallback(cb: ITvPlayCallback) {
            callback = cb
            val video = ensureBuffer(0)
            val audio = ensureBuffer(1)
            if (video == null || audio == null) {
                cb.onSessionUnavailable()
                return
            }
            val videoCopy = ParcelFileDescriptor.dup(video.fileDescriptor)
            try {
                val audioCopy = ParcelFileDescriptor.dup(audio.fileDescriptor)
                try {
                    cb.onSharedMemoryReady(
                        videoCopy,
                        16L * 1024 * 1024,
                        audioCopy,
                        512L * 1024,
                        protocolEngine.currentGenerationId()
                    )
                } finally {
                    audioCopy.close()
                }
            } finally {
                videoCopy.close()
            }
            pendingDirectUrl?.let { (url, pos) ->
                pendingDirectUrl = null
                runCatching { cb.onDirectVideoUrl(url, pos) }
            }
        }

        override fun unregisterCallback(cb: ITvPlayCallback) {
            if (callback?.asBinder() == cb.asBinder()) {
                callback = null
            }
        }

        override fun startServer() {
            startAirPlaySession()
        }

        override fun stopServer() {
            stopAirPlaySession()
        }

        override fun updatePlaybackTelemetry(
            durationSec: Double,
            positionSec: Double,
            rate: Float,
            isPlaying: Boolean,
            isBuffering: Boolean
        ) {
            protocolEngine.updatePlaybackTelemetry(
                DirectPlaybackTelemetry(durationSec, positionSec, rate, isPlaying, isBuffering)
            )
        }

        override fun sendDacpCommand(command: String) {
            dacpController.sendCommand(command)
        }

        override fun configureAdvertising(
            maxWidth: Int,
            maxHeight: Int,
            hevcEnabled: Boolean,
            serviceName: String
        ) {
            val cleanName = if (serviceName.isNotBlank()) {
                identityStore.saveServiceName(serviceName)
            } else {
                advertiseConfig.serviceName
            }
            advertiseConfig = advertiseConfig.copy(
                serviceName = ReceiverIdentityHelper.sanitizeServiceName(cleanName),
                maxWidth = maxWidth,
                maxHeight = maxHeight,
                hevcEnabled = hevcEnabled
            )
            if (serverRunning) {
                mdnsAdvertiser.start(advertiseConfig)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        identityStore = ReceiverIdentityStore(this)
        val identity = identityStore.loadOrCreate()
        ed25519PrivateKey = identity.ed25519PrivateKey
        advertiseConfig = advertiseConfig.copy(
            serviceName = identity.serviceName,
            deviceId = identity.deviceId,
            piUuid = identity.piUuid,
            publicKeyHex = identity.ed25519PublicKeyHex
        )
        mdnsAdvertiser = MdnsAdvertiser(this)
        dacpController = DacpController(this)
        protocolEngine = AirPlayDirectHttpServer(protocolListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promoteToForeground()
        startAirPlaySession()
        return START_STICKY
    }

    private fun promoteToForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "TVPlayAir Background Receiver",
            NotificationManager.IMPORTANCE_LOW
        )
        manager?.createNotificationChannel(channel)

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(advertiseConfig.serviceName)
            .setContentText("AirPlay receiver active in background")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }.onFailure {
            Log.w(TAG, "Could not promote SessionService to foreground", it)
        }
    }

    private fun ensureUiForeground() {
        if (callback != null) return
        runCatching {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                )
            }
            startActivity(intent)
        }.onFailure {
            Log.w(TAG, "Unable to auto-launch MainActivity from background", it)
        }
    }

    @Synchronized
    private fun startAirPlaySession() {
        if (serverRunning) return
        val started = protocolEngine.start(
            AirPlayServerConfig(
                airPlayPort = advertiseConfig.airPlayPort,
                maxWidth = advertiseConfig.maxWidth,
                maxHeight = advertiseConfig.maxHeight,
                hevcEnabled = advertiseConfig.hevcEnabled,
                deviceId = advertiseConfig.deviceId,
                ed25519PrivateKey = ed25519PrivateKey
            )
        )
        if (started) {
            mdnsAdvertiser.start(advertiseConfig)
            serverRunning = true
        }
    }

    @Synchronized
    private fun stopAirPlaySession() {
        serverRunning = false
        mdnsAdvertiser.stop()
        protocolEngine.stop()
        dacpController.onClientDisconnected()
    }

    private fun ensureBuffer(kind: Int): ParcelFileDescriptor? {
        val current = if (kind == 0) videoFd else audioFd
        if (current != null) return current
        val rawFd = NativeSharedMemory.nativeCreateProducerBuffer(kind)
        if (rawFd < 0) return null
        val descriptor = ParcelFileDescriptor.adoptFd(rawFd)
        if (kind == 0) videoFd = descriptor else audioFd = descriptor
        return descriptor
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        stopAirPlaySession()
        dacpController.release()
        callback = null
        videoFd?.close()
        audioFd?.close()
        NativeSharedMemory.nativeDestroyProducerBuffers()
        super.onDestroy()
    }
}
