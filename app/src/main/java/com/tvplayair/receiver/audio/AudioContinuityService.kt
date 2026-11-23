package com.tvplayair.receiver.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
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

    private val listener = object : SessionEventListener {
        override fun onSharedMemoryReady(generationId: Int, activeDirectPlaybackId: Long) {
            if (!ownsHandoff) return
            consumer?.setGeneration(generationId)
            if (audioGeneration != null && activeDirectPlaybackId == 0L) {
                consumer?.start(audioGeneration!!)
            }
        }

        override fun onAudioStreamStarted(generationId: Int) {
            audioGeneration = generationId
            if (ownsHandoff) {
                consumer?.setGeneration(generationId)
                consumer?.start(generationId)
            }
        }

        override fun onAudioStreamStopped() {
            audioGeneration = null
            consumer?.stop()
        }

        override fun onSharedMemoryDetached() {
            consumer?.stop()
        }
    }

    override fun onCreate() {
        super.onCreate()
        running = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_HOST || connection == null || consumer == null) {
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
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1002, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(1002, notification)
        }
        ownsHandoff = true
        connection?.replaceListener(listener, replay = true)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        ownsHandoff = false
        if (running === this) running = null
        // Activity reclaim removes ownership first; otherwise tear down resources.
        if (!claimed) {
            consumer?.stop()
            connection?.close()
        }
        claimed = false
        consumer = null
        connection = null
        super.onDestroy()
    }

    companion object {
        private const val ACTION_HOST = "com.tvplayair.receiver.HOST_RAOP_AUDIO"
        private var running: AudioContinuityService? = null
        private var connection: SessionConnection? = null
        private var consumer: OboeAudioConsumer? = null
        private var audioGeneration: Int? = null
        private var claimed = false

        fun retain(context: Context, session: SessionConnection, audio: OboeAudioConsumer, generation: Int?): Boolean {
            if (generation == null) return false
            connection = session
            consumer = audio
            audioGeneration = generation
            claimed = false
            return runCatching {
                context.startForegroundService(
                    Intent(context, AudioContinuityService::class.java).setAction(ACTION_HOST)
                )
                true
            }.getOrElse {
                Log.w("AudioContinuityService", "Background audio handoff denied", it)
                connection = null
                consumer = null
                audioGeneration = null
                false
            }
        }

        fun claim(): Pair<SessionConnection, OboeAudioConsumer>? {
            val session = connection ?: return null
            val audio = consumer ?: return null
            claimed = true
            running?.ownsHandoff = false
            running?.stopSelf()
            connection = null
            consumer = null
            audioGeneration = null
            return session to audio
        }
    }
}
