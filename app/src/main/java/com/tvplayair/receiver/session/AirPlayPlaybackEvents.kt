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
    private var standbyChannel: Channel? = null
    private var activeSession: SessionKey? = null
    private var currentEvent: Event? = null
    private var playbackId = 0L
    private var stopRequested = false

    @Synchronized
    fun attach(connection: Channel) {
        if (activeSession == connection.key) {
            channel?.close()
            channel = connection
            currentEvent?.let(connection::offer)
        } else {
            standbyChannel?.close()
            standbyChannel = connection
        }
    }

    @Synchronized
    fun detach(connection: Channel) {
        if (channel === connection) channel = null
        if (standbyChannel === connection) standbyChannel = null
    }

    @Synchronized
    fun start(key: SessionKey, onStart: (Long) -> Unit = {}) {
        if (activeSession != key) {
            if (currentEvent?.state != State.STOPPED) publish(State.STOPPED)
            val previous = channel
            channel = standbyChannel?.takeIf { it.key == key }
            if (channel != null || standbyChannel == null) {
                // Keep the old channel long enough to deliver STOPPED and allow it to resume.
                standbyChannel = previous
            } else {
                previous?.close()
            }
            activeSession = key
        }
        playbackId++
        stopRequested = false
        currentEvent = null
        publish(State.LOADING)
        onStart(playbackId)
    }

    @Synchronized
    fun control(key: SessionKey, stop: Boolean = false, dispatch: (Long) -> Unit) {
        val active = activeSession ?: return
        // Some senders omit the session header on controls. Preserve same-peer legacy control.
        if (active.peer != key.peer || (key.appleSessionId != null && key != active)) return
        if (stopRequested) return
        if (stop) {
            stopRequested = true
            publish(State.STOPPED)
        }
        dispatch()
    }

    @Synchronized
    fun update(id: Long, telemetry: DirectPlaybackTelemetry, onAccepted: () -> Unit = {}) {
        if (activeSession == null || id != playbackId || stopRequested) return
        onAccepted()
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
        standbyChannel?.close()
        standbyChannel = null
        activeSession = null
        currentEvent = null
    }
}
