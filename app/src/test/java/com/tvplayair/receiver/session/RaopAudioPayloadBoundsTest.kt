package com.tvplayair.receiver.session

import org.junit.Test

class RaopAudioPayloadBoundsTest {
    @Test
    fun malformedSlicesAreRejectedBeforeNativeOrCodecAccess() {
        val decoder = RaopAudioDecoder()
        val packet = byteArrayOf(0, 1, 2, 3)
        decoder.decodeAndPublish(packet, -1, 4, 1, 0L)
        decoder.decodeAndPublish(packet, 0, -1, 1, 0L)
        decoder.decodeAndPublish(packet, 5, 1, 1, 0L)
        decoder.decodeAndPublish(packet, 2, 3, 1, 0L)
        decoder.decodeAndPublish(packet, Int.MAX_VALUE, Int.MAX_VALUE, 1, 0L)
    }
}
