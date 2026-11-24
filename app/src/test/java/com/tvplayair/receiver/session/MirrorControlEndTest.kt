package com.tvplayair.receiver.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MirrorControlEndTest {
    @Test
    fun closingControlWithoutVideoTeardownClearsOnlyTheOwningMirrorSetup() {
        val mirror = AirPlayMirrorStreamReceiver({ _, _, _, _, _, _, _ -> }, {})
        val oldOwner = AirPlayConnectionCrypto { ByteArray(32) }
        val replacement = AirPlayConnectionCrypto { ByteArray(32) }
        val server = RaopRtspServer(
            listener = object : AirPlayProtocolListener {
                override fun onVideoConfigChanged(
                    mimeType: String, width: Int, height: Int, generationId: Int,
                    codecConfig: ByteArray, opensProjection: Boolean
                ) = Unit
                override fun onDirectVideoUrl(url: String, startPosition: Double, playbackId: Long) = Unit
                override fun onDirectVideoCommand(command: String, value: Double, playbackId: Long) = Unit
                override fun onClientConnected(clientName: String, dacpId: String, activeRemote: String) = Unit
                override fun onMirrorStreamEnded(generationId: Int) = Unit
            },
            configProvider = { AirPlayServerConfig() },
            mirrorReceiver = mirror,
            connectionCrypto = { oldOwner }
        )
        val key = ByteArray(16)
        mirror.configureStreamCipherCandidates(oldOwner, key, null, 1L)
        assertFalse(mirror.allowsAudio(replacement))
        server.endControlSession(oldOwner, "rtsp_peer_eof")
        assertTrue(mirror.allowsAudio(replacement))

        mirror.configureStreamCipherCandidates(replacement, key, null, 2L)
        server.endControlSession(oldOwner, "rtsp_peer_eof")
        assertFalse(mirror.allowsAudio(oldOwner))
        assertTrue(mirror.allowsAudio(replacement))
    }
}
