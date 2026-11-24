package com.tvplayair.receiver.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MirrorSpsDimensionsTest {
    @Test
    fun h264ReadsTallPortraitDimensionsAndCropping() {
        val sps = "000000016742c032da014c05a7967c0440000003004000000c83c60ca8"
        assertEquals(1320 to 2868, mirrorSpsDimensions(hex(sps), hevc = false))
    }

    @Test
    fun hevcReadsVisibleDimensionsFromAnnexBConfig() {
        val sps = "00000142010101600000030090000003000003005aa00f080361f36595952930bc05a02000000300200000030321"
        assertEquals(480 to 854, mirrorSpsDimensions(hex(sps), hevc = true))
    }

    @Test
    fun invalidOrMissingSpsAllowsTheCallerToUseHeaderDimensions() {
        assertNull(mirrorSpsDimensions(hex("0000000167"), hevc = false))
        assertNull(mirrorSpsDimensions(hex("00000142"), hevc = true))
        assertNull(mirrorSpsDimensions(hex("0000000168ff"), hevc = false))
    }

    private fun hex(value: String): ByteArray =
        value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
