package com.tvplayair.receiver.session

import java.nio.ByteBuffer

object NativeSharedMemory {
    init {
        System.loadLibrary("tvplayair_native")
    }

    external fun nativeCreateProducerBuffer(kind: Int): Int
    external fun nativeMapConsumerBuffer(kind: Int, fd: Int, bytes: Long): Boolean
    external fun nativeReadVideoPacket(directBuffer: ByteBuffer, generationId: Int): Int
    external fun nativeWriteVideoFrame(
        directBuffer: ByteBuffer,
        size: Int,
        ptsUs: Long,
        isHevc: Boolean,
        isLengthPrefixed: Boolean,
        generationId: Int,
        extraFlags: Int
    ): Int
    external fun nativeWriteAudioPcm(
        directBuffer: ByteBuffer,
        byteCount: Int,
        generationId: Int
    ): Int
    external fun nativeGetVideoDroppedPackets(): Long
    external fun nativeGetAudioDroppedPackets(): Long
    external fun nativeDetachConsumerBuffers()
    external fun nativeDestroyProducerBuffers()
}
