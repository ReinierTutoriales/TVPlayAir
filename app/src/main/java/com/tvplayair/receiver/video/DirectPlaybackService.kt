package com.tvplayair.receiver.video

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.tvplayair.receiver.cast.TvCastReceiver
import com.tvplayair.receiver.session.SessionConnection
import com.tvplayair.receiver.session.SessionEventListener

/** Owns active direct playback when the TV UI is gone. All handoffs run on the main thread. */
class DirectPlaybackService : MediaSessionService() {
    internal data class RetainedPlayback(
        val player: WebVideoPlayer,
        val connection: SessionConnection,
        val castReceiver: TvCastReceiver? = null,
        val castVideo: DirectPlaybackStart? = null
    )

    private var hostedSession: MediaSession? = null
    private var retained: RetainedPlayback? = null
    private val handler = Handler(Looper.getMainLooper())
    private val castStartupTimeout = Runnable {
        val player = retained?.player ?: return@Runnable
        if (!player.currentTelemetry().isReady) finishRetained()
    }

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
        if (id < 0L && (handoff.castReceiver == null || handoff.castVideo?.playbackId != id)) return false
        retained = handoff
        handoff.castReceiver?.replaceLoadHandler { request ->
            val current = retained?.castVideo ?: return@replaceLoadHandler false
            val replacement = DirectPlaybackStart(
                request.url, 0.0, current.playbackId - 1L, request.startMs, request.autoplay
            )
            handler.removeCallbacks(castStartupTimeout)
            handler.postDelayed(castStartupTimeout, DIRECT_STARTUP_TIMEOUT_MS)
            if (!player.prepareDetachedCast(replacement)) {
                handler.removeCallbacks(castStartupTimeout)
                return@replaceLoadHandler false
            }
            retained = retained?.copy(castVideo = replacement)
            true
        }

        connection.replaceListener(object : SessionEventListener {
            private var replayedAirPlayId = 0L

            override fun onSharedMemoryReady(generationId: Int, activeDirectPlaybackId: Long) {
                replayedAirPlayId = activeDirectPlaybackId
            }

            override fun onSharedMemoryDetached() {
                if ((player.playbackId ?: id) > 0L) finishRetained(notifySender = false)
            }

            override fun onDirectVideoUrl(video: DirectPlaybackStart) {
                val activeId = player.playbackId ?: id
                if (activeId < 0L && video.playbackId == replayedAirPlayId) {
                    replayedAirPlayId = 0L
                    connection.finishDirectPlayback(video.playbackId)
                } else if (video.playbackId != activeId) {
                    finishRetained(notifySender = false)
                }
            }

            override fun onVideoConfigChanged(
                mimeType: String, width: Int, height: Int, generationId: Int, isSnapshot: Boolean
            ) {
                if (!isSnapshot) finishRetained()
            }

            override fun onDirectVideoCommand(command: String, value: Double, playbackId: Long) {
                val activeId = player.playbackId ?: return
                if (playbackId != activeId || retained == null) return
                when (command) {
                    "play" -> player.play()
                    "pause" -> player.pause()
                    "seek" -> player.seekTo(value)
                    "stop" -> finishRetained()
                }
            }
        })
        player.setDetachedCallbacks(object : WebVideoPlayer.ViewCallbacks {
            override fun onTelemetryChanged(telemetry: DirectPlaybackTelemetry) {
                val activeId = player.playbackId ?: return
                if (activeId > 0L && retained != null) {
                    connection.updatePlaybackTelemetry(activeId, telemetry, player.checkpointPositionMs())
                } else if (activeId < 0L && telemetry.isReady) {
                    handler.removeCallbacks(castStartupTimeout)
                }
            }

            override fun onMediaReady(playbackId: Long) = Unit

            override fun onPlaybackFinished(playbackId: Long) {
                if (playbackId == player.playbackId) finishRetained()
            }

            override fun onSystemPlaybackIntent(playbackId: Long, playWhenReady: Boolean) {
                val activeId = player.playbackId ?: return
                if (playbackId == activeId && retained != null) {
                    if (activeId > 0L) {
                        connection.setDirectPlaybackIntent(activeId, if (playWhenReady) "play" else "pause")
                    } else if (playWhenReady) {
                        player.play()
                    } else {
                        player.pause()
                    }
                }
            }
        })
        return true
    }

    private fun finishRetained(notifySender: Boolean = true) {
        val handoff = retained ?: return
        retained = null
        handler.removeCallbacks(castStartupTimeout)
        if (notifySender) {
            handoff.player.playbackId?.takeIf { it > 0L }?.let(handoff.connection::finishDirectPlayback)
        }
        handoff.castReceiver?.stop()
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
            service.handler.removeCallbacks(service.castStartupTimeout)
            service.retained = null
            return handoff
        }
    }
}
