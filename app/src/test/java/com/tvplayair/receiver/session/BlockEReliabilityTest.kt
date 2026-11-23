package com.tvplayair.receiver.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockEReliabilityTest {
    @Test
    fun `BinaryPlistWriter round-trips non-ASCII UTF-16BE strings`() {
        val original = mapOf(
            "title" to "Música & Películas — TVPlayAir",
            "ascii" to "AppleTV3,2"
        )
        val encoded = BinaryPlistWriter.encode(original)
        val parsed = BinaryPlistParser.parse(encoded) as? Map<*, *>
        assertTrue(parsed != null)
        assertEquals("Música & Películas — TVPlayAir", parsed!!["title"])
        assertEquals("AppleTV3,2", parsed["ascii"])
    }
}
