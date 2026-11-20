package com.tvplayair.receiver.session

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.tvplayair.receiver.ipc.ITvPlayCallback
import com.tvplayair.receiver.ipc.ITvPlaySession

class SessionService : Service() {
    private var videoFd: ParcelFileDescriptor? = null
    private var audioFd: ParcelFileDescriptor? = null

    private val binder = object : ITvPlaySession.Stub() {
        override fun registerCallback(callback: ITvPlayCallback) {
            val video = ensureBuffer(0, 16L * 1024 * 1024)
            val audio = ensureBuffer(1, 512L * 1024)
            if (video == null || audio == null) {
                callback.onSessionUnavailable()
                return
            }
            callback.onSharedMemoryReady(
                ParcelFileDescriptor.dup(video.fileDescriptor), 16L * 1024 * 1024,
                ParcelFileDescriptor.dup(audio.fileDescriptor), 512L * 1024
            )
        }

        override fun unregisterCallback(callback: ITvPlayCallback) = Unit
        override fun startServer() = Unit
        override fun stopServer() = Unit
    }

    private fun ensureBuffer(kind: Int, size: Long): ParcelFileDescriptor? {
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
        videoFd?.close()
        audioFd?.close()
        NativeSharedMemory.nativeDestroyProducerBuffers()
        super.onDestroy()
    }
}
