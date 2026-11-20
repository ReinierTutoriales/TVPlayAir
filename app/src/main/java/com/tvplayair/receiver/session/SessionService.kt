package com.tvplayair.receiver.session

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.tvplayair.receiver.control.DacpController
import com.tvplayair.receiver.ipc.ITvPlayCallback
import com.tvplayair.receiver.ipc.ITvPlaySession
import com.tvplayair.receiver.network.AirPlayAdvertiseConfig
import com.tvplayair.receiver.network.MdnsAdvertiser
import com.tvplayair.receiver.video.DirectPlaybackTelemetry

class SessionService : Service() {
    private var videoFd: ParcelFileDescriptor? = null
    private var audioFd: ParcelFileDescriptor? = null

    @Volatile
    private var callback: ITvPlayCallback? = null

    private lateinit var mdnsAdvertiser: MdnsAdvertiser
    private lateinit var dacpController: DacpController
    private lateinit var protocolEngine: AirPlayProtocolEngine

    private var advertiseConfig = AirPlayAdvertiseConfig()
    private var serverRunning = false

    private val protocolListener = object : AirPlayProtocolListener {
        override fun onVideoConfigChanged(
            mimeType: String,
            width: Int,
            height: Int,
            generationId: Int
        ) {
            runCatching { callback?.onVideoConfigChanged(mimeType, width, height, generationId) }
        }

        override fun onDirectVideoUrl(url: String, startPositionSec: Double) {
            runCatching { callback?.onDirectVideoUrl(url, startPositionSec) }
        }

        override fun onDirectVideoCommand(command: String, value: Double) {
            runCatching { callback?.onDirectVideoCommand(command, value) }
        }

        override fun onClientConnected(clientName: String, dacpId: String, activeRemote: String) {
            dacpController.onClientConnected(dacpId, activeRemote)
            runCatching { callback?.onClientConnected(clientName, dacpId, activeRemote) }
        }

        override fun onClientDisconnected() {
            dacpController.onClientDisconnected()
            runCatching { callback?.onClientDisconnected() }
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

        override fun configureAdvertising(maxWidth: Int, maxHeight: Int, hevcEnabled: Boolean) {
            advertiseConfig = advertiseConfig.copy(
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
        mdnsAdvertiser = MdnsAdvertiser(this)
        dacpController = DacpController(this)
        protocolEngine = AirPlayDirectHttpServer(protocolListener)
    }

    @Synchronized
    private fun startAirPlaySession() {
        if (serverRunning) return
        val started = protocolEngine.start(
            AirPlayServerConfig(
                airPlayPort = advertiseConfig.airPlayPort,
                maxWidth = advertiseConfig.maxWidth,
                maxHeight = advertiseConfig.maxHeight,
                hevcEnabled = advertiseConfig.hevcEnabled
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
