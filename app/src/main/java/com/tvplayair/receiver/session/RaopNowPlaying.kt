package com.tvplayair.receiver.session

import java.nio.ByteBuffer

data class RaopTrack(val title: String?, val artist: String?, val album: String?)

data class RaopRemote(val dacpId: String, val activeRemote: String)

data class RaopNowPlaying(
    val track: RaopTrack? = null,
    val artwork: ByteArray? = null,
    val remote: RaopRemote? = null
)

internal object RaopMetadataParser {
    private const val ITEM = 0x6D6C6974 // mlit
    private const val TITLE = 0x6D696E6D // minm
    private const val ARTIST = 0x61736172 // asar
    private const val ALBUM = 0x6173616C // asal

    fun track(body: ByteArray): RaopTrack? {
        val bytes = ByteBuffer.wrap(body)
        if (bytes.remaining() < 8) return null
        if (bytes.getInt(0) == ITEM) {
            val size = bytes.getInt(4)
            if (size !in 0..(body.size - 8)) return null
            bytes.position(8).limit(8 + size)
        }
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        while (bytes.remaining() >= 8) {
            val key = bytes.int
            val size = bytes.int
            if (size !in 0..bytes.remaining()) return null
            val start = bytes.position()
            if (size <= 2048 && (key == TITLE || key == ARTIST || key == ALBUM)) {
                val value = String(body, start, size, Charsets.UTF_8).trim().takeIf { it.isNotEmpty() }
                when (key) {
                    TITLE -> title = value
                    ARTIST -> artist = value
                    ALBUM -> album = value
                }
            }
            bytes.position(start + size)
        }
        if (bytes.hasRemaining() || (title == null && artist == null && album == null)) return null
        return RaopTrack(title, artist, album)
    }

    fun timestamp(rtpInfo: String?): Long? = rtpInfo?.split(';', ',')
        ?.firstOrNull { it.substringBefore('=').trim().equals("rtptime", ignoreCase = true) }
        ?.substringAfter('=')?.trim()?.toLongOrNull()?.takeIf { it in 0..0xFFFF_FFFFL }
}

internal class RaopNowPlayingSession(private val publish: (RaopNowPlaying?) -> Unit) {
    private var owner: Any? = null
    private var timestamp: Long? = null
    private var trackTimestampUnconfirmed = false
    private var current = RaopNowPlaying()

    @Synchronized
    fun activate(source: Any, remote: RaopRemote? = null) {
        if (owner === source) return
        owner = source
        timestamp = null
        trackTimestampUnconfirmed = false
        current = RaopNowPlaying(remote = remote)
        publish(current)
    }

    @Synchronized
    fun track(source: Any, at: Long?, track: RaopTrack, remote: RaopRemote? = null) {
        if (owner !== source || !advance(at)) return
        if (at == null && timestamp != null && current.track != track) trackTimestampUnconfirmed = true
        if (at != null) trackTimestampUnconfirmed = false
        val next = current.copy(
            track = track,
            artwork = current.artwork.takeIf { current.track == null || current.track == track },
            remote = remote ?: current.remote
        )
        if (current == next) return
        current = next
        publish(current)
    }

    @Synchronized
    fun artwork(source: Any, at: Long?, artwork: ByteArray?) {
        if (owner !== source || (timestamp != null && at == null) ||
            (trackTimestampUnconfirmed && at == timestamp) || !advance(at)
        ) return
        current = current.copy(artwork = artwork)
        publish(current)
    }

    @Synchronized
    fun updateRemote(source: Any, remote: RaopRemote) {
        if (owner !== source || current.remote == remote) return
        current = current.copy(remote = remote)
        publish(current)
    }

    @Synchronized
    fun clear(source: Any? = null) {
        if (owner == null || (source != null && owner !== source)) return
        owner = null
        timestamp = null
        trackTimestampUnconfirmed = false
        current = RaopNowPlaying()
        publish(null)
    }

    private fun advance(at: Long?): Boolean {
        val last = timestamp
        if (at == null) return true
        if (last != null && (at - last).toInt() < 0) return false
        if (at != last) {
            timestamp = at
            trackTimestampUnconfirmed = false
            current = RaopNowPlaying(remote = current.remote)
        }
        return true
    }
}
