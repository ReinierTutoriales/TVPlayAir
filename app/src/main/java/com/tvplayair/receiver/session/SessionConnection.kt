package com.tvplayair.receiver.session

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import com.tvplayair.receiver.ipc.ITvPlayCallback
import com.tvplayair.receiver.ipc.ITvPlaySession
import com.tvplayair.receiver.video.DirectPlaybackTelemetry

interface SessionEventListener {
    fun onSharedMemoryReady(generationId: Int) = Unit
    fun onSharedMemoryDetached() = Unit
    fun onVideoConfigChanged(mimeType: String, width: Int, height: Int, generationId: Int) = Unit
    fun onDirectVideoUrl(url: String, startPositionSec: Double) = Unit
    fun onDirectVideoCommand(command: String, value: Double) = Unit
    fun onClientConnected(clientName: String, dacpId: String, activeRemote: String) = Unit
    fun onClientDisconnected() = Unit
    fun onAudioStreamStarted(generationId: Int) = Unit
}

class SessionConnection(
    context: Context,
    private val listener: SessionEventListener = object : SessionEventListener {}
) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var service: ITvPlaySession? = null
    private var remoteBinder: IBinder? = null
    private var bound = false
    private var retryDelayMs = 250L
    private var lastGenerationId = 0

    private val callback = object : ITvPlayCallback.Stub() {
        override fun onSharedMemoryReady(
            videoFd: ParcelFileDescriptor,
            videoSize: Long,
            audioFd: ParcelFileDescriptor,
            audioSize: Long,
            generationId: Int
        ) {
            val videoMapped = NativeSharedMemory.nativeMapConsumerBuffer(
                0, videoFd.detachFd(), videoSize
            )
            val audioMapped = NativeSharedMemory.nativeMapConsumerBuffer(
                1, audioFd.detachFd(), audioSize
            )
            if (!videoMapped || !audioMapped) {
                handleBinderDeath()
            } else {
                lastGenerationId = generationId
                handler.removeCallbacks(notifyReady)
                handler.post(notifyReady)
            }
        }

        override fun onSessionUnavailable() = handleBinderDeath()

        override fun onVideoConfigChanged(
            mimeType: String,
            width: Int,
            height: Int,
            generationId: Int
        ) {
            handler.post {
                if (bound) listener.onVideoConfigChanged(mimeType, width, height, generationId)
            }
        }

        override fun onDirectVideoUrl(url: String, startPositionSec: Double) {
            handler.post {
                if (bound) listener.onDirectVideoUrl(url, startPositionSec)
            }
        }

        override fun onDirectVideoCommand(command: String, value: Double) {
            handler.post {
                if (bound) listener.onDirectVideoCommand(command, value)
            }
        }

        override fun onClientConnected(clientName: String, dacpId: String, activeRemote: String) {
            handler.post {
                if (bound) listener.onClientConnected(clientName, dacpId, activeRemote)
            }
        }

        override fun onClientDisconnected() {
            handler.post {
                if (bound) listener.onClientDisconnected()
            }
        }

        override fun onAudioStreamStarted(generationId: Int) {
            handler.post {
                if (bound) listener.onAudioStreamStarted(generationId)
            }
        }
    }

    private val deathRecipient = IBinder.DeathRecipient {
        handler.post { handleBinderDeath() }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder == null) {
                handleBinderDeath()
                return
            }
            try {
                binder.linkToDeath(deathRecipient, 0)
                remoteBinder = binder
                service = ITvPlaySession.Stub.asInterface(binder)
                service?.registerCallback(callback)
                service?.startServer()
                retryDelayMs = 250L
            } catch (_: Exception) {
                handleBinderDeath()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) = handleBinderDeath()
    }

    fun connect() {
        if (bound) return
        runCatching { SessionService.startInBackground(appContext) }
        bound = appContext.bindService(
            Intent(appContext, SessionService::class.java),
            connection,
            Context.BIND_AUTO_CREATE
        )
        if (!bound) scheduleReconnect()
    }

    fun sendDacpCommand(command: String) {
        runCatching { service?.sendDacpCommand(command) }
    }

    fun updatePlaybackTelemetry(telemetry: DirectPlaybackTelemetry) {
        runCatching {
            service?.updatePlaybackTelemetry(
                telemetry.durationSec,
                telemetry.positionSec,
                telemetry.rate,
                telemetry.isPlaying,
                telemetry.isBuffering
            )
        }
    }

    fun configureAdvertising(
        maxWidth: Int,
        maxHeight: Int,
        hevcEnabled: Boolean,
        serviceName: String
    ) {
        runCatching { service?.configureAdvertising(maxWidth, maxHeight, hevcEnabled, serviceName) }
    }

    private fun handleBinderDeath() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { handleBinderDeath() }
            return
        }
        handler.removeCallbacks(notifyReady)
        listener.onSharedMemoryDetached()
        NativeSharedMemory.nativeDetachConsumerBuffers()
        service = null
        remoteBinder?.let { runCatching { it.unlinkToDeath(deathRecipient, 0) } }
        remoteBinder = null
        if (bound) {
            runCatching { appContext.unbindService(connection) }
            bound = false
        }
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        handler.removeCallbacks(reconnect)
        handler.postDelayed(reconnect, retryDelayMs)
        retryDelayMs = (retryDelayMs * 2).coerceAtMost(2000L)
    }

    private val notifyReady = Runnable {
        if (bound) listener.onSharedMemoryReady(lastGenerationId)
    }

    private val reconnect = Runnable { connect() }

    fun close() {
        handler.removeCallbacks(reconnect)
        handler.removeCallbacks(notifyReady)
        runCatching {
            service?.unregisterCallback(callback)
        }
        listener.onSharedMemoryDetached()
        NativeSharedMemory.nativeDetachConsumerBuffers()
        service = null
        remoteBinder?.let { runCatching { it.unlinkToDeath(deathRecipient, 0) } }
        remoteBinder = null
        if (bound) {
            runCatching { appContext.unbindService(connection) }
            bound = false
        }
    }
}
