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
import com.tvplayair.receiver.audio.OboeAudioConsumer
import com.tvplayair.receiver.control.DacpCommand
import com.tvplayair.receiver.session.SessionConnection
import com.tvplayair.receiver.session.SessionEventListener
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

    private var sharedMemoryReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

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
        root.addView(
            mirrorSurfaceView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        root.addView(
            directSurfaceView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
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

        sessionConnection = SessionConnection(
            context = this,
            listener = object : SessionEventListener {
                override fun onSharedMemoryReady(generationId: Int) {
                    sharedMemoryReady = true
                    mirrorDecoder.setGeneration(generationId)
                    audioConsumer.start(generationId)
                    if (mirrorSurfaceView.holder.surface?.isValid == true) {
                        presentationCoordinator.startMirror()
                    }
                }

                override fun onSharedMemoryDetached() {
                    sharedMemoryReady = false
                    presentationCoordinator.onSessionDied()
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
                }

                override fun onDirectVideoUrl(url: String, startPositionSec: Double) {
                    presentationCoordinator.switchToDirectVideo(url, startPositionSec)
                }

                override fun onDirectVideoCommand(command: String, value: Double) {
                    when (command) {
                        "play" -> presentationCoordinator.playDirect()
                        "pause" -> presentationCoordinator.pauseDirect()
                        "seek" -> presentationCoordinator.seekDirect(value)
                        "stop" -> presentationCoordinator.switchToMirror()
                    }
                }
            }
        )
        sessionConnection.connect()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
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
        return super.onKeyDown(keyCode, event)
    }

    override fun showMirrorSurface() {
        directSurfaceView.visibility = View.INVISIBLE
        mirrorSurfaceView.visibility = View.VISIBLE
        mirrorSurfaceView.bringToFront()
    }

    override fun showDirectSurface() {
        mirrorSurfaceView.visibility = View.INVISIBLE
        directSurfaceView.visibility = View.VISIBLE
        directSurfaceView.bringToFront()
    }

    override fun hideAllSurfaces() {
        mirrorSurfaceView.visibility = View.INVISIBLE
        directSurfaceView.visibility = View.INVISIBLE
    }

    private fun surfaceCallback(isDirectSurface: Boolean) = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            if (!isDirectSurface && sharedMemoryReady) {
                presentationCoordinator.startMirror()
            }
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            presentationCoordinator.onSurfaceDestroyed(isDirectSurface)
        }
    }

    override fun onDestroy() {
        presentationCoordinator.onSessionDied()
        webVideoPlayer.release()
        sessionConnection.close()
        super.onDestroy()
    }
}
