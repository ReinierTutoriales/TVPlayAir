package com.tvplayair.receiver.session

import com.tvplayair.receiver.network.MdnsTxtRecords

/**
 * Generates AirPlay GET /info (binary bplist00) and GET /server-info (XML plist)
 * capability responses advertising display resolution and audio capabilities.
 */
object AirPlayInfoPlist {
    // 0x5A7FFFF3,0x1C combined as 64-bit integer (clears VideoFairPlay bit 2 and VideoV2 bit 33
    // so iOS uses direct Content-Location URLs in POST /play instead of FCUP reverse proxy + /fp-setup2).
    const val FEATURES_64BIT: Long = (0x1CL shl 32) or 0x5A7FFFF3L
    const val SERVER_INFO_FEATURES: Long = 0x5A7FEFF3L
    const val STATUS_FLAGS: Int = 0x44

    fun buildInfoMap(
        deviceId: String,
        maxWidth: Int,
        maxHeight: Int,
        hevcEnabled: Boolean
    ): Map<String, Any> {
        val width = maxWidth.coerceIn(1280, 1920).toLong()
        val height = maxHeight.coerceIn(720, 1080).toLong()
        return linkedMapOf(
            "deviceID" to deviceId,
            "macAddress" to deviceId,
            "features" to FEATURES_64BIT,
            "model" to MdnsTxtRecords.AIRPLAY_MODEL,
            "sourceVersion" to "220.68",
            "statusFlags" to STATUS_FLAGS.toLong(),
            "keepAliveLowPower" to true,
            "keepAliveSendStatsAsBody" to true,
            "vv" to 2L,
            "preferredCodec" to if (hevcEnabled) "HEVC" else "H264",
            "displays" to listOf(
                linkedMapOf<String, Any>(
                    "uuid" to "e5f7a68d-7b0f-4305-984b-974f677a150b",
                    "widthPixels" to width,
                    "heightPixels" to height,
                    "width" to width,
                    "height" to height,
                    "widthPhysical" to 0L,
                    "heightPhysical" to 0L,
                    "maxFPS" to 60L,
                    "features" to 14L,
                    "overscanned" to false,
                    "refreshRate" to 60.0,
                    "rotation" to true
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
        hevcEnabled: Boolean
    ): ByteArray = BinaryPlistWriter.encode(buildInfoMap(deviceId, maxWidth, maxHeight, hevcEnabled))

    fun formatInfoXml(
        deviceId: String,
        maxWidth: Int,
        maxHeight: Int,
        hevcEnabled: Boolean
    ): String {
        val width = maxWidth.coerceAtLeast(1280)
        val height = maxHeight.coerceAtLeast(720)
        val refreshRate = 60
        val codecHint = if (hevcEnabled) "HEVC" else "H264"

        return buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            append("<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" ")
            append("\"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n")
            append("<plist version=\"1.0\">\n<dict>\n")
            append("  <key>deviceID</key><string>").append(deviceId).append("</string>\n")
            append("  <key>features</key><integer>").append(FEATURES_64BIT).append("</integer>\n")
            append("  <key>model</key><string>").append(MdnsTxtRecords.AIRPLAY_MODEL).append("</string>\n")
            append("  <key>sourceVersion</key><string>220.68</string>\n")
            append("  <key>statusFlags</key><integer>").append(STATUS_FLAGS).append("</integer>\n")
            append("  <key>preferredCodec</key><string>").append(codecHint).append("</string>\n")
            append("  <key>displays</key>\n  <array>\n    <dict>\n")
            append("      <key>widthPixels</key><integer>").append(width).append("</integer>\n")
            append("      <key>heightPixels</key><integer>").append(height).append("</integer>\n")
            append("      <key>maxFPS</key><integer>").append(refreshRate).append("</integer>\n")
            append("      <key>overscanned</key><false/>\n")
            append("      <key>refreshRate</key><real>60.0</real>\n")
            append("      <key>rotation</key><true/>\n")
            append("    </dict>\n  </array>\n")
            append("  <key>audioFormats</key>\n  <array>\n    <dict>\n")
            append("      <key>audioInputFormats</key><integer>67108860</integer>\n")
            append("      <key>audioOutputFormats</key><integer>67108860</integer>\n")
            append("      <key>type</key><integer>100</integer>\n")
            append("    </dict>\n  </array>\n")
            append("</dict>\n</plist>\n")
        }
    }

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
