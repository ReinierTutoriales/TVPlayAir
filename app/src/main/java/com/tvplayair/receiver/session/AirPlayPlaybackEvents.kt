package com.tvplayair.receiver.session

import com.tvplayair.receiver.video.DirectPlaybackTelemetry
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ArrayBlockingQueue

internal class AirPlayPlaybackEvents {
    data class SessionKey(val peer: String, val appleSessionId: String?)

    enum class State(val value: String) {
        LOADING("loading"), PLAYING("playing"), PAUSED("paused"), STOPPED("stopped");

        companion object {
            fun from(telemetry: DirectPlaybackTelemetry): State = when {
                telemetry.isBuffering -> LOADING
                telemetry.isPlaying -> PLAYING
                telemetry.isReady -> PAUSED
                else -> STOPPED
            }
        }
    }

    data class Event(val state: State, val playbackId: Long) {
        fun request(appleSessionId: String?): ByteArray {
            val body = """
                <?xml version="1.0" encoding="UTF-8"?>
                <plist version="1.0"><dict>
                <key>category</key><string>video</string>
                <key>sessionID</key><integer>$playbackId</integer>
                <key>state</key><string>${state.value}</string>
                </dict></plist>
            """.trimIndent().toByteArray(StandardCharsets.UTF_8)
            val header = buildString {
                append("POST /event HTTP/1.1\r\n")
                append("Content-Type: application/x-apple-plist\r\n")
                append("Content-Length: ").append(body.size).append("\r\n")
                if (appleSessionId != null) {
                    append("X-Apple-Session-ID: ").append(appleSessionId).append("\r\n")
                }
                append("\r\n")
            }
            return header.toByteArray(StandardCharsets.US_ASCII) + body
        }
    }

    class Channel(val key: SessionKey, val socket: Socket) {
        val events = ArrayBlockingQueue<Event>(8)

        fun offer(event: Event) {
            if (!events.offer(event)) {
                events.poll()
                events.offer(event)
            }
        }

        fun close() {
            runCatching { socket.close() }
        }
    }

    private var channel: Channel? = null
    private var activeSession: SessionKey? = null
    private var currentEvent: Event? = null
    private var playbackId = 0L

    @Synchronized
    fun attach(connection: Channel): Boolean {
        if (activeSession != null && activeSession != connection.key &&
            currentEvent?.state != State.STOPPED && channel?.key == activeSession
        ) return false
        channel?.close()
        channel = connection
        if (activeSession == connection.key) currentEvent?.let(connection::offer)
        return true
    }

    @Synchronized
    fun detach(connection: Channel) {
        if (channel === connection) channel = null
    }

    @Synchronized
    fun start(key: SessionKey) {
        if (activeSession != key && currentEvent?.state != State.STOPPED) publish(State.STOPPED)
        activeSession = key
        playbackId++
        currentEvent = null
        publish(State.LOADING)
    }

    @Synchronized
    fun update(telemetry: DirectPlaybackTelemetry) {
        if (activeSession == null) return
        publish(State.from(telemetry))
    }

    private fun publish(state: State) {
        if (activeSession == null || currentEvent?.state == state) return
        val event = Event(state, playbackId)
        currentEvent = event
        channel?.takeIf { it.key == activeSession }?.offer(event)
    }

    @Synchronized
    fun clear() {
        channel?.close()
        channel = null
        activeSession = null
        currentEvent = null
    }
}
