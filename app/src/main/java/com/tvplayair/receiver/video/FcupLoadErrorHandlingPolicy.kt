package com.tvplayair.receiver.video

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import java.io.IOException

@OptIn(UnstableApi::class)
internal class FcupLoadErrorHandlingPolicy : DefaultLoadErrorHandlingPolicy() {
    override fun isEligibleForFallback(exception: IOException): Boolean {
        if (exception is InvalidResponseCodeException &&
            (exception.responseCode == 502 || exception.responseCode == 504)
        ) {
            val uri = exception.dataSpec.uri
            return isFcupPlaylist(uri.host, uri.path)
        }
        return super.isEligibleForFallback(exception)
    }

    companion object {
        internal fun isFcupPlaylist(host: String?, path: String?): Boolean =
            host == "127.0.0.1" && path != null &&
                path.startsWith("/__fcup/") && path.endsWith("/playlist.m3u8")
    }
}
