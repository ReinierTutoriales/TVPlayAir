package com.tvplayair.receiver.session

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import com.tvplayair.receiver.MainActivity
import com.tvplayair.receiver.control.DacpController
import com.tvplayair.receiver.control.DacpRequestBuilder
import com.tvplayair.receiver.diagnostics.PLAYBACK_DIAGNOSTICS_TAG
import com.tvplayair.receiver.ipc.ITvPlayCallback
import com.tvplayair.receiver.ipc.ITvPlaySession
import com.tvplayair.receiver.network.AirPlayAdvertiseConfig
import com.tvplayair.receiver.network.MdnsAdvertiser
import com.tvplayair.receiver.network.ReceiverIdentityHelper
import com.tvplayair.receiver.network.ReceiverIdentityStore
import com.tvplayair.receiver.video.DirectPlaybackStart
import com.tvplayair.receiver.video.NO_DIRECT_CHECKPOINT_MS
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

    private data class MirrorConfig(
        val mimeType: String,
        val width: Int,
        val height: Int,
        val generationId: Int,
        val codecConfig: ByteArray
    ) {
        fun sendTo(callback: ITvPlayCallback, opensProjection: Boolean = false) =
            callback.onVideoConfigChanged(mimeType, width, height, generationId, codecConfig, opensProjection)
    }

    private val callbackLock = Any()
    private var latestMirrorConfig: MirrorConfig? = null
    private var latestNowPlaying: RaopNowPlaying? = null
    private var activeAudioGeneration: Int? = null
    @Volatile
    private var projectionVisible = false

    @Volatile
    private var callback: ITvPlayCallback? = null
    private var directPlayback: DirectPlaybackStart? = null

    private fun ITvPlayCallback.showDirectVideo(video: DirectPlaybackStart) {
        onDirectVideoUrl(
            video.url, video.airPlayPosition, video.playbackId,
            video.resumePositionMs ?: NO_DIRECT_CHECKPOINT_MS, video.playWhenReady
        )
    }

    private lateinit var identityStore: ReceiverIdentityStore
    private lateinit var mdnsAdvertiser: MdnsAdvertiser
    private val mainHandler = Handler(Looper.getMainLooper())
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
            generationId: Int,
            codecConfig: ByteArray,
            opensProjection: Boolean
        ) {
            synchronized(callbackLock) {
                val config = MirrorConfig(mimeType, width, height, generationId, codecConfig)
                latestMirrorConfig = config
                if (opensProjection) directPlayback = null
                runCatching { callback?.let { config.sendTo(it, opensProjection) } }
            }
            ensureUiForeground()
        }

        override fun onDirectVideoUrl(url: String, startPosition: Double, playbackId: Long) {
            synchronized(callbackLock) {
                // Retain the active source for Activity recreation, even if a UI is bound.
                val video = DirectPlaybackStart(url, startPosition, playbackId)
                directPlayback = video
                runCatching { callback?.showDirectVideo(video) }
            }
            ensureUiForeground()
        }

        override fun onDirectVideoCommand(command: String, value: Double, playbackId: Long) {
            synchronized(callbackLock) {
                if (directPlayback?.playbackId != playbackId) return
                when (command) {
                    "stop" -> directPlayback = null
                    "pause", "play" -> directPlayback = directPlayback?.withPlaybackIntent(command)
                }
                runCatching { callback?.onDirectVideoCommand(command, value, playbackId) }
            }
        }

        override fun onClientConnected(clientName: String, dacpId: String, activeRemote: String) {
            synchronized(callbackLock) {
                if (latestNowPlaying?.remote == null) {
                    dacpController.onClientConnected(dacpId, activeRemote)
                    runCatching { callback?.onClientConnected(clientName, dacpId, activeRemote) }
                }
            }
        }

        override fun onMirrorStreamEnded() {
            synchronized(callbackLock) {
                latestMirrorConfig = null
                runCatching { callback?.onMirrorStreamEnded() }
            }
        }

        override fun onNowPlaying(metadata: RaopNowPlaying?) {
            synchronized(callbackLock) {
                val remote = metadata?.remote
                if (remote != latestNowPlaying?.remote ||
                    (metadata != null) != (latestNowPlaying != null)
                ) {
                    if (remote == null || DacpRequestBuilder.expectedServiceName(remote.dacpId) == null) {
                        dacpController.onClientDisconnected()
                    } else {
                        dacpController.onClientConnected(remote.dacpId, remote.activeRemote)
                    }
                }
                latestNowPlaying = metadata
                // Metadata can be absent while RAOP audio is still streaming.
                // Only transport lifecycle events may reset activeAudioGeneration.
                callback?.let { sendNowPlaying(it, metadata) }
            }
        }

        override fun onAudioStreamStopped() {
            synchronized(callbackLock) {
                activeAudioGeneration?.let {
                    Log.i(PLAYBACK_DIAGNOSTICS_TAG, "route=raop generation=$it event=stopped")
                }
                activeAudioGeneration = null
                runCatching { callback?.onAudioStreamStopped() }
            }
        }

        override fun onAudioStreamStarted(generationId: Int) {
            synchronized(callbackLock) {
                if (activeAudioGeneration == generationId) return
                activeAudioGeneration = generationId
                Log.i(PLAYBACK_DIAGNOSTICS_TAG, "route=raop generation=$generationId event=active")
                runCatching { callback?.onAudioStreamStarted(generationId) }
            }
            ensureUiForeground()
        }
    }

    private val binder = object : ITvPlaySession.Stub() {
        override fun registerCallback(cb: ITvPlayCallback) {
            val video = ensureBuffer(0)
            val audio = ensureBuffer(1)
            if (video == null || audio == null) {
                cb.onSessionUnavailable()
                return
            }
            synchronized(callbackLock) {
                val videoCopy = ParcelFileDescriptor.dup(video.fileDescriptor)
                try {
                    val audioCopy = ParcelFileDescriptor.dup(audio.fileDescriptor)
                    try {
                        cb.onSharedMemoryReady(
                            videoCopy,
                            16L * 1024 * 1024,
                            audioCopy,
                            512L * 1024,
                            protocolEngine.currentGenerationId(),
                            directPlayback?.playbackId ?: 0L
                        )
                    } finally {
                        audioCopy.close()
                    }
                } finally {
                    videoCopy.close()
                }
                callback = cb
                activeAudioGeneration?.let { runCatching { cb.onAudioStreamStarted(it) } }
                sendNowPlaying(cb, latestNowPlaying)
                sendAudioControlsAvailability(cb)
                latestMirrorConfig?.let { config -> runCatching { config.sendTo(cb) } }
                directPlayback?.let { video -> runCatching { cb.showDirectVideo(video) } }
            }
        }

        override fun unregisterCallback(cb: ITvPlayCallback) {
            synchronized(callbackLock) {
                if (callback?.asBinder() == cb.asBinder()) {
                    callback = null
                    projectionVisible = false
                }
            }
        }

        override fun setProjectionVisible(visible: Boolean) {
            projectionVisible = visible
        }

        override fun startServer() {
            startAirPlaySession()
        }

        override fun updatePlaybackTelemetry(
            source: ITvPlayCallback,
            playbackId: Long,
            resumePositionMs: Long,
            durationSec: Double,
            positionSec: Double,
            rate: Float,
            isPlaying: Boolean,
            isBuffering: Boolean,
            isReady: Boolean
        ) {
            synchronized(callbackLock) {
                val active = directPlayback ?: return
                if (callback?.asBinder() != source.asBinder() || active.playbackId != playbackId) return
                directPlayback = active.withCheckpoint(resumePositionMs)
            }
            protocolEngine.updatePlaybackTelemetry(
                playbackId,
                DirectPlaybackTelemetry(durationSec, positionSec, rate, isPlaying, isBuffering, isReady)
            )
        }

        override fun setDirectPlaybackIntent(
            source: ITvPlayCallback, playbackId: Long, command: String
        ) {
            synchronized(callbackLock) {
                val active = directPlayback ?: return
                if (callback?.asBinder() != source.asBinder() || active.playbackId != playbackId) return
                val updated = active.withPlaybackIntent(command)
                if (updated === active) return
                directPlayback = updated
                // Deliver in the same order as /rate commands from the iPhone.
                val action = if (updated.playWhenReady) "play" else "pause"
                runCatching { callback?.onDirectVideoCommand(action, 0.0, playbackId) }
            }
        }

        override fun finishDirectPlayback(source: ITvPlayCallback, playbackId: Long) {
            synchronized(callbackLock) {
                if (callback?.asBinder() != source.asBinder()) return
                if (directPlayback?.playbackId == playbackId) directPlayback = null
            }
            protocolEngine.finishDirectPlayback(playbackId)
        }

        override fun sendDacpCommand(source: ITvPlayCallback, audioGeneration: Int, command: String) {
            val owner = source.asBinder()
            val controlStillCurrent = {
                synchronized(callbackLock) {
                    callback?.asBinder() == owner &&
                        activeAudioGeneration == audioGeneration && directPlayback == null
                }
            }
            synchronized(callbackLock) {
                if (controlStillCurrent()) dacpController.sendCommand(command, controlStillCurrent)
            }
        }

        override fun configureAdvertising(
            maxWidth: Int,
            maxHeight: Int,
            hevcEnabled: Boolean,
            serviceName: String
        ) {
            synchronized(this@SessionService) {
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
                    protocolEngine.start(
                        AirPlayServerConfig(
                            airPlayPort = advertiseConfig.airPlayPort,
                            maxWidth = advertiseConfig.maxWidth,
                            maxHeight = advertiseConfig.maxHeight,
                            hevcEnabled = advertiseConfig.hevcEnabled,
                            deviceId = advertiseConfig.deviceId,
                            ed25519PrivateKey = ed25519PrivateKey
                        )
                    )
                    mdnsAdvertiser.start(advertiseConfig)
                }
            }
        }
    }

    private var connectivity: ConnectivityManager? = null
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.i(PLAYBACK_DIAGNOSTICS_TAG, "route=network event=default_available handle=${network.networkHandle}")
        }

        override fun onLost(network: Network) {
            Log.i(PLAYBACK_DIAGNOSTICS_TAG, "route=network event=default_lost handle=${network.networkHandle}")
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
        dacpController = DacpController(this) {
            mainHandler.post {
                synchronized(callbackLock) {
                    callback?.let(::sendAudioControlsAvailability)
                }
            }
        }
        protocolEngine = AirPlayDirectHttpServer(protocolListener)
        getSystemService(ConnectivityManager::class.java)?.let { manager ->
            try {
                manager.registerDefaultNetworkCallback(networkCallback)
                connectivity = manager
            } catch (error: RuntimeException) {
                Log.w(PLAYBACK_DIAGNOSTICS_TAG,
                    "route=network event=observer_unavailable error=${error.javaClass.simpleName}")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promoteToForeground()
        startAirPlaySession()
        return START_STICKY
    }

    private fun sendAudioControlsAvailability(target: ITvPlayCallback) {
        runCatching { target.onAudioControlsAvailable(dacpController.canSendCommands()) }
    }

    private fun sendNowPlaying(target: ITvPlayCallback, metadata: RaopNowPlaying?) {
        runCatching {
            target.onNowPlaying(
                metadata != null,
                metadata?.track?.title.orEmpty(),
                metadata?.track?.artist.orEmpty(),
                metadata?.track?.album.orEmpty(),
                metadata?.artwork ?: ByteArray(0)
            )
        }
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
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0, projectionIntent(), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
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
        if (projectionVisible) return
        runCatching {
            startActivity(projectionIntent())
        }.onFailure {
            Log.w(TAG, "Unable to auto-launch MainActivity from background", it)
        }
    }

    private fun projectionIntent() = Intent(this, MainActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
    }

    @Synchronized
    private fun startAirPlaySession() {
        if (serverRunning) return
        if (ensureBuffer(0) == null || ensureBuffer(1) == null) {
            Log.e(TAG, "AirPlay cannot start without video and audio shared memory")
            return
        }
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
        synchronized(callbackLock) {
            latestMirrorConfig = null
            latestNowPlaying = null
            activeAudioGeneration = null
            directPlayback = null
        }
        dacpController.onClientDisconnected()
    }

    @Synchronized
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
        connectivity?.unregisterNetworkCallback(networkCallback)
        connectivity = null
        stopAirPlaySession()
        dacpController.release()
        callback = null
        videoFd?.close()
        audioFd?.close()
        NativeSharedMemory.nativeDestroyProducerBuffers()
        super.onDestroy()
    }
}
