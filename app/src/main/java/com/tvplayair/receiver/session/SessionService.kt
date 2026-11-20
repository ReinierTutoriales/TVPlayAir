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
            val video = ensureBuffer(0)
            val audio = ensureBuffer(1)
            if (video == null || audio == null) {
                callback.onSessionUnavailable()
                return
            }
            val videoCopy = ParcelFileDescriptor.dup(video.fileDescriptor)
            try {
                val audioCopy = ParcelFileDescriptor.dup(audio.fileDescriptor)
                try {
                    callback.onSharedMemoryReady(
                        videoCopy, 16L * 1024 * 1024, audioCopy, 512L * 1024
                    )
                } finally {
                    audioCopy.close()
                }
            } finally {
                videoCopy.close()
            }
        }

        override fun unregisterCallback(callback: ITvPlayCallback) = Unit
        override fun startServer() = Unit
        override fun stopServer() = Unit
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
        videoFd?.close()
        audioFd?.close()
        NativeSharedMemory.nativeDestroyProducerBuffers()
        super.onDestroy()
    }
}
