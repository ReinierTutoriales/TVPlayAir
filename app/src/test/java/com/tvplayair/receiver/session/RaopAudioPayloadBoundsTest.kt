package com.tvplayair.receiver.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RaopAudioPayloadBoundsTest {
    @Test
    fun decoderPcmUsesTheReportedChannelCountAndByteRange() {
        val input = java.nio.ByteBuffer.allocate(8).order(java.nio.ByteOrder.nativeOrder())
        input.putShort(2, 123).putShort(4, -456)
        val output = ShortArray(4)
        assertEquals(2, copyPcm16ToStereo(input, 2, 4, 1, output))
        assertTrue(output.contentEquals(shortArrayOf(123, 123, -456, -456)))
        assertEquals(1, copyPcm16ToStereo(input, 2, 4, 2, output))
        assertEquals(123, output[0].toInt())
        assertEquals(-456, output[1].toInt())
        assertEquals(0, copyPcm16ToStereo(input, 2, 3, 2, output))
        assertEquals(0, copyPcm16ToStereo(input, 2, 6, 3, output))
    }

    @Test
    fun malformedSlicesAreRejectedBeforeNativeOrCodecAccess() {
        val decoder = RaopAudioDecoder()
        val packet = byteArrayOf(0, 1, 2, 3)
        decoder.decodeAndPublish(packet, -1, 4, 1, 0L)
        decoder.decodeAndPublish(packet, 0, -1, 1, 0L)
        decoder.decodeAndPublish(packet, 5, 1, 1, 0L)
        decoder.decodeAndPublish(packet, 2, 3, 1, 0L)
        decoder.decodeAndPublish(packet, Int.MAX_VALUE, Int.MAX_VALUE, 1, 0L)
        assertTrue(decoder.pcmStats().contains("pcmProduced=0"))
    }
}
