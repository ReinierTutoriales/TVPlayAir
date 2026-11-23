package com.tvplayair.receiver.video

import android.content.Context
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.tvplayair.receiver.session.SessionConnection
import com.tvplayair.receiver.session.SessionEventListener

/** Owns active direct playback when the TV UI is gone. All handoffs run on the main thread. */
@OptIn(UnstableApi::class)
class DirectPlaybackService : MediaSessionService() {
    internal data class RetainedPlayback(
        val player: WebVideoPlayer,
        val connection: SessionConnection
    )

    private var hostedSession: MediaSession? = null
    private var retained: RetainedPlayback? = null

    override fun onCreate() {
        super.onCreate()
        running = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_HOST) return super.onStartCommand(intent, flags, startId)
        val session = requestedSession
        if (session == null) stopSelf(startId) else host(session)
        return START_NOT_STICKY
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        hostedSession.takeIf { controllerInfo.isTrusted }

    private fun host(session: MediaSession) {
        if (hostedSession === session) return
        hostedSession?.let(::removeSession)
        hostedSession = session
        addSession(session)
    }

    private fun retain(handoff: RetainedPlayback): Boolean {
        val player = handoff.player
        val connection = handoff.connection
        val id = player.playbackId ?: return false
        if (player.systemSession !== hostedSession || hostedSession == null) return false
        retained = handoff
        connection.replaceListener(object : SessionEventListener {
            override fun onSharedMemoryDetached() = finishRetained(notifySender = false)

            override fun onDirectVideoUrl(video: DirectPlaybackStart) {
                if (video.playbackId != id) finishRetained(notifySender = false)
            }

            override fun onVideoConfigChanged(
                mimeType: String, width: Int, height: Int, generationId: Int, codecConfig: ByteArray, opensProjection: Boolean
            ) {
                if (opensProjection) finishRetained()
            }

            override fun onDirectVideoCommand(command: String, value: Double, playbackId: Long) {
                val activeId = player.playbackId ?: return
                if (playbackId != activeId || retained == null) return
                when (command) {
                    "play" -> player.play()
                    "pause" -> player.pause()
                    "seek" -> if (validDirectSeekPosition(value)) player.seekTo(value)
                    "stop" -> finishRetained()
                }
            }
        })
        player.setDetachedCallbacks(object : WebVideoPlayer.ViewCallbacks {
            override fun onTelemetryChanged(telemetry: DirectPlaybackTelemetry) {
                val activeId = player.playbackId ?: return
                if (retained != null) {
                    connection.updatePlaybackTelemetry(activeId, telemetry, player.checkpointPositionMs())
                }
            }

            override fun onMediaReady(playbackId: Long) = Unit

            override fun onPlaybackFinished(playbackId: Long) {
                if (playbackId == player.playbackId) finishRetained()
            }

            override fun onSystemPlaybackIntent(playbackId: Long, playWhenReady: Boolean) {
                val activeId = player.playbackId ?: return
                if (playbackId == activeId && retained != null) {
                    connection.setDirectPlaybackIntent(activeId, if (playWhenReady) "play" else "pause")
                }
            }
        })
        return true
    }

    private fun finishRetained(notifySender: Boolean = true) {
        val handoff = retained ?: return
        retained = null
        if (notifySender) {
            handoff.player.playbackId?.let(handoff.connection::finishDirectPlayback)
        }
        handoff.connection.replaceListener(object : SessionEventListener {})
        handoff.player.release()
        handoff.connection.close()
    }

    override fun onDestroy() {
        if (running === this) running = null
        val previous = hostedSession
        hostedSession = null
        if (requestedSession === previous) requestedSession = null
        previous?.let(::removeSession)
        finishRetained()
        super.onDestroy()
    }

    companion object {
        private const val ACTION_HOST = "com.tvplayair.receiver.HOST_DIRECT_PLAYBACK"
        private var running: DirectPlaybackService? = null
        private var requestedSession: MediaSession? = null

        internal fun publish(context: Context, session: MediaSession) {
            if (requestedSession === session) return
            requestedSession = session
            runCatching {
                context.startService(Intent(context, DirectPlaybackService::class.java).setAction(ACTION_HOST))
            }.onSuccess {
                running?.host(session)
            }.onFailure {
                if (requestedSession === session) requestedSession = null
            }
        }

        internal fun unpublish(session: MediaSession) {
            if (requestedSession === session) requestedSession = null
            val service = running ?: return
            if (service.hostedSession !== session) return
            service.hostedSession = null
            service.removeSession(session)
            service.stopSelf()
        }

        internal fun retain(handoff: RetainedPlayback): Boolean =
            running?.retain(handoff) == true

        internal fun claim(): RetainedPlayback? {
            val service = running ?: return null
            val handoff = service.retained
            service.retained = null
            return handoff
        }
    }
}
