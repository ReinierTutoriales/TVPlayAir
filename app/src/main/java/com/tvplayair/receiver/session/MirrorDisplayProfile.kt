package com.tvplayair.receiver.session

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodecList
import android.media.MediaFormat
import android.view.Display

internal data class MirrorDisplayProfile(
    val width: Int,
    val height: Int,
    val fps: Int,
    val hevc: Boolean
) {
    companion object {
        private val safeProfile = MirrorDisplayProfile(1280, 720, 30, false)

        fun detect(context: Context, maxWidth: Int, maxHeight: Int, allowHevc: Boolean): MirrorDisplayProfile {
            val modes = context.getSystemService(DisplayManager::class.java)
                ?.getDisplay(Display.DEFAULT_DISPLAY)?.supportedModes.orEmpty()
            val decoders = runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos }
                .getOrDefault(emptyArray())
                .filter { !it.isEncoder && it.isHardwareAccelerated }

            return select(maxWidth, maxHeight, allowHevc) { profile ->
                val fitsDisplay = if (modes.isEmpty()) profile.height <= 1080 && profile.fps == 30
                else modes.any { mode ->
                    maxOf(mode.physicalWidth, mode.physicalHeight) >= profile.width &&
                        minOf(mode.physicalWidth, mode.physicalHeight) >= profile.height &&
                        mode.refreshRate >= profile.fps - 0.1f
                }
                val mime = if (profile.hevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
                fitsDisplay && decoders.any { decoder ->
                    decoder.supportedTypes.any { it.equals(mime, ignoreCase = true) } && runCatching {
                        decoder.getCapabilitiesForType(mime).videoCapabilities
                            ?.areSizeAndRateSupported(profile.width, profile.height, profile.fps.toDouble()) == true
                    }.getOrDefault(false)
                }
            }
        }

        internal fun select(
            maxWidth: Int,
            maxHeight: Int,
            allowHevc: Boolean,
            supported: (MirrorDisplayProfile) -> Boolean
        ): MirrorDisplayProfile {
            val sizes = listOf(3840 to 2160, 1920 to 1080, 1280 to 720)
            for ((width, height) in sizes) {
                val hevc = height > 1080
                if (width > maxWidth || height > maxHeight || (hevc && !allowHevc)) continue
                for (fps in listOf(60, 30)) {
                    val profile = MirrorDisplayProfile(width, height, fps, hevc)
                    if (supported(profile)) return profile
                }
            }
            return safeProfile
        }
    }
}
