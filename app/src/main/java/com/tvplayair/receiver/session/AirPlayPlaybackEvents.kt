package com.tvplayair.receiver.session

import com.tvplayair.receiver.video.DirectPlaybackTelemetry
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture

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

    data class Event(
        val state: State,
        val playbackId: Long,
        val customXmlBody: String? = null
    ) {
        fun request(appleSessionId: String?): ByteArray {
            val body = (customXmlBody ?: """
                <?xml version="1.0" encoding="UTF-8"?>
                <plist version="1.0"><dict>
                <key>category</key><string>video</string>
                <key>sessionID</key><integer>$playbackId</integer>
                <key>state</key><string>${state.value}</string>
                </dict></plist>
            """.trimIndent()).toByteArray(StandardCharsets.UTF_8)
            val contentType = if (customXmlBody != null) {
                "text/x-apple-plist+xml"
            } else {
                "application/x-apple-plist"
            }
            val header = buildString {
                append("POST /event HTTP/1.1\r\n")
                append("Content-Type: ").append(contentType).append("\r\n")
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
    private var reverseReady = CompletableFuture<Unit>()

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
        if (activeReverse() != null) reverseReady.complete(Unit)
    }

    @Synchronized
    fun detach(connection: Channel) {
        if (channel === connection) channel = null
        if (standbyChannel === connection) standbyChannel = null
        if (activeReverse() == null && reverseReady.isDone) {
            reverseReady = CompletableFuture()
        }
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
        reverseReady.cancel(false)
        reverseReady = CompletableFuture()
        if (activeReverse() != null) reverseReady.complete(Unit)
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
            reverseReady.cancel(false)
            publish(State.STOPPED)
        }
        dispatch(playbackId)
    }

    @Synchronized
    fun update(id: Long, telemetry: DirectPlaybackTelemetry, onAccepted: () -> Unit = {}) {
        if (activeSession == null || id != playbackId || stopRequested) return
        onAccepted()
        publish(State.from(telemetry))
    }

    @Synchronized
    fun finish(id: Long, onAccepted: () -> Unit = {}) {
        if (activeSession == null || id != playbackId || stopRequested) return
        stopRequested = true
        reverseReady.cancel(false)
        onAccepted()
        publish(State.STOPPED)
    }

    private fun publish(state: State) {
        if (activeSession == null || currentEvent?.state == state) return
        val event = Event(state, playbackId)
        currentEvent = event
        channel?.takeIf { it.key == activeSession }?.offer(event)
    }

    @Synchronized
    fun <T> withPlayback(id: Long, action: () -> T): T? =
        if (activeSession != null && playbackId == id && !stopRequested) action() else null

    @Synchronized
    fun reverseSignal(id: Long): CompletableFuture<Unit>? = withPlayback(id) { reverseReady }

    private fun activeReverse(): Channel? {
        val active = activeSession ?: return null
        return channel ?: standbyChannel?.takeIf {
            it.key.peer == active.peer &&
                (it.key.appleSessionId == null || active.appleSessionId == null)
        }
    }

    @Synchronized
    fun sendFcupRequest(url: String, requestId: Int, sessionIdOverride: Long? = null): Boolean {
        val activeChannel = activeReverse() ?: return false
        val escapedUrl = url
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
        val sid = sessionIdOverride ?: playbackId.coerceAtLeast(1L)
        val sessionUuid = activeChannel.key.appleSessionId ?: activeSession?.appleSessionId ?: "11111111-2222-3333-4444-555555555555"
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0">
            <dict>
                <key>sessionID</key>
                <integer>1</integer>
                <key>type</key>
                <string>unhandledURLRequest</string>
                <key>request</key>
                <dict>
                    <key>FCUP_Response_ClientInfo</key>
                    <integer>1</integer>
                    <key>FCUP_Response_ClientRef</key>
                    <integer>40030004</integer>
                    <key>FCUP_Response_RequestID</key>
                    <integer>$requestId</integer>
                    <key>FCUP_Response_URL</key>
                    <string>$escapedUrl</string>
                    <key>sessionID</key>
                    <integer>1</integer>
                    <key>FCUP_Response_Headers</key>
                    <dict>
                        <key>X-Playback-Session-Id</key>
                        <string>$sessionUuid</string>
                        <key>User-Agent</key>
                        <string>AppleCoreMedia/1.0.0.11B554a (Apple TV; U; CPU OS 7_0_4 like Mac OS X; en_us)</string>
                    </dict>
                </dict>
            </dict>
            </plist>
        """.trimIndent()
        activeChannel.offer(Event(State.LOADING, sid, customXmlBody = xml))
        return true
    }

    @Synchronized
    fun clear() {
        channel?.close()
        channel = null
        standbyChannel?.close()
        standbyChannel = null
        activeSession = null
        currentEvent = null
        reverseReady.cancel(false)
        reverseReady = CompletableFuture()
    }
}
