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
import com.tvplayair.receiver.audio.OboeAudioConsumer
import com.tvplayair.receiver.cast.CastMediaRequest
import com.tvplayair.receiver.cast.TvCastReceiver
import com.tvplayair.receiver.control.DacpCommand
import com.tvplayair.receiver.control.TvRemoteHidBridge
import com.tvplayair.receiver.network.ReceiverIdentityHelper
import com.tvplayair.receiver.network.ReceiverIdentityStore
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
    private lateinit var directStartupCover: View
    private lateinit var audioConsumer: OboeAudioConsumer
    private lateinit var mirrorDecoder: MirrorDecoder
    private val directVideoModel: DirectVideoViewModel by viewModels()
    private val webVideoPlayer: WebVideoPlayer get() = directVideoModel.player
    private lateinit var presentationCoordinator: VideoPresentationCoordinator
    private lateinit var sessionConnection: SessionConnection
    private lateinit var hidBridge: TvRemoteHidBridge
    private lateinit var identityStore: ReceiverIdentityStore

    private var sharedMemoryReady = false
    private var awaitingDirectSnapshot = false
    private var destroyingView = false
    private var screenStarted = false
    private var mirrorRequested = false
    private var directPlaybackId = 0L
    private var nextCastPlaybackId = 0L
    private var castReceiver: TvCastReceiver? = null
    private var castVideo: DirectPlaybackStart? = null
    private var pendingCastIntent: Intent? = null
    private var directStartupTimeout: Runnable? = null
    private var directRequestedAtMs = 0L
    private var overlayState by mutableStateOf(TvOverlayState())

    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingVideoSelect: Pair<Long, Long>? = null
    private val hideVideoControls = Runnable { dismissVideoControls() }
    private val closeOverlayOnBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            when {
                overlayState.visible -> setOverlayVisible(false)
                overlayState.showingVideoControls -> dismissVideoControls()
                presentationCoordinator.state == PresentationState.MIRROR_ACTIVE -> {
                    mirrorRequested = false
                    presentationCoordinator.onMirrorDisconnected()
                    syncPresentationState()
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
        directStartupCover = View(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
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
                        onVideoToggle = ::toggleVideoFromControls,
                        onVideoSeek = ::seekVideoFromControls,
                        onVideoInteraction = ::refreshVideoControlsTimeout,
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
        root.addView(directStartupCover, FrameLayout.LayoutParams(matchParent))
        root.addView(overlayComposeView, FrameLayout.LayoutParams(matchParent))
        setContentView(root)

        val retainedPlayback = DirectPlaybackService.claim()
        retainedPlayback?.let(directVideoModel::adopt)
        audioConsumer = OboeAudioConsumer(this)
        mirrorDecoder = MirrorDecoder(
            surfaceProvider = { mirrorSurfaceView.holder.surface },
            onVideoSizeChanged = ::updateMirrorSurfaceAspectRatio
        )
        webVideoPlayer.attachView(directSurfaceView, object : WebVideoPlayer.ViewCallbacks {
            override fun onTelemetryChanged(telemetry: DirectPlaybackTelemetry) {
                if (::presentationCoordinator.isInitialized &&
                    presentationCoordinator.state == PresentationState.DIRECT_ACTIVE &&
                    overlayState.showingVideoControls) {
                    val intentChanged = overlayState.videoControls?.playWhenReady != telemetry.playWhenReady
                    overlayState = overlayState.copy(videoControls = telemetry)
                    if (intentChanged) refreshVideoControlsTimeout()
                }
                if (::presentationCoordinator.isInitialized &&
                    canReportDirectPlayback() &&
                    ::sessionConnection.isInitialized && directPlaybackId > 0L) {
                    sessionConnection.updatePlaybackTelemetry(
                        directPlaybackId, telemetry, webVideoPlayer.checkpointPositionMs()
                    )
                }
            }

            override fun onMediaReady(playbackId: Long) {
                if (presentationCoordinator.onDirectMediaReady(playbackId)) {
                    val elapsedMs = SystemClock.elapsedRealtime() - directRequestedAtMs
                    Log.i("TVPlayAir", "direct_startup outcome=ready playbackId=$playbackId elapsedMs=$elapsedMs")
                    syncPresentationState()
                    webVideoPlayer.exposeSystemControls(playbackId)
                    if (screenStarted) publishDirectPlayback()
                    if (playbackId < 0L) castReceiver?.attachSession(webVideoPlayer.platformSessionToken)
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

        val sessionListener = object : SessionEventListener {
            override fun onSharedMemoryReady(generationId: Int, activeDirectPlaybackId: Long) {
                sharedMemoryReady = true
                awaitingDirectSnapshot = activeDirectPlaybackId != 0L
                val retainedId = webVideoPlayer.playbackId
                val binder = sessionConnection.remoteBinder
                if (retainedId != null && retainedId > 0L &&
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
                if (directPlaybackId < 0L) {
                    audioConsumer.stop()
                    presentationCoordinator.onMirrorDisconnected()
                } else {
                    directPlaybackId = 0L
                    cancelDirectStartupTimeout()
                    dismissVideoControls()
                    presentationCoordinator.onSessionDied()
                }
                overlayState = overlayState.clearAudioMetadata().copy(
                    connectedClient = null,
                    audioProjectionActive = false,
                    audioControlsAvailable = false,
                    presentationState = presentationCoordinator.state
                )
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
                mirrorRequested = true
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
                if (awaitingDirectSnapshot && directPlaybackId < 0L) {
                    awaitingDirectSnapshot = false
                    sessionConnection.finishDirectPlayback(video.playbackId)
                    return
                }
                awaitingDirectSnapshot = false
                if (video.playbackId > 0L) startDirectVideo(video, restoringMirror)
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

            override fun onMirrorStreamEnded() {
                mirrorRequested = false
                if (!overlayState.audioProjectionActive) {
                    overlayState = overlayState.copy(connectedClient = null)
                }
                presentationCoordinator.onMirrorDisconnected()
                syncPresentationState()
            }

            override fun onAudioStreamStarted(generationId: Int) {
                overlayState = overlayState.withAudioStreamStarted()
                updateScreenWake()
                audioConsumer.setGeneration(generationId)
                if (sharedMemoryReady) {
                    audioConsumer.start(generationId)
                }
            }

            override fun onAudioStreamStopped() {
                audioConsumer.stop()
                overlayState = overlayState.withAudioStreamStopped()
                updateScreenWake()
            }

            override fun onAudioControlsAvailable(available: Boolean) {
                overlayState = overlayState.copy(audioControlsAvailable = available)
            }

            override fun onNowPlaying(metadata: RaopNowPlaying?) {
                // Metadata is not a transport stop signal: artwork/title may disappear
                // while the sender keeps its audio stream alive (including on lock).
                overlayState = overlayState.withNowPlaying(metadata)
                updateScreenWake()
            }
        }
        sessionConnection = retainedPlayback?.connection ?: SessionConnection(this)
        sessionConnection.replaceListener(sessionListener, replay = retainedPlayback != null)
        sessionConnection.connect()
        if (BuildConfig.CAST_CONNECT_ENABLED) {
            castReceiver = retainedPlayback?.castReceiver ?: runCatching {
                TvCastReceiver(this, ::loadCastMedia)
            }.onFailure { Log.w("TVPlayAir", "Cast Connect unavailable", it) }.getOrNull()
            castReceiver?.replaceLoadHandler(::loadCastMedia)
            pendingCastIntent = intent
            retainedPlayback?.castVideo?.let { video ->
                nextCastPlaybackId = video.playbackId
                startDirectVideo(video.copy(playWhenReady = webVideoPlayer.wantsPlayback), mirrorAvailable = false)
            }
        }
    }

    private fun loadCastMedia(request: CastMediaRequest): Boolean =
        startDirectVideo(
            DirectPlaybackStart(request.url, 0.0, --nextCastPlaybackId, request.startMs, request.autoplay),
            mirrorAvailable = mirrorRequested
        )

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        castReceiver?.let { receiver ->
            if (!receiver.handleIntent(intent)) pendingCastIntent = intent
        }
    }

    override fun onStart() {
        super.onStart()
        screenStarted = true
        sessionConnection.setProjectionVisible(true)
        if (directPlaybackId != 0L) publishDirectPlayback()
        castReceiver?.let { receiver ->
            runCatching {
                receiver.start()
                pendingCastIntent?.let(receiver::handleIntent)
            }.onFailure { Log.w("TVPlayAir", "Cast Connect could not start", it) }
            pendingCastIntent = null
        }
    }

    override fun onStop() {
        saveDirectCheckpoint()
        screenStarted = false
        if (directPlaybackId >= 0L) castReceiver?.stop()
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

        val inDirectMode = presentationCoordinator.hasDirectPlayback
        if (presentationCoordinator.state == PresentationState.DIRECT_ACTIVE &&
            !overlayState.showingVideoControls &&
            handleHiddenVideoControlsKey(keyCode, event)
        ) return true
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (inDirectMode) {
                    if (event == null || event.repeatCount == 0) {
                        setDirectPlaybackIntent("toggle")
                    }
                } else {
                    sessionConnection.sendDacpCommand(DacpCommand.PLAY_PAUSE.wirePath)
                }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                if (inDirectMode) {
                    setDirectPlaybackIntent("play")
                } else {
                    sessionConnection.sendDacpCommand(DacpCommand.PLAY.wirePath)
                }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (inDirectMode) {
                    setDirectPlaybackIntent("pause")
                } else {
                    sessionConnection.sendDacpCommand(DacpCommand.PAUSE.wirePath)
                }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_STOP -> {
                if (inDirectMode) {
                    if (presentationCoordinator.finishDirectPlayback(directPlaybackId)) {
                        finishDirectSession(directPlaybackId)
                    }
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

    private fun handleHiddenVideoControlsKey(keyCode: Int, event: KeyEvent?): Boolean {
        when {
            isSelectKey(keyCode) -> {
                if (event?.repeatCount == 0) {
                    event.startTracking()
                    pendingVideoSelect = event.downTime to directPlaybackId
                }
                return true
            }
            keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> if (event?.repeatCount == 0) seekVideoBy(-10.0)
            keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> if (event?.repeatCount == 0) seekVideoBy(10.0)
            keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN -> Unit
            else -> return false
        }
        showVideoControls()
        return true
    }

    private fun showVideoControls() {
        overlayState = overlayState.copy(videoControls = presentationCoordinator.directTelemetry())
        closeOverlayOnBack.isEnabled = true
        refreshVideoControlsTimeout()
    }

    private fun refreshVideoControlsTimeout() {
        if (!overlayState.showingVideoControls) return
        mainHandler.removeCallbacks(hideVideoControls)
        if (overlayState.videoControls?.playWhenReady == true) {
            mainHandler.postDelayed(hideVideoControls, 5_000L)
        }
    }

    private fun toggleVideoFromControls() {
        if (!overlayState.showingVideoControls) return
        setDirectPlaybackIntent("toggle")
        refreshVideoControlsTimeout()
    }

    private fun seekVideoFromControls(deltaSec: Double) {
        if (!overlayState.showingVideoControls) return
        seekVideoBy(deltaSec)
        refreshVideoControlsTimeout()
    }

    private fun seekVideoBy(deltaSec: Double) {
        val telemetry = presentationCoordinator.directTelemetry()
        if (!telemetry.canSeek) return
        presentationCoordinator.seekDirect((telemetry.positionSec + deltaSec).coerceAtLeast(0.0))
    }

    private fun dismissVideoControls() {
        mainHandler.removeCallbacks(hideVideoControls)
        if (overlayState.videoControls != null) {
            overlayState = overlayState.copy(videoControls = null)
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

    private fun startDirectVideo(video: DirectPlaybackStart, mirrorAvailable: Boolean): Boolean {
        val id = video.playbackId
        if (video.url.isBlank() || !video.airPlayPosition.isFinite() || id == 0L) return false
        if (directPlaybackId == id && presentationCoordinator.hasDirectPlayback) return true
        dismissVideoControls()
        overlayState = overlayState.clearAudioMetadata().copy(visible = false)
        cancelDirectStartupTimeout()
        if (directPlaybackId < 0L) {
            if (id > 0L) releaseCastPlayback() else castReceiver?.attachSession(null)
        }
        if (directPlaybackId > 0L && id < 0L) sessionConnection.finishDirectPlayback(directPlaybackId)
        directPlaybackId = id
        castVideo = video.takeIf { id < 0L }
        directRequestedAtMs = SystemClock.elapsedRealtime()
        if (!presentationCoordinator.switchToDirectVideo(video, mirrorAvailable)) {
            finishDirectSession(id)
            return false
        }
        if (presentationCoordinator.state == PresentationState.SWITCHING_TO_DIRECT) {
            scheduleDirectStartupTimeout(id)
        }
        if (id < 0L) {
            webVideoPlayer.exposeSystemControls(id)
            if (screenStarted) publishDirectPlayback()
            castReceiver?.attachSession(webVideoPlayer.platformSessionToken)
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
        } else if (directPlaybackId < 0L) {
            val play = when (command) {
                "play" -> true
                "pause" -> false
                "toggle" -> !webVideoPlayer.wantsPlayback
                else -> return
            }
            if (play) presentationCoordinator.playDirect() else presentationCoordinator.pauseDirect()
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

    private fun finishDirectSession(playbackId: Long) {
        if (directPlaybackId != playbackId) return
        directPlaybackId = 0L
        if (playbackId > 0L) sessionConnection.finishDirectPlayback(playbackId)
        else releaseCastPlayback()
        syncPresentationState()
    }

    private fun releaseCastPlayback() {
        castVideo = null
        castReceiver?.let { receiver ->
            receiver.attachSession(null)
            if (!screenStarted) receiver.stop()
        }
    }

    private fun cancelDirectStartupTimeout() {
        directStartupTimeout?.let(mainHandler::removeCallbacks)
        directStartupTimeout = null
    }

    private fun syncPresentationState() {
        val state = presentationCoordinator.state
        if (state != PresentationState.SWITCHING_TO_DIRECT) cancelDirectStartupTimeout()
        if (state != PresentationState.DIRECT_ACTIVE && overlayState.videoControls != null) {
            dismissVideoControls()
        }
        if (overlayState.presentationState != state) {
            overlayState = overlayState.withPresentation(state)
        }
        closeOverlayOnBack.isEnabled = overlayState.visible ||
            overlayState.showingVideoControls ||
            state == PresentationState.MIRROR_ACTIVE
        updateScreenWake()
    }

    private fun updateScreenWake() {
        val state = overlayState
        val keepScreenOn = !state.projectionHome || state.visible || state.showingAudio || !state.showStandby
        val flag = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        if (keepScreenOn) window.addFlags(flag) else window.clearFlags(flag)
    }

    private fun updateMirrorSurfaceAspectRatio(videoWidth: Int, videoHeight: Int) {
        if (!::mirrorSurfaceView.isInitialized || videoWidth <= 0 || videoHeight <= 0) return
        val parent = mirrorSurfaceView.parent as? View
        val containerW = parent?.width?.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val containerH = parent?.height?.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        if (containerW <= 0 || containerH <= 0) return

        val fitW: Int
        val fitH: Int
        if (videoWidth.toLong() * containerH >= videoHeight.toLong() * containerW) {
            fitW = containerW
            fitH = ((containerW.toLong() * videoHeight) / videoWidth).toInt().coerceAtLeast(1)
        } else {
            fitH = containerH
            fitW = ((containerH.toLong() * videoWidth) / videoHeight).toInt().coerceAtLeast(1)
        }
        val currentParams = mirrorSurfaceView.layoutParams as? FrameLayout.LayoutParams
        if (currentParams == null || currentParams.width != fitW || currentParams.height != fitH) {
            mirrorSurfaceView.layoutParams = FrameLayout.LayoutParams(fitW, fitH, Gravity.CENTER)
        }
    }

    override fun showMirrorSurface() {
        directSurfaceView.visibility = View.INVISIBLE
        mirrorSurfaceView.visibility = View.VISIBLE
        directStartupCover.visibility = View.GONE
    }

    override fun showDirectSurface() {
        directStartupCover.visibility = View.VISIBLE
        mirrorSurfaceView.visibility = View.INVISIBLE
        directSurfaceView.visibility = View.VISIBLE
    }

    override fun revealDirectSurface() {
        directStartupCover.visibility = View.GONE
    }

    override fun hideAllSurfaces() {
        mirrorSurfaceView.visibility = View.INVISIBLE
        directSurfaceView.visibility = View.INVISIBLE
        directStartupCover.visibility = View.GONE
    }

    private fun surfaceCallback(isDirectSurface: Boolean) = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            if (destroyingView || !::presentationCoordinator.isInitialized) return
            if (isDirectSurface && presentationCoordinator.onDirectSurfaceCreated()) {
                webVideoPlayer.onOutputSurfaceRecreated()
                directRequestedAtMs = SystemClock.elapsedRealtime()
                scheduleDirectStartupTimeout(directPlaybackId)
                syncPresentationState()
            }
            if (!isDirectSurface && sharedMemoryReady && mirrorRequested && !awaitingDirectSnapshot) {
                presentationCoordinator.onMirrorSurfaceCreated()
                syncPresentationState()
            }
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            if (destroyingView || (isDirectSurface && isChangingConfigurations)) return
            if (isDirectSurface && presentationCoordinator.hasDirectPlayback) {
                saveDirectCheckpoint()
                directStartupCover.visibility = View.VISIBLE
            }
            presentationCoordinator.onSurfaceDestroyed(isDirectSurface)
            syncPresentationState()
        }
    }

    override fun onDestroy() {
        saveDirectCheckpoint()
        destroyingView = true
        cancelDirectStartupTimeout()
        mainHandler.removeCallbacks(hideVideoControls)
        hidBridge.release()
        webVideoPlayer.detachView(directSurfaceView)
        val retainedByService = (!isChangingConfigurations || directPlaybackId < 0L) &&
            DirectPlaybackService.retain(
                DirectPlaybackService.RetainedPlayback(
                    webVideoPlayer, sessionConnection,
                    castReceiver.takeIf { directPlaybackId < 0L }, castVideo
                )
            )
        if (!retainedByService) castReceiver?.stop()
        if (retainedByService) directVideoModel.relinquishPlayer()
        if (retainedByService || (isChangingConfigurations && directPlaybackId >= 0L)) {
            mirrorDecoder.stopAndRelease()
            audioConsumer.stop()
        } else {
            presentationCoordinator.onSessionDied()
        }
        if (!retainedByService) sessionConnection.close()
        super.onDestroy()
    }
}
