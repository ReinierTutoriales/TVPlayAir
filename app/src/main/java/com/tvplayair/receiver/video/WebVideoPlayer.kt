package com.tvplayair.receiver.video

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.SurfaceView
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.session.MediaSession
import com.tvplayair.receiver.diagnostics.DirectPlaybackDiagnostics
import com.tvplayair.receiver.diagnostics.FCUP_REQUEST_HEADER
import com.tvplayair.receiver.diagnostics.PLAYBACK_DIAGNOSTICS_TAG
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.io.IOException

/**
 * Direct 4K HDR web video player backed by Media3 ExoPlayer on a native SurfaceView.
 * Uses Safari iOS User-Agent and adaptive track selection for direct TV playback.
 */
@OptIn(UnstableApi::class)
class WebVideoPlayer(context: Context) : DirectVideoEngine {
    interface ViewCallbacks {
        fun onTelemetryChanged(telemetry: DirectPlaybackTelemetry)
        fun onMediaReady(playbackId: Long)
        fun onVideoAspectChanged(playbackId: Long, aspectRatio: Double) = Unit
        fun onPlaybackFinished(playbackId: Long)
        fun onSystemPlaybackIntent(playbackId: Long, playWhenReady: Boolean)
    }

    private var viewCallbacks: ViewCallbacks? = null
    private var surfaceView: SurfaceView? = null
    private var finishedPlaybackId: Long? = null

    var playbackId: Long? = null
        private set

    fun attachView(surface: SurfaceView, callbacks: ViewCallbacks) {
        surfaceView = surface
        viewCallbacks = callbacks
    }

    fun detachView(surface: SurfaceView) {
        if (surfaceView !== surface) return
        viewCallbacks = null
        player?.clearVideoSurfaceView(surface)
        surfaceView = null
    }

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
    private var mediaSession: MediaSession? = null
    private var systemPlayer: Player? = null

    internal val systemSession: MediaSession?
        get() = mediaSession

    internal fun setDetachedCallbacks(callbacks: ViewCallbacks) {
        check(surfaceView == null)
        viewCallbacks = callbacks
    }

    private var pendingStartFraction: Double? = null
    private var prepareStartedMs = 0L
    private val eventWindow = Timeline.Window()
    private val diagnostics = DirectPlaybackDiagnostics(SystemClock::elapsedRealtime) {
        Log.i(PLAYBACK_DIAGNOSTICS_TAG, it)
    }
    private var firstFrameLogged = false

    private fun playbackIdFor(eventTime: AnalyticsListener.EventTime): Long? {
        val id = playbackId ?: return null
        val timeline = eventTime.timeline
        val index = eventTime.windowIndex
        return id.takeIf {
            index in 0 until timeline.windowCount &&
                timeline.getWindow(index, eventWindow).mediaItem.mediaId == id.toString()
        }
    }

    private val videoEvents = object : AnalyticsListener {
        override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
            if (renderTimeMs < prepareStartedMs || output != surfaceView?.holder?.surface) return
            val id = playbackIdFor(eventTime) ?: return
            if (!firstFrameLogged) {
                firstFrameLogged = true
                Log.i(PLAYBACK_DIAGNOSTICS_TAG,
                    "route=direct id=$id event=first_frame elapsedMs=${renderTimeMs - prepareStartedMs}")
            }
            viewCallbacks?.onMediaReady(id)
        }

        override fun onVideoSizeChanged(eventTime: AnalyticsListener.EventTime, videoSize: VideoSize) {
            val id = playbackIdFor(eventTime) ?: return
            reportVideoSize(id, videoSize)
        }

        override fun onLoadError(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: LoadEventInfo,
            mediaLoadData: MediaLoadData,
            error: IOException,
            wasCanceled: Boolean
        ) {
            if (wasCanceled) return
            val http = error as? InvalidResponseCodeException
            val uri = loadEventInfo.dataSpec.uri
            val local = FcupLoadErrorHandlingPolicy.isFcupPlaylist(uri.host, uri.path)
            val source = if (local) "fcup" else "media"
            val request = if (local) http?.headerFields?.entries
                ?.firstOrNull { it.key.equals(FCUP_REQUEST_HEADER, ignoreCase = true) }
                ?.value?.firstOrNull()?.toIntOrNull() else null
            val label = "source=$source http=${http?.responseCode ?: "-"} request=${request ?: "-"} " +
                "error=${error.javaClass.simpleName} dataType=${mediaLoadData.dataType}"
            val id = playbackIdFor(eventTime) ?: if (local) {
                uri.pathSegments.getOrNull(1)?.toLongOrNull()
            } else null
            if (local && http?.responseCode == 410) {
                Log.i(PLAYBACK_DIAGNOSTICS_TAG, "route=direct id=${id ?: "-"} event=retired_load $label")
            } else if (id == null) {
                Log.i(PLAYBACK_DIAGNOSTICS_TAG, "route=direct id=- event=unattributed_load_error $label")
            } else {
                diagnostics.loadError(id, loadEventInfo.loadTaskId, label)
            }
        }

        override fun onLoadCompleted(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: LoadEventInfo,
            mediaLoadData: MediaLoadData
        ) {
            val uri = loadEventInfo.dataSpec.uri
            val id = playbackIdFor(eventTime) ?: if (FcupLoadErrorHandlingPolicy.isFcupPlaylist(uri.host, uri.path)) {
                uri.pathSegments.getOrNull(1)?.toLongOrNull()
            } else null
            diagnostics.loadCompleted(id, loadEventInfo.loadTaskId)
        }

        override fun onVideoInputFormatChanged(
            eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?
        ) {
            val id = playbackIdFor(eventTime) ?: return
            Log.i(TAG, "Video format id=$id mime=${format.sampleMimeType} codecs=${format.codecs} " +
                "size=${format.width}x${format.height} fps=${format.frameRate} color=${format.colorInfo}")
        }

        override fun onAudioInputFormatChanged(
            eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?
        ) {
            val id = playbackIdFor(eventTime) ?: return
            Log.i(TAG, "Audio format id=$id mime=${format.sampleMimeType} codecs=${format.codecs} " +
                "sampleRate=${format.sampleRate} channels=${format.channelCount}")
        }
    }

    private val telemetryTicker = object : Runnable {
        override fun run() {
            val exo = player ?: return
            viewCallbacks?.onTelemetryChanged(currentTelemetry())
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

        override fun onPlaybackStateChanged(playbackState: Int) {
            Log.i(TAG, "ExoPlayer state=$playbackState")
            viewCallbacks?.onTelemetryChanged(currentTelemetry())
            mainHandler.removeCallbacks(telemetryTicker)
            if (playbackState == Player.STATE_READY || playbackState == Player.STATE_BUFFERING) {
                mainHandler.postDelayed(telemetryTicker, TELEMETRY_INTERVAL_MS)
            }
            if (playbackState == Player.STATE_ENDED) {
                diagnostics.finish("ended")
                notifyPlaybackFinished()
            }
        }

        override fun onEvents(player: Player, events: Player.Events) {
            if (!events.containsAny(Player.EVENT_TRACKS_CHANGED, Player.EVENT_PLAYBACK_STATE_CHANGED,
                    Player.EVENT_MEDIA_METADATA_CHANGED)
            ) return
            viewCallbacks?.onTelemetryChanged(currentTelemetry())
            reportAudioReady(player)
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            viewCallbacks?.onTelemetryChanged(currentTelemetry())
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            Log.i(TAG, "ExoPlayer isPlaying=$isPlaying")
            if (isPlaying) diagnostics.playing(player?.currentMediaItem?.mediaId?.toLongOrNull())
            viewCallbacks?.onTelemetryChanged(currentTelemetry())
        }

        override fun onPlayerError(error: PlaybackException) {
            pendingStartFraction = null
            Log.e(TAG, "ExoPlayer error (${error.errorCodeName})")
            val detail = generateSequence<Throwable>(error) { it.cause }.take(6).toList()
            val http = detail.filterIsInstance<InvalidResponseCodeException>().firstOrNull()
            diagnostics.finish("player_error", " code=${error.errorCodeName} " +
                "http=${http?.responseCode ?: "-"} cause=${detail.last().javaClass.simpleName}")
            viewCallbacks?.onTelemetryChanged(currentTelemetry())
            notifyPlaybackFinished()
        }
    }

    private fun reportAudioReady(player: Player) {
        val id = playbackId ?: return
        if (player.currentMediaItem?.mediaId == id.toString() &&
            player.playbackState == Player.STATE_READY && isAudioOnly(player)
        ) viewCallbacks?.onMediaReady(id)
    }

    private fun isAudioOnly(player: Player): Boolean =
        player.currentTracks.isTypeSelected(C.TRACK_TYPE_AUDIO) &&
            !player.currentTracks.containsType(C.TRACK_TYPE_VIDEO)

    private fun reportVideoSize(id: Long, size: VideoSize) {
        if (size.width <= 0 || size.height <= 0 || size.pixelWidthHeightRatio <= 0f) return
        val aspectRatio = size.width.toDouble() * size.pixelWidthHeightRatio / size.height
        if (aspectRatio.isFinite()) viewCallbacks?.onVideoAspectChanged(id, aspectRatio)
    }

    private fun notifyPlaybackFinished() {
        val exo = player ?: return
        val item = exo.currentMediaItem ?: return
        val id = playbackId ?: return
        mainHandler.post {
            if (player === exo && playbackId == id && exo.currentMediaItem === item &&
                (exo.playbackState == Player.STATE_ENDED || exo.playbackState == Player.STATE_IDLE)
            ) {
                finishedPlaybackId = id
                releaseSystemControls()
                viewCallbacks?.onPlaybackFinished(id)
            }
        }
    }

    private fun ensurePlayer(): ExoPlayer {
        player?.let { return it }

        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(SAFARI_IOS_USER_AGENT)
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(15_000)
            .setAllowCrossProtocolRedirects(true)

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 30_000, 1_500, 3_000)
            .build()

        val mediaSourceFactory = DefaultMediaSourceFactory(appContext)
            .setDataSourceFactory(httpDataSourceFactory)
            .setLoadErrorHandlingPolicy(FcupLoadErrorHandlingPolicy())

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        return ExoPlayer.Builder(appContext)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttributes, true)
            .build()
            .also { created ->
                created.addListener(playerListener)
                created.addAnalyticsListener(videoEvents)
                player = created
            }
    }

    fun exposeSystemControls(id: Long) {
        val exo = player ?: return
        if (playbackId != id || mediaSession != null) return

        val controls = systemPlayer ?: createSystemPlayer(exo).also { systemPlayer = it }
        mediaSession = MediaSession.Builder(appContext, controls)
            .setCallback(object : MediaSession.Callback {
                override fun onConnectAsync(
                    session: MediaSession, controller: MediaSession.ControllerInfo
                ): ListenableFuture<MediaSession.ConnectionResult> {
                    val result = if (controller.isTrusted) {
                        MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                            .setAvailablePlayerCommands(
                                Player.Commands.Builder()
                                    .add(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
                                    .add(Player.COMMAND_GET_METADATA)
                                    .add(Player.COMMAND_PLAY_PAUSE)
                                    .add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
                                    .add(Player.COMMAND_SEEK_BACK)
                                    .add(Player.COMMAND_SEEK_FORWARD)
                                    .add(Player.COMMAND_STOP)
                                    .build()
                            )
                            .build()
                    } else {
                        MediaSession.ConnectionResult.reject()
                    }
                    return Futures.immediateFuture(result)
                }
            })
            .build()
    }

    private fun createSystemPlayer(exo: ExoPlayer): Player =
        object : ForwardingSimpleBasePlayer(exo) {
            override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
                val id = playbackId
                if (id != null && mediaSession != null) viewCallbacks?.onSystemPlaybackIntent(id, playWhenReady)
                return Futures.immediateVoidFuture()
            }

            override fun handleStop(): ListenableFuture<*> {
                val id = playbackId
                if (id != null && mediaSession != null) {
                    mainHandler.post { if (playbackId == id) viewCallbacks?.onPlaybackFinished(id) }
                }
                return Futures.immediateVoidFuture()
            }
        }

    private fun releaseSystemControls() {
        val session = mediaSession
        mediaSession = null
        session?.let {
            DirectPlaybackService.unpublish(it)
            it.release()
        }
    }

    override fun prepare(video: DirectPlaybackStart): Boolean {
        val surface = surfaceView ?: return false
        if (finishedPlaybackId == video.playbackId) return false
        val exo = ensurePlayer()
        prepareStartedMs = SystemClock.elapsedRealtime()
        if (playbackId == video.playbackId && exo.playbackState != Player.STATE_IDLE &&
            exo.playbackState != Player.STATE_ENDED
        ) {
            exo.playWhenReady = video.playWhenReady
            exo.setVideoSurfaceView(surface)
            reportVideoSize(video.playbackId, exo.videoSize)
            reportAudioReady(exo)
            return true
        }
        releaseSystemControls()
        finishedPlaybackId = null
        playbackId = video.playbackId
        firstFrameLogged = false
        diagnostics.start(video.playbackId)
        exo.setVideoSurfaceView(surface)
        pendingStartFraction = video.startFraction
        if (!video.playWhenReady) exo.playWhenReady = false
        exo.setMediaItem(
            MediaItem.Builder().setUri(video.url).setMediaId(video.playbackId.toString()).build(),
            video.initialPositionMs() ?: C.TIME_UNSET
        )
        exo.playWhenReady = video.playWhenReady
        exo.prepare()
        viewCallbacks?.onTelemetryChanged(currentTelemetry())
        return true
    }

    fun onOutputSurfaceRecreated() {
        prepareStartedMs = SystemClock.elapsedRealtime()
        player?.let { exo ->
            reportAudioReady(exo)
            val id = playbackId
            if (id != null &&
                exo.currentMediaItem?.mediaId == id.toString() &&
                exo.playbackState == Player.STATE_READY &&
                !exo.playWhenReady
            ) {
                viewCallbacks?.onMediaReady(id)
            }
        }
    }

    fun checkpointPositionMs(): Long? {
        val exo = player ?: return null
        if (playbackId == null || pendingStartFraction != null || exo.currentTimeline.isEmpty ||
            !exo.isCurrentMediaItemSeekable || exo.isCurrentMediaItemLive || exo.isPlayingAd ||
            exo.playbackState == Player.STATE_IDLE || exo.playbackState == Player.STATE_ENDED
        ) return null
        return exo.currentPosition.takeIf { it >= 0L }
    }

    override fun play() {
        player?.play()
    }

    override fun pause() {
        player?.pause()
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

    override fun stopAndClear(releasePlayer: Boolean) {
        resetPlaybackState()
        if (releasePlayer) {
            // Media3 stop() is asynchronous. Release before another decoder needs the VPU.
            releasePlayerInstance()
        } else {
            player?.let { exo ->
                exo.stop()
                exo.clearMediaItems()
            }
        }
        viewCallbacks?.onTelemetryChanged(currentTelemetry())
    }

    private fun resetPlaybackState() {
        diagnostics.finish("cleared")
        playbackId = null
        finishedPlaybackId = null
        releaseSystemControls()
        pendingStartFraction = null
        mainHandler.removeCallbacks(telemetryTicker)
    }

    private fun releasePlayerInstance() {
        val exo = player
        player = null
        systemPlayer = null
        exo?.removeListener(playerListener)
        exo?.release()
    }

    override fun currentTelemetry(): DirectPlaybackTelemetry {
        val exo = player ?: return DirectPlaybackTelemetry(
            durationSec = 0.0,
            positionSec = 0.0,
            rate = 0f,
            isPlaying = false,
            isBuffering = false
        )
        val hasCurrentItem = playbackId?.let { exo.currentMediaItem?.mediaId == it.toString() } == true
        val metadata = exo.mediaMetadata.takeIf { hasCurrentItem }
        val audioOnly = hasCurrentItem && isAudioOnly(exo)
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
            isReady = exo.playbackState == Player.STATE_READY,
            title = metadata?.title?.toString()?.trim()?.takeIf(String::isNotEmpty),
            canSeek = hasCurrentItem && exo.isCurrentMediaItemSeekable &&
                exo.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM),
            playWhenReady = hasCurrentItem && exo.playWhenReady,
            audioOnly = audioOnly,
            artist = metadata?.artist?.toString()?.takeIf { audioOnly && it.isNotBlank() },
            album = metadata?.albumTitle?.toString()?.takeIf { audioOnly && it.isNotBlank() },
            artwork = metadata?.artworkData?.takeIf { audioOnly }
        )
    }

    fun release() {
        surfaceView?.let(::detachView)
        viewCallbacks = null
        resetPlaybackState()
        releasePlayerInstance()
    }
}
