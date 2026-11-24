package com.tvplayair.receiver.session

import java.net.InetSocketAddress
import java.net.ServerSocket

internal fun openAirPlayListener(port: Int): ServerSocket {
    val socket = ServerSocket()
    try {
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(port))
        return socket
    } catch (error: Exception) {
        runCatching { socket.close() }
        throw error
    }
}
