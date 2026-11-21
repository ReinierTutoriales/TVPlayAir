package com.tvplayair.receiver.cast

import java.net.URI

internal data class CastMediaRequest(val url: String, val startMs: Long?, val autoplay: Boolean) {
    companion object {
        fun from(url: String?, startMs: Long, autoplay: Boolean): CastMediaRequest? {
            val address = url?.let { runCatching { URI(it) }.getOrNull() } ?: return null
            if ((!address.scheme.equals("https", ignoreCase = true) &&
                    !address.scheme.equals("http", ignoreCase = true)) ||
                address.host == null || address.userInfo != null
            ) return null
            return CastMediaRequest(address.toString(), startMs.takeIf { it >= 0L }, autoplay)
        }
    }
}
