package com.tvplayair.receiver.session

import java.nio.ByteBuffer

object NativeSharedMemory {
    init {
        System.loadLibrary("tvplayair_native")
    }

    external fun nativeCreateProducerBuffer(kind: Int): Int
    external fun nativeMapConsumerBuffer(kind: Int, fd: Int, bytes: Long): Boolean
    external fun nativeReadVideoPacket(directBuffer: ByteBuffer, generationId: Int): Int
    external fun nativeDetachConsumerBuffers()
    external fun nativeDestroyProducerBuffers()
}
