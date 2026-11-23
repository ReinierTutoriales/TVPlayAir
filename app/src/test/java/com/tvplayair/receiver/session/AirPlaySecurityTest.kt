package com.tvplayair.receiver.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AirPlaySecurityTest {
    @Test
    fun fcupTargetUrlAllowsWebAndCustomAirPlaySchemesOnly() {
        assertTrue(isAllowedFcupTargetUrl("mlhls://localhost/master.m3u8"))
        assertTrue(isAllowedFcupTargetUrl("https://example.com/playlist.m3u8"))
        assertTrue(isAllowedFcupTargetUrl("http://192.168.1.50/stream.m3u8"))
        assertFalse(isAllowedFcupTargetUrl("file:///etc/passwd"))
        assertFalse(isAllowedFcupTargetUrl("content://media/external/video/1"))
        assertFalse(isAllowedFcupTargetUrl("javascript:alert(1)"))
        assertFalse(isAllowedFcupTargetUrl("data:text/plain,hello"))
        assertFalse(isAllowedFcupTargetUrl("/relative/path"))
    }
}
