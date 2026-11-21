package com.tvplayair.receiver.video

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.SurfaceView
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector

/**
 * Direct 4K HDR web video player backed by Media3 ExoPlayer on a native SurfaceView.
 * Uses Safari iOS User-Agent and adaptive track selection for direct TV playback.
 */
class WebVideoPlayer(
    context: Context,
    private val surfaceView: SurfaceView,
    private val onTelemetryChanged: (DirectPlaybackTelemetry) -> Unit = {},
    private val onPlaybackFinished: () -> Unit = {}
) : DirectVideoEngine {

    companion object {
        private const val TAG = "WebVideoPlayer"
        private const val TELEMETRY_INTERVAL_MS = 1_000L
        const val SAFARI_IOS_USER_AGENT =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) " +
                "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1"
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var pendingStartFraction: Double? = null
    private var playbackGeneration = 0L

    private val telemetryTicker = object : Runnable {
        override fun run() {
            val exo = player ?: return
            onTelemetryChanged(currentTelemetry())
            if (exo.playbackState == Player.STATE_READY || exo.playbackState == Player.STATE_BUFFERING) {
                mainHandler.postDelayed(this, TELEMETRY_INTERVAL_MS)
            }
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            val exo = player ?: return
            val fraction = pendingStartFraction ?: return
            if (!exo.isCurrentMediaItemSeekable || exo.isCurrentMediaItemLive) return
            val positionMs = initialDirectPositionMs(fraction, exo.duration) ?: return
            pendingStartFraction = null
            exo.seekTo(positionMs)
        }

        override fun onRenderedFirstFrame() {
            Log.i(TAG, "Direct video first frame rendered")
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            Log.i(TAG, "ExoPlayer state=$playbackState")
            onTelemetryChanged(currentTelemetry())
            mainHandler.removeCallbacks(telemetryTicker)
            if (playbackState == Player.STATE_READY || playbackState == Player.STATE_BUFFERING) {
                mainHandler.postDelayed(telemetryTicker, TELEMETRY_INTERVAL_MS)
            }
            if (playbackState == Player.STATE_ENDED) notifyPlaybackFinished()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            Log.i(TAG, "ExoPlayer isPlaying=$isPlaying")
            onTelemetryChanged(currentTelemetry())
        }

        override fun onPlayerError(error: PlaybackException) {
            pendingStartFraction = null
            Log.e(TAG, "ExoPlayer error (${error.errorCodeName})")
            onTelemetryChanged(currentTelemetry())
            notifyPlaybackFinished()
        }
    }

    private fun notifyPlaybackFinished() {
        val exo = player ?: return
        val item = exo.currentMediaItem ?: return
        val generation = playbackGeneration
        mainHandler.post {
            if (player === exo && playbackGeneration == generation && exo.currentMediaItem === item &&
                (exo.playbackState == Player.STATE_ENDED || exo.playbackState == Player.STATE_IDLE)
            ) {
                onPlaybackFinished()
            }
        }
    }

    @OptIn(UnstableApi::class)
    private fun ensurePlayer(): ExoPlayer {
        player?.let { return it }

        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(SAFARI_IOS_USER_AGENT)
            .setAllowCrossProtocolRedirects(true)

        val mediaSourceFactory = DefaultMediaSourceFactory(appContext)
            .setDataSourceFactory(httpDataSourceFactory)

        val trackSelector = DefaultTrackSelector(appContext).apply {
            parameters = buildUponParameters()
                    .build()
        }

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        return ExoPlayer.Builder(appContext)
            .setTrackSelector(trackSelector)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(audioAttributes, true)
            .build()
            .also { created ->
                created.setVideoSurfaceView(surfaceView)
                created.addListener(playerListener)
                player = created
            }
    }

    override fun prepareAndPlay(url: String, startPosition: Double): Boolean {
        if (url.isBlank() || !startPosition.isFinite()) return false
        playbackGeneration++
        val exo = ensurePlayer()
        val initialPositionMs = initialDirectPositionMs(startPosition, durationMs = 0L)
        pendingStartFraction = null
        exo.setMediaItem(
            MediaItem.fromUri(url),
            initialPositionMs?.takeIf { it > 0L } ?: C.TIME_UNSET
        )
        if (initialPositionMs == null) pendingStartFraction = startPosition
        exo.prepare()
        exo.playWhenReady = true
        onTelemetryChanged(currentTelemetry())
        return true
    }

    override fun play() {
        player?.play()
    }

    override fun pause() {
        player?.pause()
    }

    fun togglePlayback() {
        player?.let { exo ->
            if (exo.playWhenReady) exo.pause() else exo.play()
        }
    }

    override fun seekTo(positionSec: Double) {
        val exo = player ?: return
        if (!exo.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) return
        pendingStartFraction = null
        if (!positionSec.isFinite()) return
        val durationMs = exo.duration
        val maxMs = if (durationMs == C.TIME_UNSET || durationMs < 0L) Long.MAX_VALUE else durationMs
        val targetMs = (positionSec.coerceAtLeast(0.0) * 1000.0)
            .coerceAtMost(maxMs.toDouble()).toLong()
        exo.seekTo(targetMs)
    }

    override fun stopAndClear() {
        // Keep the player and its SurfaceView binding warm for the next AirPlay video.
        // Stop and remove the old media so stale frames/metadata cannot be reused.
        playbackGeneration++
        pendingStartFraction = null
        mainHandler.removeCallbacks(telemetryTicker)
        player?.let { exo ->
            exo.stop()
            exo.clearMediaItems()
        }
        onTelemetryChanged(currentTelemetry())
    }

    override fun currentTelemetry(): DirectPlaybackTelemetry {
        val exo = player ?: return DirectPlaybackTelemetry(
            durationSec = 0.0,
            positionSec = 0.0,
            rate = 0f,
            isPlaying = false,
            isBuffering = false
        )
        val rawDuration = exo.duration
        val durationSec = if (rawDuration == C.TIME_UNSET || rawDuration < 0L) {
            0.0
        } else {
            rawDuration / 1000.0
        }
        val positionSec = (exo.currentPosition.coerceAtLeast(0L)) / 1000.0
        val playing = exo.isPlaying
        return DirectPlaybackTelemetry(
            durationSec = durationSec,
            positionSec = positionSec,
            rate = if (playing) exo.playbackParameters.speed else 0f,
            isPlaying = playing,
            isBuffering = exo.playbackState == Player.STATE_BUFFERING,
            isReady = exo.playbackState == Player.STATE_READY
        )
    }

    fun release() {
        playbackGeneration++
        pendingStartFraction = null
        mainHandler.removeCallbacks(telemetryTicker)
        player?.let { exo ->
            exo.removeListener(playerListener)
            exo.release()
        }
        player = null
    }
}
