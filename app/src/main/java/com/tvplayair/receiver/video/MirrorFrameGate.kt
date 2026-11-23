package com.tvplayair.receiver.video

/**
 * Pure, deterministic codec-configuration cache (SPS/PPS/VPS) and strict IDR gate.
 * Ensures that after start, reconfigure, or returning from ExoPlayer, no non-IDR
 * frames are submitted to MediaCodec until a clean keyframe arrives.
 */
class MirrorFrameGate {
    companion object {
        const val PACKET_HEADER_BYTES = 16
        const val FLAG_CODEC_CONFIG = 1 shl 0
        const val FLAG_KEY_FRAME = 1 shl 1
    }

    private var cachedCodecConfig: ByteArray? = null
    private var awaitingIdr = true

    fun matchesCodecConfig(data: ByteArray): Boolean = cachedCodecConfig.contentEquals(data)

    fun onCodecConfig(data: ByteArray): Boolean {
        if (data.isEmpty() || matchesCodecConfig(data)) return false
        cachedCodecConfig = data.copyOf()
        return true
    }

    fun getCachedCodecConfig(): ByteArray? = cachedCodecConfig?.copyOf()

    /**
     * Re-arms the strict IDR gate while retaining cached SPS/PPS/VPS for reinjection.
     */
    fun resetGate() {
        awaitingIdr = true
    }

    /**
     * Clears both cached codec configuration and IDR gate state (e.g. codec or session reset).
     */
    fun clearAll() {
        cachedCodecConfig = null
        awaitingIdr = true
    }

    fun shouldSubmitFrame(flags: Int): Boolean =
        !awaitingIdr || (flags and (FLAG_CODEC_CONFIG or FLAG_KEY_FRAME)) != 0

    fun onFrameSubmitted(flags: Int) {
        if ((flags and FLAG_CODEC_CONFIG) == 0 && (flags and FLAG_KEY_FRAME) != 0) awaitingIdr = false
    }
}
