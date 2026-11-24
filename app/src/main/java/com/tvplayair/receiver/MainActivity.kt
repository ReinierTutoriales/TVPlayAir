package com.tvplayair.receiver

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.tv.material3.MaterialTheme
import com.tvplayair.receiver.diagnostics.PLAYBACK_DIAGNOSTICS_TAG
import com.tvplayair.receiver.audio.AudioContinuityService
import com.tvplayair.receiver.audio.OboeAudioConsumer
import com.tvplayair.receiver.control.DacpCommand
import com.tvplayair.receiver.control.TvRemoteHidBridge
import com.tvplayair.receiver.network.ReceiverIdentityHelper
import com.tvplayair.receiver.network.ReceiverIdentityStore
import com.tvplayair.receiver.session.BootReceiver
import com.tvplayair.receiver.session.SessionConnection
import com.tvplayair.receiver.session.RaopNowPlaying
import com.tvplayair.receiver.session.SessionEventListener
import com.tvplayair.receiver.ui.TvOverlayMenu
import com.tvplayair.receiver.ui.TvOverlayState
import com.tvplayair.receiver.video.DIRECT_STARTUP_TIMEOUT_MS
import com.tvplayair.receiver.video.DirectPlaybackTelemetry
import com.tvplayair.receiver.video.DirectPlaybackStart
import com.tvplayair.receiver.video.DirectPlaybackService
import com.tvplayair.receiver.video.DirectVideoViewModel
import com.tvplayair.receiver.video.MirrorDecoder
import com.tvplayair.receiver.video.MirrorVideoConfig
import com.tvplayair.receiver.video.PresentationState
import com.tvplayair.receiver.video.SurfaceLayerController
import com.tvplayair.receiver.video.VideoPresentationCoordinator
import com.tvplayair.receiver.video.WebVideoPlayer

class MainActivity : ComponentActivity(), SurfaceLayerController {
    private lateinit var mirrorSurfaceView: SurfaceView
    private lateinit var directSurfaceView: SurfaceView
    private lateinit var videoStartupCover: View
    private lateinit var audioConsumer: OboeAudioConsumer
    private lateinit var mirrorDecoder: MirrorDecoder
    private val directVideoModel: DirectVideoViewModel by viewModels()
    private val webVideoPlayer: WebVideoPlayer get() = directVideoModel.player
    private lateinit var presentationCoordinator: VideoPresentationCoordinator
    private lateinit var sessionConnection: SessionConnection
    private lateinit var sessionListener: SessionEventListener
    private var audioHandedOff = false
    private lateinit var hidBridge: TvRemoteHidBridge
    private lateinit var identityStore: ReceiverIdentityStore

    private var sharedMemoryReady = false
    private var awaitingDirectSnapshot = false
    private var destroyingView = false
    private var screenStarted = false
    private var mirrorRequested = false
    private var mirrorGeneration: Int? = null
    private var directAspectRatio: Double? = null
    private var mirrorAspectRatio: Double? = null
    private var directPlaybackId = 0L
    private var directStartupTimeout: Runnable? = null
    private var mirrorSurfaceTimeout: Runnable? = null
    private var directRequestedAtMs = 0L
    private var overlayState by mutableStateOf(TvOverlayState())

    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingVideoSelect: Pair<Long, Long>? = null
    private val hidePlaybackControls = Runnable { dismissPlaybackControls() }
    private val closeOverlayOnBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            when {
                overlayState.visible -> setOverlayVisible(false)
                overlayState.showingVideoControls || overlayState.showingMirrorControls -> dismissPlaybackControls()
                presentationCoordinator.state == PresentationState.MIRROR_ACTIVE ||
                    presentationCoordinator.state == PresentationState.MIRROR_WAITING_KEYFRAME -> {
                    finishMirrorPresentation()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        onBackPressedDispatcher.addCallback(this, closeOverlayOnBack)
        val retainedAudio = AudioContinuityService.claim()
        audioConsumer = retainedAudio?.second ?: OboeAudioConsumer(applicationContext)
        identityStore = ReceiverIdentityStore(this)
        val initialIdentity = identityStore.loadOrCreate()
        overlayState = overlayState.copy(
            receiverName = initialIdentity.serviceName,
            audioOffsetMs = audioConsumer.getAudioOffsetMs(),
            projectionHome = isProjectionHomeIntent(intent),
            autoStartReceiver = BootReceiver.isAutoStartEnabled(this),
            autoOpenVisual = getSharedPreferences("receiver_presentation", MODE_PRIVATE)
                .getBoolean("auto_open_visual", false)
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
        videoStartupCover = View(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val overlayComposeView = ComposeView(this).apply {
            setContent {
                MaterialTheme {
                    val audioGeneration = overlayState.audioGeneration
                    TvOverlayMenu(
                        state = overlayState,
                        onOpenMenu = { setOverlayVisible(true) },
                        onCycleReceiverName = ::cycleReceiverName,
                        onToggleResolution = ::toggleResolutionPreset,
                        onToggleHevc = ::toggleHevcSetting,
                        onToggleAutoOpenVisual = ::toggleAutoOpenVisual,
                        onToggleAutoStartReceiver = ::toggleAutoStartReceiver,
                        onAdjustAudioOffset = ::adjustAudioOffset,
                        onAudioCommand = { command -> sendAudioCommand(command, audioGeneration) },
                        onVideoToggle = { toggleVideoFromControls(directPlaybackId) },
                        onSeekTo = ::seekVideoTo,
                        onPlaybackInteraction = ::refreshPlaybackControlsTimeout,
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
        root.addView(videoStartupCover, FrameLayout.LayoutParams(matchParent))
        root.addView(overlayComposeView, FrameLayout.LayoutParams(matchParent))
        root.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                updateDirectSurfaceAspectRatio()
                fitSurface(mirrorSurfaceView, mirrorAspectRatio)
            }
        }
        setContentView(root)

        val retainedPlayback = DirectPlaybackService.claim()
        retainedPlayback?.let(directVideoModel::adopt)
        mirrorDecoder = MirrorDecoder(
            surfaceProvider = { mirrorSurfaceView.holder.surface },
            audioClockOffsetNs = audioConsumer::senderClockOffsetNs,
            onVideoSizeChanged = ::updateMirrorSurfaceAspectRatio,
            onFirstFrameRendered = {
                if (!destroyingView && presentationCoordinator.onMirrorFrameRendered()) syncPresentationState()
            },
            onRecovering = {
                if (!destroyingView && presentationCoordinator.onMirrorRecovering()) syncPresentationState()
            },
            onWaitingForKeyFrame = {
                if (!destroyingView && presentationCoordinator.onMirrorWaitingForKeyFrame()) {
                    syncPresentationState()
                }
            }
        )
        webVideoPlayer.attachView(directSurfaceView, object : WebVideoPlayer.ViewCallbacks {
            override fun onTelemetryChanged(telemetry: DirectPlaybackTelemetry) {
                if (::presentationCoordinator.isInitialized &&
                    presentationCoordinator.state == PresentationState.DIRECT_ACTIVE) {
                    val previousIntent = overlayState.videoControls?.playWhenReady
                    overlayState = overlayState.withDirectTelemetry(telemetry)
                    if (previousIntent != overlayState.videoControls?.playWhenReady) refreshPlaybackControlsTimeout()
                }
                if (::presentationCoordinator.isInitialized &&
                    canReportDirectPlayback() &&
                    ::sessionConnection.isInitialized && directPlaybackId > 0L) {
                    sessionConnection.updatePlaybackTelemetry(
                        directPlaybackId, telemetry, webVideoPlayer.checkpointPositionMs()
                    )
                }
            }

            override fun onVideoAspectChanged(playbackId: Long, aspectRatio: Double) {
                if (directPlaybackId != playbackId) return
                directAspectRatio = aspectRatio
                updateDirectSurfaceAspectRatio()
            }

            override fun onMediaReady(playbackId: Long) {
                if (presentationCoordinator.onDirectMediaReady(playbackId)) {
                    val elapsedMs = SystemClock.elapsedRealtime() - directRequestedAtMs
                    Log.i("TVPlayAir", "direct_startup outcome=ready playbackId=$playbackId elapsedMs=$elapsedMs")
                    syncPresentationState()
                    if (!overlayState.visible && !overlayState.showingDirectAudio) showVideoControls()
                    webVideoPlayer.exposeSystemControls(playbackId)
                    if (screenStarted) publishDirectPlayback()
                    saveDirectCheckpoint()
                }
            }

            override fun onPlaybackFinished(playbackId: Long) {
                if (presentationCoordinator.finishDirectPlayback(playbackId)) {
                    finishDirectSession(playbackId)
                }
            }

            override fun onSystemPlaybackIntent(playbackId: Long, playWhenReady: Boolean) {
                if (directPlaybackId == playbackId) {
                    setDirectPlaybackIntent(if (playWhenReady) "play" else "pause")
                }
            }
        })
        presentationCoordinator = VideoPresentationCoordinator(
            mirrorEngine = mirrorDecoder,
            directEngine = webVideoPlayer,
            audioController = audioConsumer,
            surfaceController = this
        )
        hidBridge = TvRemoteHidBridge(this).apply { startIfSupported() }

        sessionListener = object : SessionEventListener {
            override fun onSharedMemoryReady(generationId: Int, activeDirectPlaybackId: Long) {
                sharedMemoryReady = true
                awaitingDirectSnapshot = activeDirectPlaybackId != 0L
                val retainedId = webVideoPlayer.playbackId
                val binder = sessionConnection.remoteBinder
                if (retainedId != null &&
                    (retainedId != activeDirectPlaybackId || binder != directVideoModel.sessionBinder)
                ) webVideoPlayer.stopAndClear()
                directVideoModel.sessionBinder = binder
                if (awaitingDirectSnapshot) audioConsumer.setPausedForExternalPlayer(true)
                audioConsumer.setGeneration(generationId)
                if (mirrorRequested && !awaitingDirectSnapshot) {
                    presentationCoordinator.startMirror()
                    syncPresentationState()
                }
                if (overlayState.audioProjectionActive) {
                    audioConsumer.start(generationId)
                }
            }

            override fun onSharedMemoryDetached() {
                if (destroyingView) return
                sharedMemoryReady = false
                directVideoModel.sessionBinder = null
                awaitingDirectSnapshot = false
                mirrorRequested = false
                directPlaybackId = 0L
                cancelDirectStartupTimeout()
                dismissPlaybackControls()
                presentationCoordinator.onSessionDied()
                overlayState = overlayState.withAudioStreamStopped().withPresentation(presentationCoordinator.state)
                    .copy(connectedClient = null)
                updateScreenWake()
            }

            override fun onVideoConfigChanged(
                mimeType: String,
                width: Int,
                height: Int,
                generationId: Int,
                codecConfig: ByteArray,
                opensProjection: Boolean
            ) {
                updateMirrorSurfaceAspectRatio(width, height)
                val reconfigured = mirrorDecoder.updateConfig(
                    MirrorVideoConfig(mimeType, width, height), generationId, codecConfig
                )
                mirrorGeneration = generationId
                mirrorRequested = true
                if (opensProjection && awaitingDirectSnapshot) {
                    awaitingDirectSnapshot = false
                }
                if (sharedMemoryReady && !awaitingDirectSnapshot) {
                    presentationCoordinator.startMirror(reconfigured, opensProjection)
                    if (!presentationCoordinator.hasDirectPlayback && directPlaybackId != 0L) {
                        finishDirectSession(directPlaybackId)
                    }
                }
                syncPresentationState()
            }

            override fun onDirectVideoUrl(video: DirectPlaybackStart) {
                val restoringMirror = awaitingDirectSnapshot && mirrorRequested
                awaitingDirectSnapshot = false
                startDirectVideo(video, restoringMirror)
            }

            override fun onDirectVideoCommand(command: String, value: Double, playbackId: Long) {
                if (directPlaybackId == 0L || directPlaybackId != playbackId) return
                if (!presentationCoordinator.hasDirectPlayback) return
                when (command) {
                    "play" -> presentationCoordinator.playDirect()
                    "pause" -> presentationCoordinator.pauseDirect()
                    "seek" -> presentationCoordinator.seekDirect(value)
                    "stop" -> {
                        if (presentationCoordinator.finishDirectPlayback(playbackId, senderStop = true)) {
                            finishDirectSession(playbackId)
                        }
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

            override fun onMirrorStreamEnded(generationId: Int) {
                if (mirrorGeneration == generationId) finishMirrorPresentation()
            }

            override fun onAudioStreamStarted(generationId: Int) {
                overlayState = overlayState.withAudioStreamStarted(generationId)
                updateScreenWake()
                audioConsumer.setGeneration(generationId)
                if (sharedMemoryReady) {
                    audioConsumer.start(generationId)
                }
            }

            override fun onAudioStreamStopped(generationId: Int) {
                if (generationId != 0 && overlayState.audioGeneration != null &&
                    overlayState.audioGeneration != generationId
                ) return
                audioConsumer.stop()
                overlayState = overlayState.withAudioStreamStopped(generationId).let { state ->
                    if (!mirrorRequested && !presentationCoordinator.hasDirectPlayback) {
                        state.copy(connectedClient = null)
                    } else {
                        state
                    }
                }
                updateScreenWake()
            }

            override fun onAudioControlsAvailable(available: Boolean) {
                overlayState = overlayState.copy(audioControlsAvailable = available)
            }

            override fun onNowPlaying(metadata: RaopNowPlaying?, generationId: Int) {
                // Metadata is not a transport stop signal: artwork/title may disappear
                // while the sender keeps its audio stream alive (including on lock).
                overlayState = overlayState.withNowPlaying(metadata, generationId)
                updateScreenWake()
            }
        }
        sessionConnection = retainedPlayback?.connection ?: retainedAudio?.first ?: SessionConnection(this)
        sessionConnection.replaceListener(sessionListener, replay = retainedPlayback != null)
        sessionConnection.connect()
        sessionConnection.setAutoOpenVisual(overlayState.autoOpenVisual)
    }

    override fun onStart() {
        super.onStart()
        if (audioHandedOff) {
            AudioContinuityService.claim()
            audioHandedOff = false
            sessionConnection.replaceListener(sessionListener, replay = true)
        }
        screenStarted = true
        sessionConnection.setProjectionVisible(true)
        if (directPlaybackId != 0L) publishDirectPlayback()
    }

    override fun onStop() {
        saveDirectCheckpoint()
        screenStarted = false
        dismissPlaybackControls()
        sessionConnection.setProjectionVisible(false)
        if (overlayState.audioProjectionActive && directPlaybackId == 0L) {
            audioHandedOff = AudioContinuityService.retain(
                this, sessionConnection, audioConsumer, overlayState.audioGeneration
            )
        }
        super.onStop()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val openDuringMirror = isSelectKey(keyCode) && !overlayState.visible &&
            presentationCoordinator.state == PresentationState.MIRROR_ACTIVE &&
            !overlayState.showingMirrorControls && !hidBridge.isConnected()
        val openFromProjectionHome = isSelectKey(keyCode) && overlayState.projectionHome &&
            !overlayState.visible && overlayState.showStandby && !overlayState.showingAudio
        if (isSettingsKey(keyCode) || openDuringMirror || openFromProjectionHome) {
            if (event?.repeatCount == 0) event.startTracking()
            return true
        }

        if (overlayState.visible) return super.onKeyDown(keyCode, event)

        if (presentationCoordinator.state == PresentationState.MIRROR_ACTIVE && !hidBridge.isConnected() &&
            (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN)
        ) {
            overlayState = overlayState.copy(mirrorControlsVisible = true)
            refreshPlaybackControlsTimeout()
            return true
        }

        val inDirectMode = presentationCoordinator.hasDirectPlayback
        if (presentationCoordinator.state == PresentationState.DIRECT_ACTIVE &&
            !overlayState.showingDirectControls &&
            handleHiddenVideoControlsKey(keyCode, event)
        ) return true
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (inDirectMode) {
                    if (event == null || event.repeatCount == 0) {
                        setDirectPlaybackIntent("toggle")
                    }
                } else {
                    sendAudioCommand(DacpCommand.PLAY_PAUSE, overlayState.audioGeneration)
                }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                if (inDirectMode) {
                    setDirectPlaybackIntent("play")
                } else {
                    sendAudioCommand(DacpCommand.PLAY, overlayState.audioGeneration)
                }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (inDirectMode) {
                    setDirectPlaybackIntent("pause")
                } else {
                    sendAudioCommand(DacpCommand.PAUSE, overlayState.audioGeneration)
                }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_STOP -> {
                if (inDirectMode) {
                    if (presentationCoordinator.finishDirectPlayback(directPlaybackId)) {
                        finishDirectSession(directPlaybackId)
                    }
                } else {
                    sendAudioCommand(DacpCommand.STOP, overlayState.audioGeneration)
                }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                if (inDirectMode) return true
                sendAudioCommand(DacpCommand.NEXT, overlayState.audioGeneration)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                if (inDirectMode) return true
                sendAudioCommand(DacpCommand.PREVIOUS, overlayState.audioGeneration)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                if (inDirectMode) {
                    if (presentationCoordinator.state == PresentationState.DIRECT_ACTIVE) showVideoControls()
                } else {
                    sendAudioCommand(DacpCommand.BEGIN_FF, overlayState.audioGeneration)
                }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                if (inDirectMode) {
                    if (presentationCoordinator.state == PresentationState.DIRECT_ACTIVE) showVideoControls()
                } else {
                    sendAudioCommand(DacpCommand.BEGIN_REW, overlayState.audioGeneration)
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

    private fun handleHiddenVideoControlsKey(keyCode: Int, event: KeyEvent?): Boolean {
        when {
            isSelectKey(keyCode) -> {
                if (event?.repeatCount == 0) {
                    event.startTracking()
                    pendingVideoSelect = event.downTime to directPlaybackId
                }
                return true
            }
            keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
                keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN -> Unit
            else -> return false
        }
        showVideoControls()
        return true
    }

    private fun showVideoControls() {
        overlayState = overlayState.copy(videoControls = presentationCoordinator.directTelemetry())
        closeOverlayOnBack.isEnabled = true
        refreshPlaybackControlsTimeout()
    }

    private fun refreshPlaybackControlsTimeout() {
        if (!overlayState.showingVideoControls && !overlayState.showingMirrorControls) return
        mainHandler.removeCallbacks(hidePlaybackControls)
        if (overlayState.showingMirrorControls || overlayState.videoControls?.playWhenReady == true) {
            mainHandler.postDelayed(hidePlaybackControls, 5_000L)
        }
    }

    private fun sendAudioCommand(command: DacpCommand, generation: Int?) {
        if (generation == null || !overlayState.canSendAudioControl(generation)) return
        sessionConnection.sendDacpCommand(generation, command.wirePath)
        if (overlayState.showingMirrorControls) refreshPlaybackControlsTimeout()
    }

    private fun toggleVideoFromControls(playbackId: Long) {
        if (directPlaybackId != playbackId || !overlayState.showingDirectControls) return
        setDirectPlaybackIntent("toggle")
        refreshPlaybackControlsTimeout()
    }

    private fun seekVideoTo(positionSec: Double) {
        val telemetry = presentationCoordinator.directTelemetry()
        if (!telemetry.canSeek || !positionSec.isFinite() ||
            !telemetry.durationSec.isFinite() || telemetry.durationSec <= 0.0
        ) return
        presentationCoordinator.seekDirect(positionSec.coerceIn(0.0, telemetry.durationSec))
    }

    private fun dismissPlaybackControls() {
        mainHandler.removeCallbacks(hidePlaybackControls)
        if (overlayState.videoControls != null || overlayState.mirrorControlsVisible) {
            overlayState = overlayState.copy(videoControls = null, mirrorControlsVisible = false)
        }
        closeOverlayOnBack.isEnabled = overlayState.visible ||
            (::presentationCoordinator.isInitialized &&
                presentationCoordinator.state == PresentationState.MIRROR_ACTIVE)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        val pendingSelect = pendingVideoSelect
        if (event != null && isSelectKey(keyCode) && pendingSelect?.first == event.downTime) {
            pendingVideoSelect = null
            if (!event.isCanceled && !overlayState.visible && pendingSelect.second == directPlaybackId &&
                presentationCoordinator.state == PresentationState.DIRECT_ACTIVE
            ) {
                setDirectPlaybackIntent("toggle")
                showVideoControls()
            }
            return true
        }
        if (event?.isTracking == true && (isSettingsKey(keyCode) || isSelectKey(keyCode))) {
            if (!event.isCanceled) {
                val canOpenWithSelect = presentationCoordinator.state == PresentationState.MIRROR_ACTIVE ||
                    (overlayState.projectionHome && overlayState.showStandby && !overlayState.showingAudio)
                if (isSettingsKey(keyCode) || canOpenWithSelect) {
                    setOverlayVisible(!isSettingsKey(keyCode) || !overlayState.visible)
                }
            }
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        overlayState = overlayState.copy(projectionHome = isProjectionHomeIntent(intent))
    }

    private fun isProjectionHomeIntent(intent: Intent): Boolean =
        intent.hasCategory(Intent.CATEGORY_HOME) ||
            intent.component?.className == "${packageName}.ProjectionHome"

    private fun isSettingsKey(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_SETTINGS ||
            keyCode == KeyEvent.KEYCODE_INFO

    private fun isSelectKey(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER

    private fun setOverlayVisible(visible: Boolean) {
        if (overlayState.visible == visible) return
        overlayState = overlayState.copy(visible = visible)
        dismissPlaybackControls()
        updateScreenWake()
    }

    private fun cycleReceiverName() {
        val nextName = ReceiverIdentityHelper.nextReceiverName(
            current = overlayState.receiverName,
            systemDeviceName = identityStore.readSystemDeviceName()
        )
        val saved = identityStore.saveServiceName(nextName)
        overlayState = overlayState.copy(receiverName = saved)
        pushAdvertisingConfig(overlayState)
    }

    private fun toggleAutoStartReceiver() {
        val enabled = !overlayState.autoStartReceiver
        BootReceiver.setAutoStartEnabled(this, enabled)
        overlayState = overlayState.copy(autoStartReceiver = enabled)
    }

    private fun toggleAutoOpenVisual() {
        val enabled = !overlayState.autoOpenVisual
        overlayState = overlayState.copy(autoOpenVisual = enabled)
        getSharedPreferences("receiver_presentation", MODE_PRIVATE)
            .edit().putBoolean("auto_open_visual", enabled).apply()
        sessionConnection.setAutoOpenVisual(enabled)
    }

    private fun toggleResolutionPreset() {
        val next = overlayState.toggleResolution()
        overlayState = next
        pushAdvertisingConfig(next)
    }

    private fun toggleHevcSetting() {
        val next = overlayState.toggleHevc()
        overlayState = next
        pushAdvertisingConfig(next)
    }

    private fun pushAdvertisingConfig(state: TvOverlayState) {
        sessionConnection.configureAdvertising(
            state.resolutionPreset.width,
            state.resolutionPreset.height,
            state.hevcEnabled,
            state.receiverName
        )
    }

    private fun adjustAudioOffset(deltaMs: Int) {
        val next = overlayState.stepAudioOffset(deltaMs)
        if (next == overlayState) return
        overlayState = next
        audioConsumer.setAudioOffsetMs(next.audioOffsetMs)
    }

    private fun startDirectVideo(video: DirectPlaybackStart, mirrorAvailable: Boolean): Boolean {
        val id = video.playbackId
        if (video.url.isBlank() || !video.airPlayPosition.isFinite() || id <= 0L) return false
        if (directPlaybackId == id && presentationCoordinator.hasDirectPlayback) return true
        dismissPlaybackControls()
        overlayState = overlayState.clearAudioMetadata().copy(visible = false, directAudio = null)
        directAspectRatio = null
        updateDirectSurfaceAspectRatio()
        cancelDirectStartupTimeout()
        directPlaybackId = id
        directRequestedAtMs = SystemClock.elapsedRealtime()
        if (!presentationCoordinator.switchToDirectVideo(video, mirrorAvailable)) {
            finishDirectSession(id)
            return false
        }
        if (presentationCoordinator.state == PresentationState.SWITCHING_TO_DIRECT) {
            scheduleDirectStartupTimeout(id)
        }
        syncPresentationState()
        return true
    }

    private fun scheduleDirectStartupTimeout(id: Long) {
        cancelDirectStartupTimeout()
        val timeout = Runnable {
            if (presentationCoordinator.onDirectStartupTimeout(id)) {
                Log.w(PLAYBACK_DIAGNOSTICS_TAG, "route=direct id=$id event=startup_timeout")
                val elapsedMs = SystemClock.elapsedRealtime() - directRequestedAtMs
                Log.w("TVPlayAir", "direct_startup outcome=timeout playbackId=$id elapsedMs=$elapsedMs")
                finishDirectSession(id)
            }
        }
        directStartupTimeout = timeout
        mainHandler.postDelayed(timeout, DIRECT_STARTUP_TIMEOUT_MS)
    }

    private fun canReportDirectPlayback(): Boolean =
        presentationCoordinator.state == PresentationState.DIRECT_ACTIVE ||
            presentationCoordinator.state == PresentationState.DIRECT_WITHOUT_SURFACE

    private fun setDirectPlaybackIntent(command: String) {
        if (directPlaybackId > 0L) {
            sessionConnection.setDirectPlaybackIntent(directPlaybackId, command)
        }
    }

    private fun publishDirectPlayback() {
        webVideoPlayer.systemSession?.let { DirectPlaybackService.publish(applicationContext, it) }
    }

    private fun saveDirectCheckpoint() {
        if (directPlaybackId <= 0L || !canReportDirectPlayback()) return
        sessionConnection.updatePlaybackTelemetry(
            directPlaybackId, presentationCoordinator.directTelemetry(), webVideoPlayer.checkpointPositionMs()
        )
    }

    private fun finishMirrorPresentation() {
        mirrorGeneration = null
        mirrorRequested = false
        if (!overlayState.audioProjectionActive) {
            overlayState = overlayState.copy(connectedClient = null)
        }
        presentationCoordinator.onMirrorDisconnected()
        syncPresentationState()
    }

    private fun finishDirectSession(playbackId: Long) {
        if (directPlaybackId != playbackId) return
        directPlaybackId = 0L
        sessionConnection.finishDirectPlayback(playbackId)
        syncPresentationState()
    }

    private fun cancelDirectStartupTimeout() {
        directStartupTimeout?.let(mainHandler::removeCallbacks)
        directStartupTimeout = null
    }

    private fun syncPresentationState() {
        val state = presentationCoordinator.state
        if (state != PresentationState.SWITCHING_TO_DIRECT) cancelDirectStartupTimeout()
        if (state == PresentationState.SWITCHING_TO_MIRROR) {
            if (mirrorSurfaceTimeout == null) {
                val timeout = Runnable {
                    mirrorSurfaceTimeout = null
                    if (presentationCoordinator.state == PresentationState.SWITCHING_TO_MIRROR) {
                        finishMirrorPresentation()
                    }
                }
                mirrorSurfaceTimeout = timeout
                mainHandler.postDelayed(timeout, 5_000L)
            }
        } else {
            mirrorSurfaceTimeout?.let(mainHandler::removeCallbacks)
            mirrorSurfaceTimeout = null
        }
        if ((state != PresentationState.DIRECT_ACTIVE && overlayState.videoControls != null) ||
            (state != PresentationState.MIRROR_ACTIVE && overlayState.mirrorControlsVisible)
        ) {
            dismissPlaybackControls()
        }
        if (overlayState.presentationState != state) {
            overlayState = overlayState.withPresentation(state)
        }
        if (state == PresentationState.DIRECT_ACTIVE) {
            overlayState = overlayState.withDirectTelemetry(presentationCoordinator.directTelemetry())
        }
        closeOverlayOnBack.isEnabled = overlayState.visible ||
            overlayState.showingVideoControls ||
            state == PresentationState.MIRROR_ACTIVE ||
            state == PresentationState.MIRROR_WAITING_KEYFRAME
        updateScreenWake()
    }

    private fun updateScreenWake() {
        val state = overlayState
        val keepScreenOn = !state.showStandby && !state.showingDirectAudio
        val flag = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        if (keepScreenOn) window.addFlags(flag) else window.clearFlags(flag)
    }

    private fun updateDirectSurfaceAspectRatio() = fitSurface(directSurfaceView, directAspectRatio)

    private fun updateMirrorSurfaceAspectRatio(videoWidth: Int, videoHeight: Int) {
        if (videoWidth > 0 && videoHeight > 0) {
            mirrorAspectRatio = videoWidth.toDouble() / videoHeight
            fitSurface(mirrorSurfaceView, mirrorAspectRatio)
        }
    }

    private fun fitSurface(surface: SurfaceView, aspectRatio: Double?) {
        val parent = surface.parent as? View
        val containerW = parent?.width?.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val containerH = parent?.height?.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        val (width, height) = if (aspectRatio == null || containerW <= 0 || containerH <= 0) {
            ViewGroup.LayoutParams.MATCH_PARENT to ViewGroup.LayoutParams.MATCH_PARENT
        } else if (aspectRatio >= containerW.toDouble() / containerH) {
            containerW to (containerW / aspectRatio).toInt().coerceAtLeast(1)
        } else {
            (containerH * aspectRatio).toInt().coerceAtLeast(1) to containerH
        }
        val params = surface.layoutParams as? FrameLayout.LayoutParams
        if (params?.width != width || params.height != height) {
            surface.layoutParams = FrameLayout.LayoutParams(width, height, Gravity.CENTER)
        }
    }

    override fun hasMirrorSurface(): Boolean = mirrorSurfaceView.holder.surface.isValid

    override fun coverMirrorSurface() {
        videoStartupCover.visibility = View.VISIBLE
    }

    override fun revealMirrorSurface() {
        videoStartupCover.visibility = View.GONE
    }

    override fun showMirrorSurface() {
        directSurfaceView.visibility = View.INVISIBLE
        videoStartupCover.visibility = View.VISIBLE
        mirrorSurfaceView.visibility = View.VISIBLE
    }

    override fun showDirectSurface() {
        videoStartupCover.visibility = View.VISIBLE
        mirrorSurfaceView.visibility = View.INVISIBLE
        directSurfaceView.visibility = View.VISIBLE
    }

    override fun revealDirectSurface() {
        videoStartupCover.visibility = View.GONE
    }

    override fun hideAllSurfaces() {
        mirrorSurfaceView.visibility = View.INVISIBLE
        directSurfaceView.visibility = View.INVISIBLE
        videoStartupCover.visibility = View.GONE
    }

    private fun surfaceCallback(isDirectSurface: Boolean) = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            if (destroyingView || !::presentationCoordinator.isInitialized) return
            if (isDirectSurface && presentationCoordinator.onDirectSurfaceCreated()) {
                directRequestedAtMs = SystemClock.elapsedRealtime()
                scheduleDirectStartupTimeout(directPlaybackId)
                syncPresentationState()
                webVideoPlayer.onOutputSurfaceRecreated()
            }
            if (!isDirectSurface && sharedMemoryReady && mirrorRequested && !awaitingDirectSnapshot) {
                presentationCoordinator.onMirrorSurfaceCreated()
                syncPresentationState()
            }
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            if (destroyingView || !::presentationCoordinator.isInitialized ||
                (isDirectSurface && isChangingConfigurations)
            ) return
            if (isDirectSurface && presentationCoordinator.hasDirectPlayback) {
                saveDirectCheckpoint()
                videoStartupCover.visibility = View.VISIBLE
            }
            presentationCoordinator.onSurfaceDestroyed(isDirectSurface)
            syncPresentationState()
        }
    }

    override fun onDestroy() {
        saveDirectCheckpoint()
        destroyingView = true
        mirrorSurfaceTimeout?.let(mainHandler::removeCallbacks)
        mirrorSurfaceTimeout = null
        cancelDirectStartupTimeout()
        mainHandler.removeCallbacks(hidePlaybackControls)
        hidBridge.release()
        webVideoPlayer.detachView(directSurfaceView)
        val retainedByService = !isChangingConfigurations &&
            DirectPlaybackService.retain(DirectPlaybackService.RetainedPlayback(webVideoPlayer, sessionConnection))
        if (retainedByService) directVideoModel.relinquishPlayer()
        val audioRetained = !retainedByService && (audioHandedOff ||
            (overlayState.audioProjectionActive && AudioContinuityService.retain(
                this, sessionConnection, audioConsumer, overlayState.audioGeneration
            )))
        if (retainedByService || isChangingConfigurations) {
            mirrorDecoder.stopAndRelease()
            audioConsumer.stop()
        } else if (!audioRetained) {
            presentationCoordinator.onSessionDied()
        } else {
            mirrorDecoder.stopAndRelease()
        }
        if (!retainedByService && !audioRetained) sessionConnection.close()
        super.onDestroy()
    }
}
