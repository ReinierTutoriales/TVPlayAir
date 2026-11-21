package com.tvplayair.receiver.session

import android.util.Log
import java.io.BufferedInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

object AirPlayTimingPacket {
    const val PACKET_BYTES = 32
    const val PT_TIMING_REQUEST = 0xD2 // 0x80 | 82
    const val PT_TIMING_RESPONSE = 0xD3 // 0x80 | 83
    private const val NTP_UNIX_EPOCH_DELTA_SEC = 2_208_988_800L

    fun currentNtpTimestamp(nowMillis: Long = System.currentTimeMillis()): Long {
        val secs = (nowMillis / 1000L) + NTP_UNIX_EPOCH_DELTA_SEC
        val fracMillis = nowMillis % 1000L
        val frac = (fracMillis shl 32) / 1000L
        return (secs shl 32) or (frac and 0xFFFFFFFFL)
    }

    fun buildResponse(request: ByteArray, length: Int, nowMillis: Long = System.currentTimeMillis()): ByteArray? {
        if (length < PACKET_BYTES || request.size < PACKET_BYTES) return null
        val reqType = request[1].toInt() and 0xFF
        if (reqType != PT_TIMING_REQUEST) return null

        val ntpNow = currentNtpTimestamp(nowMillis)
        val resp = ByteArray(PACKET_BYTES)
        resp[0] = 0x80.toByte()
        resp[1] = PT_TIMING_RESPONSE.toByte()
        resp[2] = 0x00
        resp[3] = 0x07
        // Reference timestamp (bytes 8..15) = client's transmit timestamp (bytes 24..31)
        System.arraycopy(request, 24, resp, 8, 8)
        val bb = ByteBuffer.wrap(resp).order(ByteOrder.BIG_ENDIAN)
        bb.putLong(16, ntpNow) // Receive timestamp
        bb.putLong(24, ntpNow) // Transmit timestamp
        return resp
    }
}

/**
 * UDP NTP timing responder (port 6002) and persistent TCP event endpoint (port 7101)
 * required by iOS between initial SETUP and stream SETUP/RECORD.
 */
class AirPlayTimingAndEventServers {
    companion object {
        private const val TAG = "AirPlayTimingEvent"
        const val DEFAULT_EVENT_PORT = 7101
        private const val EVENT_SO_TIMEOUT_MS = 60_000
    }

    private val running = AtomicBoolean(false)
    @Volatile
    private var timingSocket: DatagramSocket? = null
    @Volatile
    private var eventServerSocket: ServerSocket? = null
    private var timingThread: Thread? = null
    private var eventThread: Thread? = null

    fun start(
        timingPort: Int = RaopAudioRtpReceiver.DEFAULT_TIMING_PORT,
        eventPort: Int = DEFAULT_EVENT_PORT
    ): Boolean {
        if (running.get()) return true

        val udp = runCatching { DatagramSocket(timingPort) }.onFailure {
            Log.e(TAG, "Failed to bind timing UDP port $timingPort", it)
        }.getOrNull() ?: return false
        val tcp = runCatching { ServerSocket(eventPort) }.onFailure {
            Log.e(TAG, "Failed to bind event TCP port $eventPort", it)
        }.getOrNull()
        if (tcp == null) {
            udp.close()
            return false
        }

        timingSocket = udp
        eventServerSocket = tcp
        running.set(true)
        timingThread = Thread({ runTimingLoop(udp) }, "TVPlayAir-TimingUdp").apply {
            isDaemon = true
            start()
        }
        eventThread = Thread({ runEventAcceptLoop(tcp) }, "TVPlayAir-EventTcp").apply {
            isDaemon = true
            start()
        }
        Log.i(TAG, "AirPlay timing UDP $timingPort and event TCP $eventPort listening")
        return true
    }

    fun stop() {
        running.set(false)
        runCatching { timingSocket?.close() }
        timingSocket = null
        runCatching { eventServerSocket?.close() }
        eventServerSocket = null
        timingThread?.interrupt()
        timingThread = null
        eventThread?.interrupt()
        eventThread = null
    }

    private fun runTimingLoop(udp: DatagramSocket) {
        val rx = ByteArray(128)
        val packet = DatagramPacket(rx, rx.size)
        while (running.get()) {
            val ok = runCatching { udp.receive(packet); true }.getOrDefault(false)
            if (!ok) continue
            val reply = AirPlayTimingPacket.buildResponse(rx, packet.length) ?: continue
            runCatching {
                udp.send(DatagramPacket(reply, reply.size, packet.address, packet.port))
            }
        }
    }

    private fun runEventAcceptLoop(server: ServerSocket) {
        while (running.get()) {
            val client = runCatching { server.accept() }.getOrNull() ?: continue
            Thread({ handleEventClient(client) }, "TVPlayAir-EventClient").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun handleEventClient(client: Socket) {
        client.use { sock ->
            sock.soTimeout = EVENT_SO_TIMEOUT_MS
            val input = BufferedInputStream(sock.getInputStream())
            val output = sock.getOutputStream()
            val buf = ByteArray(2048)
            while (running.get()) {
                val read = runCatching { input.read(buf) }.getOrDefault(-1)
                if (read <= 0) break
                val text = String(buf, 0, read, StandardCharsets.US_ASCII)
                if (text.startsWith("POST ") || text.startsWith("GET ")) {
                    val okResp = "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n"
                    runCatching {
                        output.write(okResp.toByteArray(StandardCharsets.US_ASCII))
                        output.flush()
                    }
                }
            }
        }
    }
}
