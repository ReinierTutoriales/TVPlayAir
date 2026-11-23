package com.tvplayair.receiver.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import com.tvplayair.receiver.MainActivity
import com.tvplayair.receiver.session.SessionConnection
import com.tvplayair.receiver.session.SessionEventListener

/**
 * Keeps the RAOP consumer and its mapped IPC connection alive when the TV Activity
 * leaves the foreground. This service runs in the presentation process because
 * the native consumer mapping is process-local.
 */
class AudioContinuityService : Service() {
    private var ownsHandoff = false
    private var ownedHandoffId = 0L

    private val listener = object : SessionEventListener {
        override fun onSharedMemoryReady(generationId: Int, activeDirectPlaybackId: Long) {
            if (!ownsCurrentHandoff()) return
            consumer?.setGeneration(generationId)
            val activeGeneration = audioGeneration
            if (activeGeneration != null && activeDirectPlaybackId == 0L) {
                consumer?.start(activeGeneration)
            }
        }

        override fun onAudioStreamStarted(generationId: Int) {
            if (!ownsCurrentHandoff()) return
            audioGeneration = generationId
            consumer?.setGeneration(generationId)
            consumer?.start(generationId)
        }

        override fun onAudioStreamStopped() {
            if (!ownsHandoff) return
            audioGeneration = null
            consumer?.stop()
            stopSelf()
        }

        override fun onSharedMemoryDetached() {
            if (!ownsHandoff) return
            consumer?.stop()
        }
    }

    private fun ownsCurrentHandoff(): Boolean =
        ownsHandoff && ownedHandoffId != 0L && ownedHandoffId == handoffId

    override fun onCreate() {
        super.onCreate()
        running = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val requestedHandoffId = intent?.getLongExtra(EXTRA_HANDOFF_ID, 0L) ?: 0L
        if (intent?.action != ACTION_HOST || requestedHandoffId == 0L ||
            requestedHandoffId != handoffId || connection == null || consumer == null
        ) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val channelId = "tvplayair_audio_continuity"
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(channelId, "AirPlay audio", NotificationManager.IMPORTANCE_LOW)
        )
        val launch = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("TVPlayAir")
            .setContentText("AirPlay audio")
            .setContentIntent(launch)
            .setOngoing(true)
            .build()
        startForeground(1002, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        ownedHandoffId = requestedHandoffId
        ownsHandoff = true
        connection?.replaceListener(listener, replay = true)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        ownsHandoff = false
        if (running === this) running = null
        // Activity reclaim removes ownership first; otherwise tear down resources.
        // A reclaimed or superseded handoff belongs to the Activity/new service.
        if (ownedHandoffId != 0L && ownedHandoffId == handoffId) {
            consumer?.stop()
            connection?.close()
            connection = null
            consumer = null
            audioGeneration = null
            handoffId++
        }
        super.onDestroy()
    }

    companion object {
        private const val ACTION_HOST = "com.tvplayair.receiver.HOST_RAOP_AUDIO"
        private const val EXTRA_HANDOFF_ID = "handoff_id"
        private var running: AudioContinuityService? = null
        private var connection: SessionConnection? = null
        private var consumer: OboeAudioConsumer? = null
        private var audioGeneration: Int? = null
        private var handoffId = 0L

        fun retain(context: Context, session: SessionConnection, audio: OboeAudioConsumer, generation: Int?): Boolean {
            if (generation == null) return false
            if (connection === session && consumer === audio && running?.ownsCurrentHandoff() == true) return true
            // A new owner invalidates callbacks and destruction from the previous handoff.
            handoffId++
            connection = session
            consumer = audio
            audioGeneration = generation
            return runCatching {
                context.startForegroundService(
                    Intent(context, AudioContinuityService::class.java)
                        .setAction(ACTION_HOST)
                        .putExtra(EXTRA_HANDOFF_ID, handoffId)
                )
                true
            }.getOrElse {
                Log.w("AudioContinuityService", "Background audio handoff denied", it)
                handoffId++
                connection = null
                consumer = null
                audioGeneration = null
                false
            }
        }

        fun claim(): Pair<SessionConnection, OboeAudioConsumer>? {
            val session = connection ?: return null
            val audio = consumer ?: return null
            handoffId++
            running?.ownsHandoff = false
            running?.stopSelf()
            connection = null
            consumer = null
            audioGeneration = null
            return session to audio
        }
    }
}
