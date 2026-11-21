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
import com.tvplayair.receiver.video.DirectPlaybackStart
import com.tvplayair.receiver.video.DirectPlaybackTelemetry
import com.tvplayair.receiver.video.NO_DIRECT_CHECKPOINT_MS

interface SessionEventListener {
    fun onSharedMemoryReady(generationId: Int, activeDirectPlaybackId: Long) = Unit
    fun onSharedMemoryDetached() = Unit
    fun onVideoConfigChanged(mimeType: String, width: Int, height: Int, generationId: Int) = Unit
    fun onDirectVideoUrl(video: DirectPlaybackStart) = Unit
    fun onDirectVideoCommand(command: String, value: Double, playbackId: Long) = Unit
    fun onClientConnected(clientName: String, dacpId: String, activeRemote: String) = Unit
    fun onClientDisconnected() = Unit
    fun onAudioStreamStarted(generationId: Int) = Unit
    fun onNowPlaying(metadata: RaopNowPlaying?) = Unit
}

class SessionConnection(
    context: Context,
    private var listener: SessionEventListener = object : SessionEventListener {}
) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var service: ITvPlaySession? = null
    var remoteBinder: IBinder? = null
        private set
    private var activeCallback: ITvPlayCallback? = null
    private var activeDeathRecipient: IBinder.DeathRecipient? = null
    private var activeConnection: ServiceConnection? = null
    private var bound = false
    private var closed = false
    private var bindingEpoch = 0L
    private var retryDelayMs = 250L
    private var projectionVisible = false

    private fun isCurrent(epoch: Long): Boolean =
        !closed && bound && bindingEpoch == epoch

    private fun deliver(epoch: Long, action: () -> Unit) {
        handler.post { if (isCurrent(epoch)) action() }
    }

    private fun callbackFor(epoch: Long): ITvPlayCallback = object : ITvPlayCallback.Stub() {
        override fun onSharedMemoryReady(
            videoFd: ParcelFileDescriptor,
            videoSize: Long,
            audioFd: ParcelFileDescriptor,
            audioSize: Long,
            generationId: Int,
            activeDirectPlaybackId: Long
        ) {
            // The service closes its ParcelFileDescriptor copies after the Binder call.
            // Take ownership before posting; close both if the connection becomes stale.
            val videoRawFd = videoFd.detachFd()
            val audioRawFd = audioFd.detachFd()
            handler.post {
                if (!isCurrent(epoch)) {
                    ParcelFileDescriptor.adoptFd(videoRawFd).close()
                    ParcelFileDescriptor.adoptFd(audioRawFd).close()
                    return@post
                }
                // Mapping and unmapping are serialized on the main thread.
                val videoMapped = NativeSharedMemory.nativeMapConsumerBuffer(
                    0, videoRawFd, videoSize
                )
                val audioMapped = NativeSharedMemory.nativeMapConsumerBuffer(
                    1, audioRawFd, audioSize
                )
                if (!videoMapped || !audioMapped) {
                    handleBinderDeath(epoch)
                } else {
                    listener.onSharedMemoryReady(generationId, activeDirectPlaybackId)
                }
            }
        }

        override fun onSessionUnavailable() = deliver(epoch) { handleBinderDeath(epoch) }

        override fun onVideoConfigChanged(
            mimeType: String, width: Int, height: Int, generationId: Int
        ) = deliver(epoch) {
            listener.onVideoConfigChanged(mimeType, width, height, generationId)
        }

        override fun onDirectVideoUrl(
            url: String, startPosition: Double, playbackId: Long, resumePositionMs: Long, playWhenReady: Boolean
        ) = deliver(epoch) {
            listener.onDirectVideoUrl(
                DirectPlaybackStart(url, startPosition, playbackId, resumePositionMs.takeIf { it >= 0L }, playWhenReady)
            )
        }

        override fun onDirectVideoCommand(command: String, value: Double, playbackId: Long) =
            deliver(epoch) { listener.onDirectVideoCommand(command, value, playbackId) }

        override fun onClientConnected(clientName: String, dacpId: String, activeRemote: String) =
            deliver(epoch) { listener.onClientConnected(clientName, dacpId, activeRemote) }

        override fun onClientDisconnected() =
            deliver(epoch) { listener.onClientDisconnected() }

        override fun onAudioStreamStarted(generationId: Int) =
            deliver(epoch) { listener.onAudioStreamStarted(generationId) }

        override fun onNowPlaying(
            available: Boolean, title: String, artist: String, album: String, artwork: ByteArray
        ) {
            val metadata = if (available) RaopNowPlaying(
                RaopTrack(title.ifBlank { null }, artist.ifBlank { null }, album.ifBlank { null }),
                artwork.takeIf { it.isNotEmpty() }
            ) else null
            deliver(epoch) { listener.onNowPlaying(metadata) }
        }
    }

    private fun connectionFor(epoch: Long): ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (!isCurrent(epoch)) return
            if (binder == null) {
                handleBinderDeath(epoch)
                return
            }
            val recipient = IBinder.DeathRecipient {
                handler.post { handleBinderDeath(epoch) }
            }
            try {
                binder.linkToDeath(recipient, 0)
                if (!isCurrent(epoch)) {
                    runCatching { binder.unlinkToDeath(recipient, 0) }
                    return
                }
                activeDeathRecipient = recipient
                remoteBinder = binder
                service = ITvPlaySession.Stub.asInterface(binder)
                val callback = callbackFor(epoch)
                activeCallback = callback
                service?.registerCallback(callback)
                service?.setProjectionVisible(projectionVisible)
                service?.startServer()
                retryDelayMs = 250L
            } catch (_: Exception) {
                handleBinderDeath(epoch)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) = handleBinderDeath(epoch)
    }

    internal fun replaceListener(listener: SessionEventListener, replay: Boolean = false) {
        this.listener = listener
        if (!replay) return
        val callback = activeCallback ?: return
        runCatching { service?.registerCallback(callback) }
            .onFailure { handleBinderDeath(bindingEpoch) }
    }

    fun connect() {
        if (closed || bound) return
        handler.removeCallbacks(reconnect)
        runCatching { SessionService.startInBackground(appContext) }
        val epoch = ++bindingEpoch
        val connection = connectionFor(epoch)
        activeConnection = connection
        bound = runCatching {
            appContext.bindService(
                Intent(appContext, SessionService::class.java),
                connection,
                Context.BIND_AUTO_CREATE
            )
        }.getOrDefault(false)
        if (!bound) {
            activeConnection = null
            scheduleReconnect()
        }
    }

    fun finishDirectPlayback(playbackId: Long) {
        val source = activeCallback ?: return
        if (playbackId != 0L) runCatching { service?.finishDirectPlayback(source, playbackId) }
    }

    fun sendDacpCommand(command: String) {
        runCatching { service?.sendDacpCommand(command) }
    }

    fun setProjectionVisible(visible: Boolean) {
        projectionVisible = visible
        runCatching { service?.setProjectionVisible(visible) }
    }

    fun setDirectPlaybackIntent(playbackId: Long, command: String) {
        val source = activeCallback ?: return
        runCatching { service?.setDirectPlaybackIntent(source, playbackId, command) }
    }

    fun updatePlaybackTelemetry(playbackId: Long, telemetry: DirectPlaybackTelemetry, positionMs: Long?) {
        val source = activeCallback ?: return
        runCatching {
            service?.updatePlaybackTelemetry(
                source, playbackId, positionMs ?: NO_DIRECT_CHECKPOINT_MS,
                telemetry.durationSec, telemetry.positionSec,
                telemetry.rate, telemetry.isPlaying, telemetry.isBuffering, telemetry.isReady
            )
        }
    }

    fun configureAdvertising(
        maxWidth: Int, maxHeight: Int, hevcEnabled: Boolean, serviceName: String
    ) {
        runCatching { service?.configureAdvertising(maxWidth, maxHeight, hevcEnabled, serviceName) }
    }

    private fun handleBinderDeath(epoch: Long) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { handleBinderDeath(epoch) }
            return
        }
        if (!isCurrent(epoch)) return
        bindingEpoch++
        detach()
        scheduleReconnect()
    }

    private fun detach() {
        val oldService = service
        val oldCallback = activeCallback
        service = null
        activeCallback = null
        if (oldCallback != null) runCatching { oldService?.unregisterCallback(oldCallback) }
        val binder = remoteBinder
        val recipient = activeDeathRecipient
        remoteBinder = null
        activeDeathRecipient = null
        if (binder != null && recipient != null) {
            runCatching { binder.unlinkToDeath(recipient, 0) }
        }
        val connection = activeConnection
        activeConnection = null
        if (bound && connection != null) {
            runCatching { appContext.unbindService(connection) }
        }
        bound = false
        listener.onSharedMemoryDetached()
        NativeSharedMemory.nativeDetachConsumerBuffers()
    }

    private fun scheduleReconnect() {
        if (closed) return
        handler.removeCallbacks(reconnect)
        handler.postDelayed(reconnect, retryDelayMs)
        retryDelayMs = (retryDelayMs * 2).coerceAtMost(2000L)
    }

    private val reconnect = Runnable { connect() }

    fun close() {
        if (closed) return
        closed = true
        bindingEpoch++
        handler.removeCallbacks(reconnect)
        detach()
    }
}
