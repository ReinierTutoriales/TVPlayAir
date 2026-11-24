package com.tvplayair.receiver.network

import android.content.Context

enum class ResolutionPreset(val width: Int, val height: Int, val label: String) {
    UHD_4K(3840, 2160, "4K"),
    FHD_1080P(1920, 1080, "1080p");

    fun next(): ResolutionPreset = if (this == UHD_4K) FHD_1080P else UHD_4K
}

internal data class ReceiverVideoSettings(val resolution: ResolutionPreset, val hevcEnabled: Boolean) {
    companion object {
        private const val FILE = "receiver_video"
        private const val RESOLUTION = "resolution"
        private const val HEVC = "hevc"

        fun read(context: Context): ReceiverVideoSettings {
            val preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            val saved = preferences.getString(RESOLUTION, null)
            val resolution = ResolutionPreset.entries.firstOrNull { it.name == saved }
                ?: ResolutionPreset.UHD_4K
            return ReceiverVideoSettings(resolution, preferences.getBoolean(HEVC, true))
        }
    }

    // The presentation process is the sole writer. :session reads only at startup
    // and receives subsequent settings through AIDL.
    fun save(context: Context) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(RESOLUTION, resolution.name)
            .putBoolean(HEVC, hevcEnabled)
            .apply()
    }
}
