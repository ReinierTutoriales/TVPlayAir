package com.tvplayair.receiver.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MirrorPresentationSizeTest {
    @Test
    fun distortedPortraitCropKeepsSourceAspect() {
        assertEquals(498 to 1080, mirrorPresentationSize(498, 1080, 498, 1080, 484, 712))
        assertEquals(498 to 1080, mirrorPresentationSize(498, 1080, 484, 712, 484, 712))
    }

    @Test
    fun encoderPaddingCropIsAccepted() {
        assertEquals(1920 to 1080, mirrorPresentationSize(1920, 1080, 1920, 1088, 1920, 1080))
    }

    @Test
    fun tallSourceStaysTallWhenOutputIsScaledToThePanel() {
        assertEquals(1320 to 2868, mirrorPresentationSize(1320, 2868, 1920, 1080, 1920, 1080))
    }

    @Test
    fun invalidCropFallsBackToCodedSizeWithoutAnInputSize() {
        assertEquals(1920 to 1080, mirrorPresentationSize(0, 0, 1920, 1080, 2048, 1080))
        assertNull(mirrorPresentationSize(0, 0, 0, 0, 0, 0))
    }
}
