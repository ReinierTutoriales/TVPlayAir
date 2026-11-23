package com.tvplayair.receiver.session

import java.io.Closeable

/** Negotiated material belongs to the socket. The receiver identity is shared. */
internal class AirPlayConnectionCrypto(identityKey: () -> ByteArray) : Closeable {
    val pairing by lazy(LazyThreadSafetyMode.NONE) {
        AirPlayPairingSession(initialEdPrivateKey = identityKey())
    }

    private val fairPlayDelegate = lazy(LazyThreadSafetyMode.NONE) { FairPlayNative() }
    val fairPlay by fairPlayDelegate
    var fairplayAesKey: ByteArray? = null
    var sessionIv: ByteArray? = null
    @Volatile
    var mediaSessionId: String? = null
    var announcedAudioSdp: String? = null

    override fun close() {
        if (fairPlayDelegate.isInitialized()) fairPlay.close()
        fairplayAesKey?.fill(0)
        sessionIv?.fill(0)
        fairplayAesKey = null
        sessionIv = null
    }
}

/** A separate RAOP socket can carry mirror audio when both negotiated the same session UUID. */
internal fun canUseRaopDuringMirroring(mirrorOwner: AirPlayConnectionCrypto?, audioSource: Any): Boolean =
    mirrorOwner == null || mirrorOwner === audioSource ||
        mirrorOwner.mediaSessionId?.let { it == (audioSource as? AirPlayConnectionCrypto)?.mediaSessionId } == true
