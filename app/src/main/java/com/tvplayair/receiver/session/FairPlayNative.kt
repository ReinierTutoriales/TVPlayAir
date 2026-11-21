package com.tvplayair.receiver.session

object FairPlayNative {
    init {
        System.loadLibrary("tvplayair_native")
    }

    external fun nativeSetup(req16: ByteArray): ByteArray?
    external fun nativeHandshake(req164: ByteArray): ByteArray?
    external fun nativeDecryptKey(ekey72: ByteArray): ByteArray?
    external fun nativeReset()

    fun handleFpSetup(body: ByteArray): ByteArray? = when (body.size) {
        16 -> nativeSetup(body)
        164 -> nativeHandshake(body)
        else -> null
    }
}
