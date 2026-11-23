package com.tvplayair.receiver.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ArtworkPipelineTest {
    @Test
    fun artworkKeyMatchesIdenticalBytesAcrossDistinctArrays() {
        val first = byteArrayOf(1, 2, 3, 4, 5)
        val second = byteArrayOf(1, 2, 3, 4, 5)
        val different = byteArrayOf(1, 2, 3, 4, 6)

        assertEquals(first.artworkKey(), second.artworkKey())
        assertNotEquals(first.artworkKey(), different.artworkKey())
    }

    @Test
    fun centerCropRectNormalizesLandscapePortraitAndSquareUniformly() {
        assertArrayEquals(intArrayOf(420, 0, 1080), calculateCenterCropRect(1920, 1080))
        assertArrayEquals(intArrayOf(0, 420, 1080), calculateCenterCropRect(1080, 1920))
        assertArrayEquals(intArrayOf(0, 0, 600), calculateCenterCropRect(600, 600))
        assertArrayEquals(intArrayOf(0, 0, 0), calculateCenterCropRect(0, 500))
    }

    @Test
    fun inSampleSizeUsesPowerOfTwoAboveTargetPixels() {
        assertEquals(1, calculateInSampleSize(512, 512, 512))
        assertEquals(2, calculateInSampleSize(1920, 1080, 512))
        assertEquals(4, calculateInSampleSize(2400, 2400, 512))
        assertEquals(1, calculateInSampleSize(300, 300, 512))
    }
}
