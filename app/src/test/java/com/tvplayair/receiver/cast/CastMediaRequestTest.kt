package com.tvplayair.receiver.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CastMediaRequestTest {
    @Test
    fun preservesSubsecondAndPausedLoads() {
        val url = "https://example.org/video.mp4"
        assertEquals(CastMediaRequest(url, 375L, false), CastMediaRequest.from(url, 375, false))
        assertEquals(CastMediaRequest(url, 0L, true), CastMediaRequest.from(url, 0, true))
        assertEquals(CastMediaRequest(url, null, true), CastMediaRequest.from(url, -1, true))
    }

    @Test
    fun rejectsUnsupportedOrIncompleteSources() {
        for (url in listOf(null, "", "file:///video.mp4", "content://video/1", "https:/video", "https://user:pass@example.org/video")) {
            assertNull(CastMediaRequest.from(url, 0, true))
        }
    }
}
