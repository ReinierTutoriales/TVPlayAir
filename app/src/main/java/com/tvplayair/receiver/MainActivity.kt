package com.tvplayair.receiver

import android.graphics.Color
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import com.tvplayair.receiver.audio.OboeAudioConsumer
import com.tvplayair.receiver.session.SessionConnection
import com.tvplayair.receiver.video.MirrorDecoder
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
        webVideoPlayer = WebVideoPlayer(this, directSurfaceView)
        presentationCoordinator = VideoPresentationCoordinator(
            mirrorEngine = mirrorDecoder,
            directEngine = webVideoPlayer,
            audioController = audioConsumer,
            surfaceController = this
        )

        sessionConnection = SessionConnection(
            context = this,
            onSharedMemoryReady = {
                sharedMemoryReady = true
                audioConsumer.start()
                if (mirrorSurfaceView.holder.surface?.isValid == true) {
                    presentationCoordinator.startMirror()
                }
            },
            onSharedMemoryDetached = {
                sharedMemoryReady = false
                presentationCoordinator.onSessionDied()
            }
        )
        sessionConnection.connect()
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
