package com.tvplayair.receiver.session

import com.tvplayair.receiver.network.MdnsTxtRecords

/**
 * Generates AirPlay GET /info (binary bplist00) and GET /server-info (XML plist)
 * capability responses advertising display resolution and audio capabilities.
 */
object AirPlayInfoPlist {
    const val SERVER_INFO_FEATURES: Long = 0x5A7FEFF3L
    const val STATUS_FLAGS: Int = 0x44

    fun buildInfoMap(
        deviceId: String,
        maxWidth: Int,
        maxHeight: Int,
        hevcEnabled: Boolean,
        maxFps: Int
    ): Map<String, Any> {
        val width = maxWidth.toLong()
        val height = maxHeight.toLong()
        return linkedMapOf(
            "deviceID" to deviceId,
            "macAddress" to deviceId,
            "features" to MdnsTxtRecords.airPlayFeatures(hevcEnabled),
            "model" to MdnsTxtRecords.AIRPLAY_MODEL,
            "sourceVersion" to "220.68",
            "statusFlags" to STATUS_FLAGS.toLong(),
            "keepAliveLowPower" to true,
            "keepAliveSendStatsAsBody" to true,
            "vv" to 2L,
            "displays" to listOf(
                linkedMapOf<String, Any>(
                    "uuid" to "e5f7a68d-7b0f-4305-984b-974f677a150b",
                    "widthPixels" to width,
                    "heightPixels" to height,
                    "width" to width,
                    "height" to height,
                    "widthPhysical" to 0L,
                    "heightPhysical" to 0L,
                    "maxFPS" to maxFps.toLong(),
                    "features" to 14L,
                    "overscanned" to false,
                    "refreshRate" to 1.0 / maxFps,
                    "rotation" to false
                )
            ),
            "audioFormats" to listOf(
                linkedMapOf<String, Any>(
                    "audioInputFormats" to 67108860L,
                    "audioOutputFormats" to 67108860L,
                    "type" to 100L
                ),
                linkedMapOf<String, Any>(
                    "audioInputFormats" to 67108860L,
                    "audioOutputFormats" to 67108860L,
                    "type" to 101L
                )
            )
        )
    }

    fun formatInfoBinary(
        deviceId: String,
        maxWidth: Int,
        maxHeight: Int,
        hevcEnabled: Boolean,
        maxFps: Int
    ): ByteArray = BinaryPlistWriter.encode(buildInfoMap(deviceId, maxWidth, maxHeight, hevcEnabled, maxFps))

    fun formatServerInfoXml(deviceId: String): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" ")
        append("\"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n")
        append("<plist version=\"1.0\">\n<dict>\n")
        append("  <key>deviceid</key><string>").append(deviceId).append("</string>\n")
        append("  <key>features</key><integer>").append(SERVER_INFO_FEATURES).append("</integer>\n")
        append("  <key>model</key><string>").append(MdnsTxtRecords.AIRPLAY_MODEL).append("</string>\n")
        append("  <key>protovers</key><string>1.0</string>\n")
        append("  <key>srcvers</key><string>220.68</string>\n")
        append("</dict>\n</plist>\n")
    }
}
