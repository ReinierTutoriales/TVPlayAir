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

class SessionConnection(context: Context) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var service: ITvPlaySession? = null
    private var remoteBinder: IBinder? = null
    private var bound = false
    private var retryDelayMs = 250L

    private val callback = object : ITvPlayCallback.Stub() {
        override fun onSharedMemoryReady(
            videoFd: ParcelFileDescriptor,
            videoSize: Long,
            audioFd: ParcelFileDescriptor,
            audioSize: Long
        ) {
            val videoMapped = NativeSharedMemory.nativeMapConsumerBuffer(
                0, videoFd.detachFd(), videoSize
            )
            val audioMapped = NativeSharedMemory.nativeMapConsumerBuffer(
                1, audioFd.detachFd(), audioSize
            )
            if (!videoMapped || !audioMapped) handleBinderDeath()
        }

        override fun onSessionUnavailable() = handleBinderDeath()
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
                retryDelayMs = 250L
            } catch (_: Exception) {
                handleBinderDeath()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) = handleBinderDeath()
    }

    fun connect() {
        if (bound) return
        bound = appContext.bindService(
            Intent(appContext, SessionService::class.java),
            connection,
            Context.BIND_AUTO_CREATE
        )
        if (!bound) scheduleReconnect()
    }

    private fun handleBinderDeath() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { handleBinderDeath() }
            return
        }
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

    private val reconnect = Runnable { connect() }

    fun close() {
        handler.removeCallbacks(reconnect)
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
