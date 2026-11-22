package com.tvplayair.receiver.video

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FcupLoadErrorHandlingPolicyTest {
    @Test
    fun onlyLocalFcupPlaylistsQualifyForGatewayTimeoutFallback() {
        assertTrue(FcupLoadErrorHandlingPolicy.isFcupPlaylist("127.0.0.1", "/__fcup/1/playlist.m3u8"))
        assertFalse(FcupLoadErrorHandlingPolicy.isFcupPlaylist("example.com", "/__fcup/1/playlist.m3u8"))
        assertFalse(FcupLoadErrorHandlingPolicy.isFcupPlaylist("127.0.0.1", "/video/playlist.m3u8"))
        assertFalse(FcupLoadErrorHandlingPolicy.isFcupPlaylist("127.0.0.1", "/__fcup/1/video.ts"))
    }
}
