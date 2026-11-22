package com.tvplayair.receiver.session

import java.io.Closeable

/** Owned by one control connection and used on that connection's reader thread. */
internal class FairPlayNative : Closeable {
    private var handle = nativeCreate()

    fun handleFpSetup(body: ByteArray): ByteArray? = when (body.size) {
        16 -> nativeSetup(handle, body)
        164 -> nativeHandshake(handle, body)
        else -> null
    }

    fun decryptKey(ekey: ByteArray): ByteArray? = nativeDecryptKey(handle, ekey)

    override fun close() {
        nativeDestroy(handle)
        handle = 0
    }

    private external fun nativeCreate(): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeSetup(handle: Long, req16: ByteArray): ByteArray?
    private external fun nativeHandshake(handle: Long, req164: ByteArray): ByteArray?
    private external fun nativeDecryptKey(handle: Long, ekey72: ByteArray): ByteArray?

    companion object {
        init {
            System.loadLibrary("tvplayair_native")
        }
    }
}
