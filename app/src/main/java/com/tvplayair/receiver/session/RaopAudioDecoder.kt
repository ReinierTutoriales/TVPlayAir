package com.tvplayair.receiver.session

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class RaopAudioCodec {
    PCM_L16_48K,
    PCM_L16_44K1,
    AAC_LC,
    AAC_ELD
}

/**
 * Allocation-free stereo 16-bit PCM resampler (e.g. 44.1 kHz -> 48 kHz)
 * normalizing incoming RAOP audio to the fixed 48 kHz Oboe ring format.
 */
object PcmResampler {
    const val TARGET_SAMPLE_RATE = 48000
    const val CHANNELS = 2

    fun resampleStereoI16(
        src: ShortArray,
        srcFrames: Int,
        srcRate: Int,
        dst: ShortArray,
        targetRate: Int = TARGET_SAMPLE_RATE
    ): Int {
        if (srcFrames <= 0 || srcRate <= 0 || targetRate <= 0) return 0
        if (srcRate == targetRate) {
            val frames = srcFrames.coerceAtMost(dst.size / CHANNELS)
            System.arraycopy(src, 0, dst, 0, frames * CHANNELS)
            return frames
        }
        val outFrames = ((srcFrames.toLong() * targetRate) / srcRate)
            .toInt()
            .coerceAtMost(dst.size / CHANNELS)
        if (outFrames <= 0) return 0

        val step = srcRate.toDouble() / targetRate.toDouble()
        for (i in 0 until outFrames) {
            val srcPos = i * step
            val idx0 = srcPos.toInt().coerceIn(0, srcFrames - 1)
            val idx1 = (idx0 + 1).coerceAtMost(srcFrames - 1)
            val frac = srcPos - idx0

            val l0 = src[idx0 * 2].toInt()
            val r0 = src[idx0 * 2 + 1].toInt()
            val l1 = src[idx1 * 2].toInt()
            val r1 = src[idx1 * 2 + 1].toInt()

            dst[i * 2] = (l0 + ((l1 - l0) * frac)).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            dst[i * 2 + 1] = (r0 + ((r1 - r0) * frac)).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return outFrames
    }
}

/**
 * Decodes incoming RAOP audio payloads (AAC-LC / AAC-ELD via MediaCodec or L16 PCM)
 * inside :session and writes normalized 16-bit / 48 kHz stereo PCM into gProducerAudio.
 */
class RaopAudioDecoder {
    companion object {
        private const val TAG = "RaopAudioDecoder"
        private const val MAX_PCM_FRAMES = 4096
        private const val DEQUEUE_TIMEOUT_US = 2_000L

        fun defaultAudioSpecificConfig(
            codec: RaopAudioCodec,
            sampleRate: Int,
            samplesPerFrame: Int = 480
        ): ByteArray? {
            val freqIdx = when (sampleRate) {
                96000 -> 0
                88200 -> 1
                64000 -> 2
                48000 -> 3
                44100 -> 4
                32000 -> 5
                24000 -> 6
                22050 -> 7
                16000 -> 8
                else -> 4
            }
            return when (codec) {
                RaopAudioCodec.AAC_LC -> byteArrayOf(
                    ((2 shl 3) or (freqIdx ushr 1)).toByte(),
                    (((freqIdx and 1) shl 7) or (PcmResampler.CHANNELS shl 3)).toByte()
                )
                RaopAudioCodec.AAC_ELD -> {
                    // objectType=39 (11111 + 000111), freqIdx (4 bits), ch=2 (4 bits),
                    // frameLengthFlag (1 bit: 1 for 480 spf, 0 for 512 spf), 4 zero flags + eldExtType=0
                    val b0 = 0xF8.toByte()
                    val b1 = (0xE0 or ((freqIdx and 0x0F) shl 1) or ((PcmResampler.CHANNELS ushr 3) and 0x01)).toByte()
                    val frameFlag = if (samplesPerFrame == 480) 0x10 else 0x00
                    val b2 = (((PcmResampler.CHANNELS and 0x07) shl 5) or frameFlag).toByte()
                    byteArrayOf(b0, b1, b2, 0x00)
                }
                else -> null
            }
        }
    }

    private var codecType: RaopAudioCodec = RaopAudioCodec.PCM_L16_48K
    private var inputSampleRate: Int = PcmResampler.TARGET_SAMPLE_RATE
    private var mediaCodec: MediaCodec? = null
    @Volatile
    var decodedFramesTotal: Long = 0L
        private set
    @Volatile
    var pcmBytesTotal: Long = 0L
        private set

    private val srcSamples = ShortArray(MAX_PCM_FRAMES * PcmResampler.CHANNELS)
    private val resampledSamples = ShortArray(MAX_PCM_FRAMES * PcmResampler.CHANNELS)
    private val directOut: ByteBuffer = ByteBuffer
        .allocateDirect(MAX_PCM_FRAMES * PcmResampler.CHANNELS * 2)
        .order(ByteOrder.LITTLE_ENDIAN)

    @Synchronized
    fun configure(
        codec: RaopAudioCodec,
        sampleRate: Int,
        csd0: ByteArray? = null,
        samplesPerFrame: Int = 480
    ): Boolean {
        release()
        codecType = codec
        inputSampleRate = sampleRate.coerceAtLeast(8000)
        decodedFramesTotal = 0L
        pcmBytesTotal = 0L

        if (codec == RaopAudioCodec.PCM_L16_48K || codec == RaopAudioCodec.PCM_L16_44K1) {
            Log.i(TAG, "Configured PCM decoder ($codec @ $inputSampleRate Hz)")
            return true
        }

        val effectiveCsd0 = csd0?.takeIf { it.isNotEmpty() }
            ?: defaultAudioSpecificConfig(codec, inputSampleRate, samplesPerFrame)

        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            inputSampleRate,
            PcmResampler.CHANNELS
        ).apply {
            setInteger(
                MediaFormat.KEY_AAC_PROFILE,
                if (codec == RaopAudioCodec.AAC_ELD) {
                    MediaCodecInfo.CodecProfileLevel.AACObjectELD
                } else {
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC
                }
            )
            if (effectiveCsd0 != null && effectiveCsd0.isNotEmpty()) {
                setByteBuffer("csd-0", ByteBuffer.wrap(effectiveCsd0))
            }
        }

        mediaCodec = runCatching {
            MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                configure(format, null, null, 0)
                start()
            }
        }.onFailure {
            Log.e(TAG, "Failed to configure AAC decoder ($codec @ $inputSampleRate Hz)", it)
        }.getOrNull()

        Log.i(
            TAG,
            "Configured AAC decoder ($codec @ $inputSampleRate Hz, spf=$samplesPerFrame, " +
                "csd0Len=${effectiveCsd0?.size ?: 0}, ok=${mediaCodec != null})"
        )
        return mediaCodec != null
    }

    @Synchronized
    fun decodeAndPublish(payload: ByteArray, offset: Int, length: Int, generationId: Int) {
        if (length <= 0) return

        when (codecType) {
            RaopAudioCodec.PCM_L16_48K, RaopAudioCodec.PCM_L16_44K1 -> {
                val frameCount = (length / 4).coerceAtMost(MAX_PCM_FRAMES)
                if (frameCount <= 0) return
                // RAOP L16 is big-endian; convert to host little-endian I16 stereo.
                for (i in 0 until frameCount * 2) {
                    val b0 = payload[offset + i * 2].toInt() and 0xFF
                    val b1 = payload[offset + i * 2 + 1].toInt() and 0xFF
                    srcSamples[i] = ((b0 shl 8) or b1).toShort()
                }
                publishResampled(frameCount, generationId)
            }
            RaopAudioCodec.AAC_LC, RaopAudioCodec.AAC_ELD -> {
                val decoder = mediaCodec ?: return
                val inIdx = runCatching { decoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US) }
                    .getOrDefault(-1)
                if (inIdx >= 0) {
                    val inBuf = decoder.getInputBuffer(inIdx)
                    if (inBuf != null && length <= inBuf.capacity()) {
                        inBuf.clear()
                        inBuf.put(payload, offset, length)
                        decoder.queueInputBuffer(inIdx, 0, length, 0L, 0)
                    }
                }
                val info = MediaCodec.BufferInfo()
                while (true) {
                    val outIdx = runCatching { decoder.dequeueOutputBuffer(info, 0L) }
                        .getOrDefault(MediaCodec.INFO_TRY_AGAIN_LATER)
                    if (outIdx < 0) break
                    val outBuf = decoder.getOutputBuffer(outIdx)
                    if (outBuf != null && info.size >= 4) {
                        outBuf.position(info.offset)
                        outBuf.limit(info.offset + info.size)
                        val frameCount = (info.size / 4).coerceAtMost(MAX_PCM_FRAMES)
                        outBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                            .get(srcSamples, 0, frameCount * 2)
                        publishResampled(frameCount, generationId)
                    }
                    decoder.releaseOutputBuffer(outIdx, false)
                }
            }
        }
    }

    private fun publishResampled(srcFrames: Int, generationId: Int) {
        val outFrames = PcmResampler.resampleStereoI16(
            src = srcSamples,
            srcFrames = srcFrames,
            srcRate = inputSampleRate,
            dst = resampledSamples
        )
        if (outFrames <= 0) return
        val byteCount = outFrames * 4
        directOut.clear()
        directOut.asShortBuffer().put(resampledSamples, 0, outFrames * 2)
        directOut.limit(byteCount)
        val written = NativeSharedMemory.nativeWriteAudioPcm(directOut, byteCount, generationId)
        if (written > 0) {
            decodedFramesTotal += outFrames
            pcmBytesTotal += byteCount
        }
    }

    @Synchronized
    fun release() {
        mediaCodec?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        mediaCodec = null
    }
}
