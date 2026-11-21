package com.tvplayair.receiver.video

data class DirectPlaybackStart(
    val url: String,
    val airPlayPosition: Double,
    val playbackId: Long,
    val resumePositionMs: Long? = null,
    val playWhenReady: Boolean = true
) {
    fun initialPositionMs(): Long? =
        resumePositionMs ?: initialDirectPositionMs(airPlayPosition, durationMs = 0L)?.takeIf { it > 0L }

    val startFraction: Double?
        get() = airPlayPosition.takeIf { resumePositionMs == null && it > 0.0 && it <= 1.0 }

    fun withPlaybackIntent(command: String): DirectPlaybackStart {
        val requested = when (command) {
            "play" -> true
            "pause" -> false
            "toggle" -> !playWhenReady
            else -> return this
        }
        return if (requested == playWhenReady) this else copy(playWhenReady = requested)
    }

    fun withCheckpoint(positionMs: Long): DirectPlaybackStart =
        if (positionMs < 0L || positionMs == resumePositionMs) this else copy(resumePositionMs = positionMs)
}

internal const val NO_DIRECT_CHECKPOINT_MS = -1L
