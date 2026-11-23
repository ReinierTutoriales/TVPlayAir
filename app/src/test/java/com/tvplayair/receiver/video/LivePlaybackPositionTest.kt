package com.tvplayair.receiver.video

import org.junit.Assert.assertEquals
import org.junit.Test

class LivePlaybackPositionTest {
    @Test
    fun senderPositionSurvivesWindowSlidesAndResetsForTheNextItem() {
        val position = LivePlaybackPosition()
        assertEquals(0.0, position.update(100_000, 30_000), 0.001)
        assertEquals(10.0, position.update(125_000, 15_000), 0.001)
        assertEquals(10.0, position.update(120_000, 18_000), 0.001)
        assertEquals(15.0, position.update(130_000, 15_000), 0.001)

        position.reset()
        assertEquals(0.0, position.update(0, 5000), 0.001)
    }
}
