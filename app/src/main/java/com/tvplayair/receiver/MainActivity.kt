package com.tvplayair.receiver

import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.tv.material3.MaterialTheme
import com.tvplayair.receiver.audio.OboeAudioConsumer
import com.tvplayair.receiver.control.DacpCommand
import com.tvplayair.receiver.control.TvRemoteHidBridge
import com.tvplayair.receiver.network.ReceiverIdentityHelper
import com.tvplayair.receiver.network.ReceiverIdentityStore
import com.tvplayair.receiver.session.SessionConnection
import com.tvplayair.receiver.session.SessionEventListener
import com.tvplayair.receiver.ui.TvOverlayMenu
import com.tvplayair.receiver.ui.TvOverlayState
import com.tvplayair.receiver.video.MirrorDecoder
import com.tvplayair.receiver.video.MirrorVideoConfig
import com.tvplayair.receiver.video.PresentationState
import com.tvplayair.receiver.video.SurfaceLayerController
import com.tvplayair.receiver.video.VideoPresentationCoordinator
import com.tvplayair.receiver.video.WebVideoPlayer
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : ComponentActivity(), SurfaceLayerController {
    private lateinit var mirrorSurfaceView: SurfaceView
    private lateinit var directSurfaceView: SurfaceView
    private lateinit var overlayComposeView: ComposeView
    private lateinit var audioConsumer: OboeAudioConsumer
    private lateinit var mirrorDecoder: MirrorDecoder
    private lateinit var webVideoPlayer: WebVideoPlayer
    private lateinit var presentationCoordinator: VideoPresentationCoordinator
    private lateinit var sessionConnection: SessionConnection
    private lateinit var hidBridge: TvRemoteHidBridge
    private lateinit var identityStore: ReceiverIdentityStore

    private var sharedMemoryReady = false
    private var overlayState by mutableStateOf(TvOverlayState())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        identityStore = ReceiverIdentityStore(this)
        val initialIdentity = identityStore.loadOrCreate()
        overlayState = overlayState.copy(receiverName = initialIdentity.serviceName)

        // Two native SurfaceViews managed by SurfaceFlinger (zero OpenGL ES in v1.0)
        // to preserve direct Hardware Composer HDR10 / HLG / Dolby Vision passthrough.
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        mirrorSurfaceView = SurfaceView(this).apply {
            visibility = View.INVISIBLE
            holder.addCallback(surfaceCallback(isDirectSurface = false))
        }
        directSurfaceView = SurfaceView(this).apply {
            visibility = View.INVISIBLE
            holder.addCallback(surfaceCallback(isDirectSurface = true))
        }
        overlayComposeView = ComposeView(this).apply {
            setContent {
                MaterialTheme {
                    TvOverlayMenu(
                        state = overlayState,
                        onOpenMenu = { setOverlayVisible(true) },
                        onCycleReceiverName = ::cycleReceiverName,
                        onToggleResolution = ::toggleResolutionPreset,
                        onToggleHevc = ::toggleHevcSetting,
                        onAdjustAudioOffset = ::adjustAudioOffset,
                        onDismiss = { setOverlayVisible(false) }
                    )
                }
            }
        }
        val matchParent = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        root.addView(mirrorSurfaceView, matchParent)
        root.addView(directSurfaceView, FrameLayout.LayoutParams(matchParent))
        root.addView(overlayComposeView, FrameLayout.LayoutParams(matchParent))
        setContentView(root)

        audioConsumer = OboeAudioConsumer(this)
        mirrorDecoder = MirrorDecoder { mirrorSurfaceView.holder.surface }
        webVideoPlayer = WebVideoPlayer(
            context = this,
            surfaceView = directSurfaceView,
            onTelemetryChanged = { telemetry ->
                if (::sessionConnection.isInitialized) {
                    sessionConnection.updatePlaybackTelemetry(telemetry)
                }
            }
        )
        presentationCoordinator = VideoPresentationCoordinator(
            mirrorEngine = mirrorDecoder,
            directEngine = webVideoPlayer,
            audioController = audioConsumer,
            surfaceController = this
        )
        hidBridge = TvRemoteHidBridge(this).apply { startIfSupported() }

        sessionConnection = SessionConnection(
            context = this,
            listener = object : SessionEventListener {
                override fun onSharedMemoryReady(generationId: Int) {
                    sharedMemoryReady = true
                    mirrorDecoder.setGeneration(generationId)
                    audioConsumer.setGeneration(generationId)
                    refreshOverlayTelemetry()
                }

                override fun onSharedMemoryDetached() {
                    sharedMemoryReady = false
                    presentationCoordinator.onSessionDied()
                    overlayState = overlayState.copy(
                        connectedClient = null,
                        presentationState = presentationCoordinator.state
                    )
                }

                override fun onVideoConfigChanged(
                    mimeType: String,
                    width: Int,
                    height: Int,
                    generationId: Int
                ) {
                    mirrorDecoder.setGeneration(generationId)
                    audioConsumer.setGeneration(generationId)
                    mirrorDecoder.updateConfig(MirrorVideoConfig(mimeType, width, height))
                    if (mirrorSurfaceView.holder.surface?.isValid == true) {
                        presentationCoordinator.startMirror()
                    }
                    refreshOverlayTelemetry()
                }

                override fun onDirectVideoUrl(url: String, startPosition: Double) {
                    presentationCoordinator.switchToDirectVideo(url, startPosition)
                    refreshOverlayTelemetry()
                }

                override fun onDirectVideoCommand(command: String, value: Double) {
                    when (command) {
                        "play" -> presentationCoordinator.playDirect()
                        "pause" -> presentationCoordinator.pauseDirect()
                        "seek" -> presentationCoordinator.seekDirect(value)
                        "stop" -> presentationCoordinator.switchToMirror()
                    }
                    refreshOverlayTelemetry()
                }

                override fun onClientConnected(
                    clientName: String,
                    dacpId: String,
                    activeRemote: String
                ) {
                    overlayState = overlayState.copy(connectedClient = clientName)
                    if (presentationCoordinator.state != PresentationState.DIRECT_ACTIVE) {
                        audioConsumer.start()
                    }
                    refreshOverlayTelemetry()
                }

                override fun onClientDisconnected() {
                    overlayState = overlayState.copy(connectedClient = null)
                    audioConsumer.stop()
                    refreshOverlayTelemetry()
                }
            }
        )
        sessionConnection.connect()
        refreshOverlayTelemetry()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_MENU ||
                keyCode == KeyEvent.KEYCODE_SETTINGS ||
                keyCode == KeyEvent.KEYCODE_INFO) &&
            (event == null || event.repeatCount == 0)
        ) {
            setOverlayVisible(!overlayState.visible)
            return true
        }

        if (overlayState.visible) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                setOverlayVisible(false)
                return true
            }
            return super.onKeyDown(keyCode, event)
        }

        if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) &&
            (event == null || event.repeatCount == 0) &&
            presentationCoordinator.state != PresentationState.IDLE &&
            !hidBridge.isConnected()
        ) {
            setOverlayVisible(true)
            return true
        }

        val inDirectMode = presentationCoordinator.state == PresentationState.DIRECT_ACTIVE
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (inDirectMode) {
                    if (presentationCoordinator.directTelemetry().isPlaying) {
                        presentationCoordinator.pauseDirect()
                    } else {
                        presentationCoordinator.playDirect()
                    }
                } else {
                    sessionConnection.sendDacpCommand(DacpCommand.PLAY_PAUSE.wirePath)
                }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                if (inDirectMode) {
                    presentationCoordinator.playDirect()
                } else {
                    sessionConnection.sendDacpCommand(DacpCommand.PLAY.wirePath)
                }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (inDirectMode) {
                    presentationCoordinator.pauseDirect()
                } else {
                    sessionConnection.sendDacpCommand(DacpCommand.PAUSE.wirePath)
                }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_STOP -> {
                if (inDirectMode) {
                    presentationCoordinator.switchToMirror()
                } else {
                    sessionConnection.sendDacpCommand(DacpCommand.STOP.wirePath)
                }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                sessionConnection.sendDacpCommand(DacpCommand.NEXT.wirePath)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                sessionConnection.sendDacpCommand(DacpCommand.PREVIOUS.wirePath)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                if (inDirectMode) {
                    val pos = presentationCoordinator.directTelemetry().positionSec
                    presentationCoordinator.seekDirect(pos + 10.0)
                } else {
                    sessionConnection.sendDacpCommand(DacpCommand.BEGIN_FF.wirePath)
                }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                if (inDirectMode) {
                    val pos = presentationCoordinator.directTelemetry().positionSec
                    presentationCoordinator.seekDirect((pos - 10.0).coerceAtLeast(0.0))
                } else {
                    sessionConnection.sendDacpCommand(DacpCommand.BEGIN_REW.wirePath)
                }
                return true
            }
        }

        if (presentationCoordinator.state == PresentationState.MIRROR_ACTIVE &&
            hidBridge.isConnected() &&
            hidBridge.sendAndroidKey(keyCode)
        ) {
            return true
        }

        return super.onKeyDown(keyCode, event)
    }

    private fun setOverlayVisible(visible: Boolean) {
        if (visible) refreshOverlayTelemetry()
        overlayState = overlayState.copy(visible = visible)
        if (visible) {
            overlayComposeView.bringToFront()
        }
    }

    private fun cycleReceiverName() {
        val nextName = ReceiverIdentityHelper.nextReceiverName(
            current = overlayState.receiverName,
            systemDeviceName = identityStore.readSystemDeviceName()
        )
        val saved = identityStore.saveServiceName(nextName)
        overlayState = overlayState.copy(receiverName = saved)
        sessionConnection.configureAdvertising(
            overlayState.resolutionPreset.width,
            overlayState.resolutionPreset.height,
            overlayState.hevcEnabled,
            saved
        )
    }

    private fun toggleResolutionPreset() {
        val next = overlayState.toggleResolution()
        overlayState = next
        sessionConnection.configureAdvertising(
            next.resolutionPreset.width,
            next.resolutionPreset.height,
            next.hevcEnabled,
            next.receiverName
        )
    }

    private fun toggleHevcSetting() {
        val next = overlayState.toggleHevc()
        overlayState = next
        sessionConnection.configureAdvertising(
            next.resolutionPreset.width,
            next.resolutionPreset.height,
            next.hevcEnabled,
            next.receiverName
        )
    }

    private fun adjustAudioOffset(deltaMs: Int) {
        val next = overlayState.stepAudioOffset(deltaMs)
        overlayState = next
        audioConsumer.setAudioOffsetMs(next.audioOffsetMs)
    }

    private fun refreshOverlayTelemetry() {
        overlayState = overlayState.copy(
            presentationState = presentationCoordinator.state,
            oboeExclusive = audioConsumer.isExclusiveMode(),
            oboeSampleRate = audioConsumer.getGrantedSampleRate().takeIf { it > 0 } ?: 48000,
            oboeUnderruns = audioConsumer.getUnderrunCount(),
            oboeRecoveries = audioConsumer.getRecoveryCount(),
            mirrorDroppedNonIdr = mirrorDecoder.frameGate.droppedNonIdrFrames,
            hidAvailable = hidBridge.isAvailable(),
            localIpAddress = resolveLocalIpv4()
        )
    }

    private fun resolveLocalIpv4(): String = runCatching {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return "No network"
        for (iface in interfaces) {
            if (!iface.isUp || iface.isLoopback) continue
            for (addr in iface.inetAddresses) {
                if (addr is Inet4Address && !addr.isLoopbackAddress) {
                    return addr.hostAddress ?: continue
                }
            }
        }
        "No network"
    }.getOrDefault("No network")

    override fun showMirrorSurface() {
        directSurfaceView.visibility = View.INVISIBLE
        mirrorSurfaceView.visibility = View.VISIBLE
        mirrorSurfaceView.bringToFront()
        if (overlayState.visible) overlayComposeView.bringToFront()
    }

    override fun showDirectSurface() {
        mirrorSurfaceView.visibility = View.INVISIBLE
        directSurfaceView.visibility = View.VISIBLE
        directSurfaceView.bringToFront()
        if (overlayState.visible) overlayComposeView.bringToFront()
    }

    override fun hideAllSurfaces() {
        mirrorSurfaceView.visibility = View.INVISIBLE
        directSurfaceView.visibility = View.INVISIBLE
    }

    private fun surfaceCallback(isDirectSurface: Boolean) = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            if (!isDirectSurface && sharedMemoryReady && overlayState.connectedClient != null) {
                presentationCoordinator.startMirror()
                refreshOverlayTelemetry()
            }
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            presentationCoordinator.onSurfaceDestroyed(isDirectSurface)
            refreshOverlayTelemetry()
        }
    }

    override fun onDestroy() {
        hidBridge.release()
        presentationCoordinator.onSessionDied()
        webVideoPlayer.release()
        sessionConnection.close()
        super.onDestroy()
    }
}
