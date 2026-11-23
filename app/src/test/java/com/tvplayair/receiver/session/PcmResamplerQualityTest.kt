package com.tvplayair.receiver.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PcmResamplerQualityTest {
    @Test
    fun `cubic Hermite resampler preserves full-scale bounds without overflow`() {
        val resampler = PcmResampler()
        val src = shortArrayOf(
            Short.MIN_VALUE, Short.MAX_VALUE,
            Short.MAX_VALUE, Short.MIN_VALUE,
            Short.MIN_VALUE, Short.MAX_VALUE,
            Short.MAX_VALUE, Short.MIN_VALUE
        )
        val dst = ShortArray(16)
        val outFrames = resampler.resampleStereoI16(src, 4, 44_100, dst)
        assertEquals(4, outFrames)
        for (i in 0 until outFrames * 2) {
            assertTrue(dst[i].toInt() in Short.MIN_VALUE.toInt()..Short.MAX_VALUE.toInt())
        }
    }

    @Test
    fun `cubic Hermite resampler tracks smooth ramp accurately`() {
        val resampler = PcmResampler()
        val srcFrames = 441
        val src = ShortArray(srcFrames * 2) { idx ->
            val frame = idx / 2
            (frame * 50).toShort()
        }
        val dst = ShortArray(480 * 2)
        val outFrames = resampler.resampleStereoI16(src, srcFrames, 44_100, dst)
        assertEquals(480, outFrames)
        // Midpoint frame ~240 should be close to 220 * 50 = 11000
        assertTrue(abs(dst[240 * 2] - 11_000) < 250)
    }
}
