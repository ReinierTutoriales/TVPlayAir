package com.tvplayair.receiver

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.tv.material3.MaterialTheme
import com.tvplayair.receiver.audio.OboeAudioConsumer
import com.tvplayair.receiver.control.DacpCommand
import com.tvplayair.receiver.control.TvRemoteHidBridge
import com.tvplayair.receiver.network.ReceiverIdentityHelper
import com.tvplayair.receiver.network.ReceiverIdentityStore
import com.tvplayair.receiver.session.SessionConnection
import com.tvplayair.receiver.session.RaopNowPlaying
import com.tvplayair.receiver.session.SessionEventListener
import com.tvplayair.receiver.ui.TvOverlayMenu
import com.tvplayair.receiver.ui.TvOverlayState
import com.tvplayair.receiver.video.MirrorDecoder
import com.tvplayair.receiver.video.MirrorVideoConfig
import com.tvplayair.receiver.video.PresentationState
import com.tvplayair.receiver.video.SurfaceLayerController
import com.tvplayair.receiver.video.VideoPresentationCoordinator
import com.tvplayair.receiver.video.WebVideoPlayer

class MainActivity : ComponentActivity(), SurfaceLayerController {
    private lateinit var mirrorSurfaceView: SurfaceView
    private lateinit var directSurfaceView: SurfaceView
    private lateinit var audioConsumer: OboeAudioConsumer
    private lateinit var mirrorDecoder: MirrorDecoder
    private lateinit var webVideoPlayer: WebVideoPlayer
    private lateinit var presentationCoordinator: VideoPresentationCoordinator
    private lateinit var sessionConnection: SessionConnection
    private lateinit var hidBridge: TvRemoteHidBridge
    private lateinit var identityStore: ReceiverIdentityStore

    private var sharedMemoryReady = false
    private var mirrorRequested = false
    private var directPlaybackId = 0L
    private var overlayState by mutableStateOf(TvOverlayState())

    private val mainHandler = Handler(Looper.getMainLooper())
    private val hideVideoControls = Runnable { dismissVideoControls() }
    private val closeOverlayOnBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (overlayState.visible) setOverlayVisible(false) else dismissVideoControls()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        onBackPressedDispatcher.addCallback(this, closeOverlayOnBack)
        identityStore = ReceiverIdentityStore(this)
        val initialIdentity = identityStore.loadOrCreate()
        overlayState = overlayState.copy(
            receiverName = initialIdentity.serviceName,
            projectionHome = intent.hasCategory(Intent.CATEGORY_HOME) ||
                intent.component?.className == "${packageName}.ProjectionHome"
        )
        updateScreenWake()

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
        val overlayComposeView = ComposeView(this).apply {
            setContent {
                MaterialTheme {
                    TvOverlayMenu(
                        state = overlayState,
                        onOpenMenu = { setOverlayVisible(true) },
                        onCycleReceiverName = ::cycleReceiverName,
                        onToggleResolution = ::toggleResolutionPreset,
                        onToggleHevc = ::toggleHevcSetting,
                        onAdjustAudioOffset = ::adjustAudioOffset,
                        onAudioCommand = { sessionConnection.sendDacpCommand(it.wirePath) },
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
                if (::presentationCoordinator.isInitialized &&
                    presentationCoordinator.state == PresentationState.DIRECT_ACTIVE &&
                    overlayState.showingVideoControls) {
                    overlayState = overlayState.copy(videoControls = telemetry)
                }
                if (::presentationCoordinator.isInitialized &&
                    presentationCoordinator.state == PresentationState.DIRECT_ACTIVE &&
                    ::sessionConnection.isInitialized && directPlaybackId != 0L) {
                    sessionConnection.updatePlaybackTelemetry(directPlaybackId, telemetry)
                }
            },
            onPlaybackFinished = {
                if (::sessionConnection.isInitialized) sessionConnection.finishDirectPlayback(directPlaybackId)
                directPlaybackId = 0L
                presentationCoordinator.stopDirectVideo()
                syncPresentationState()
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
                    if (mirrorRequested) {
                        presentationCoordinator.startMirror()
                        syncPresentationState()
                    }
                    if (overlayState.audioProjectionActive) {
                        audioConsumer.start(generationId)
                    }
                }

                override fun onSharedMemoryDetached() {
                    sharedMemoryReady = false
                    mirrorRequested = false
                    directPlaybackId = 0L
                    dismissVideoControls()
                    presentationCoordinator.onSessionDied()
                    overlayState = overlayState.copy(
                        connectedClient = null,
                        audioProjectionActive = false,
                        nowPlaying = null,
                        presentationState = presentationCoordinator.state
                    )
                    updateScreenWake()
                }

                override fun onVideoConfigChanged(
                    mimeType: String,
                    width: Int,
                    height: Int,
                    generationId: Int
                ) {
                    val reconfigured = mirrorDecoder.updateConfig(MirrorVideoConfig(mimeType, width, height))
                    mirrorDecoder.setGeneration(generationId)
                    audioConsumer.setGeneration(generationId)
                    mirrorRequested = true
                    if (sharedMemoryReady) {
                        presentationCoordinator.startMirror(reconfigured)
                    }
                    syncPresentationState()
                }

                override fun onDirectVideoUrl(url: String, startPosition: Double, playbackId: Long) {
                    if (url.isBlank() || !startPosition.isFinite()) return
                    dismissVideoControls()
                    directPlaybackId = playbackId
                    if (!presentationCoordinator.switchToDirectVideo(url, startPosition)) {
                        sessionConnection.finishDirectPlayback(playbackId)
                        directPlaybackId = 0L
                    }
                    syncPresentationState()
                }

                override fun onDirectVideoCommand(command: String, value: Double) {
                    if (directPlaybackId == 0L) return
                    if (command != "stop" &&
                        presentationCoordinator.state != PresentationState.DIRECT_ACTIVE) return
                    when (command) {
                        "play" -> presentationCoordinator.playDirect()
                        "pause" -> presentationCoordinator.pauseDirect()
                        "seek" -> presentationCoordinator.seekDirect(value)
                        "stop" -> {
                            sessionConnection.finishDirectPlayback(directPlaybackId)
                            directPlaybackId = 0L
                            presentationCoordinator.stopDirectVideo()
                        }
                    }
                    syncPresentationState()
                }

                override fun onClientConnected(
                    clientName: String,
                    dacpId: String,
                    activeRemote: String
                ) {
                    overlayState = overlayState.copy(connectedClient = clientName)
                }

                override fun onClientDisconnected() {
                    mirrorRequested = false
                    overlayState = overlayState.copy(
                        connectedClient = null, audioProjectionActive = false, nowPlaying = null
                    )
                    audioConsumer.stop()
                    presentationCoordinator.onMirrorDisconnected()
                    syncPresentationState()
                }

                override fun onAudioStreamStarted(generationId: Int) {
                    overlayState = overlayState.copy(audioProjectionActive = true)
                    updateScreenWake()
                    mirrorDecoder.setGeneration(generationId)
                    audioConsumer.setGeneration(generationId)
                    if (sharedMemoryReady) {
                        audioConsumer.start(generationId)
                    }
                }

                override fun onNowPlaying(metadata: RaopNowPlaying?) {
                    // Metadata is not a transport stop signal: artwork/title may disappear
                    // while the sender keeps its audio stream alive (including on lock).
                    overlayState = overlayState.copy(nowPlaying = metadata)
                    updateScreenWake()
                }
            }
        )
        sessionConnection.connect()
    }

    override fun onStart() {
        super.onStart()
        sessionConnection.setProjectionVisible(true)
    }

    override fun onStop() {
        dismissVideoControls()
        sessionConnection.setProjectionVisible(false)
        super.onStop()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val openDuringMirror = isSelectKey(keyCode) && !overlayState.visible &&
            presentationCoordinator.state == PresentationState.MIRROR_ACTIVE && !hidBridge.isConnected()
        val openFromProjectionHome = isSelectKey(keyCode) && overlayState.projectionHome &&
            !overlayState.visible && overlayState.showStandby && !overlayState.showingAudio
        if (isSettingsKey(keyCode) || openDuringMirror || openFromProjectionHome) {
            if (event?.repeatCount == 0) event.startTracking()
            return true
        }

        if (overlayState.visible) return super.onKeyDown(keyCode, event)

        val inDirectMode = presentationCoordinator.state == PresentationState.DIRECT_ACTIVE
        if (inDirectMode && handleDirectVideoKey(keyCode, event?.repeatCount ?: 0)) return true
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (inDirectMode) {
                    webVideoPlayer.togglePlayback()
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
                    presentationCoordinator.stopDirectVideo()
                    syncPresentationState()
                } else {
                    sessionConnection.sendDacpCommand(DacpCommand.STOP.wirePath)
                }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                if (inDirectMode) return true
                sessionConnection.sendDacpCommand(DacpCommand.NEXT.wirePath)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                if (inDirectMode) return true
                sessionConnection.sendDacpCommand(DacpCommand.PREVIOUS.wirePath)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                if (inDirectMode) {
                    seekVideoBy(10.0)
                } else {
                    sessionConnection.sendDacpCommand(DacpCommand.BEGIN_FF.wirePath)
                }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                if (inDirectMode) {
                    seekVideoBy(-10.0)
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

    private fun handleDirectVideoKey(keyCode: Int, repeatCount: Int): Boolean {
        when {
            isSelectKey(keyCode) -> if (repeatCount == 0) webVideoPlayer.togglePlayback()
            keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> if (repeatCount == 0) seekVideoBy(-10.0)
            keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> if (repeatCount == 0) seekVideoBy(10.0)
            keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN -> Unit
            else -> return false
        }
        mainHandler.removeCallbacks(hideVideoControls)
        overlayState = overlayState.copy(videoControls = presentationCoordinator.directTelemetry())
        closeOverlayOnBack.isEnabled = true
        mainHandler.postDelayed(hideVideoControls, 4_000L)
        return true
    }

    private fun seekVideoBy(deltaSec: Double) {
        val pos = presentationCoordinator.directTelemetry().positionSec
        presentationCoordinator.seekDirect((pos + deltaSec).coerceAtLeast(0.0))
    }

    private fun dismissVideoControls() {
        mainHandler.removeCallbacks(hideVideoControls)
        if (overlayState.videoControls != null) {
            overlayState = overlayState.copy(videoControls = null)
        }
        closeOverlayOnBack.isEnabled = overlayState.visible
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (event?.isTracking == true && (isSettingsKey(keyCode) || isSelectKey(keyCode))) {
            if (!event.isCanceled) {
                setOverlayVisible(!isSettingsKey(keyCode) || !overlayState.visible)
            }
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun isSettingsKey(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_SETTINGS ||
            keyCode == KeyEvent.KEYCODE_INFO

    private fun isSelectKey(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER

    private fun setOverlayVisible(visible: Boolean) {
        if (overlayState.visible == visible) return
        overlayState = overlayState.copy(visible = visible)
        dismissVideoControls()
        updateScreenWake()
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
        if (next == overlayState) return
        overlayState = next
        audioConsumer.setAudioOffsetMs(next.audioOffsetMs)
    }

    private fun syncPresentationState() {
        val state = presentationCoordinator.state
        if (state != PresentationState.DIRECT_ACTIVE && overlayState.videoControls != null) {
            dismissVideoControls()
        }
        if (overlayState.presentationState != state) {
            overlayState = overlayState.copy(presentationState = state)
        }
        updateScreenWake()
    }

    private fun updateScreenWake() {
        val state = overlayState
        val keepScreenOn = !state.projectionHome || state.visible || state.showingAudio || !state.showStandby
        val flag = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        if (keepScreenOn) window.addFlags(flag) else window.clearFlags(flag)
    }

    override fun showMirrorSurface() {
        directSurfaceView.visibility = View.INVISIBLE
        mirrorSurfaceView.visibility = View.VISIBLE
    }

    override fun showDirectSurface() {
        mirrorSurfaceView.visibility = View.INVISIBLE
        directSurfaceView.visibility = View.VISIBLE
    }

    override fun hideAllSurfaces() {
        mirrorSurfaceView.visibility = View.INVISIBLE
        directSurfaceView.visibility = View.INVISIBLE
    }

    private fun surfaceCallback(isDirectSurface: Boolean) = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            if (!isDirectSurface && sharedMemoryReady && mirrorRequested) {
                presentationCoordinator.startMirror()
                syncPresentationState()
            }
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            presentationCoordinator.onSurfaceDestroyed(isDirectSurface)
            syncPresentationState()
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(hideVideoControls)
        hidBridge.release()
        presentationCoordinator.onSessionDied()
        webVideoPlayer.release()
        sessionConnection.close()
        super.onDestroy()
    }
}
