package com.tvplayair.receiver.session

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.container.NalUnitUtil

@OptIn(UnstableApi::class)
internal fun mirrorSpsDimensions(config: ByteArray, hevc: Boolean): Pair<Int, Int>? {
    val prefixFlags = BooleanArray(3)
    var start = NalUnitUtil.findNalUnit(config, 0, config.size, prefixFlags)
    while (start < config.size - 3) {
        val header = start + 3
        val end = NalUnitUtil.findNalUnit(config, header, config.size, prefixFlags)
        val type = config[header].toInt()
        val isSps = if (hevc) ((type shr 1) and 0x3f) == 33 else (type and 0x1f) == 7
        if (isSps) {
            val size = runCatching {
                if (hevc) {
                    val sps = NalUnitUtil.parseH265SpsNalUnit(config, header, end, null)
                    sps.width to sps.height
                } else {
                    val sps = NalUnitUtil.parseSpsNalUnit(config, header, end)
                    sps.width to sps.height
                }
            }.getOrNull()
            if (size != null && size.first in 16..8192 && size.second in 16..8192) return size
        }
        start = end
    }
    return null
}
