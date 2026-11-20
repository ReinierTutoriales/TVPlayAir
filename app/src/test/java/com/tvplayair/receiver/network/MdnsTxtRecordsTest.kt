package com.tvplayair.receiver.network

import org.junit.Assert.assertEquals
import org.junit.Test

class MdnsTxtRecordsTest {

    @Test
    fun buildsAirPlayTxtWithRequiredV1Parameters() {
        val config = AirPlayAdvertiseConfig(
            serviceName = "LivingRoomTV",
            deviceId = "AA:BB:CC:DD:EE:FF",
            piUuid = "11111111-2222-3333-4444-555555555555"
        )
        val txt = MdnsTxtRecords.buildAirPlayTxt(config)

        assertEquals("0", txt["acl"])
        assertEquals("AA:BB:CC:DD:EE:FF", txt["deviceid"])
        assertEquals("0x5A7FFFF7,0x1E", txt["features"])
        assertEquals("0x44", txt["flags"])
        assertEquals("AppleTV3,2", txt["model"])
        assertEquals("0", txt["overscanned"])
        assertEquals("11111111-2222-3333-4444-555555555555", txt["pi"])
        assertEquals(64, txt["pk"]?.length)
    }

    @Test
    fun buildsRaopServiceNameAndAudioTxt() {
        val config = AirPlayAdvertiseConfig(
            serviceName = "TVPlayAir",
            deviceId = "aa:bb:cc:dd:ee:ff"
        )
        assertEquals("AABBCCDDEEFF@TVPlayAir", MdnsTxtRecords.buildRaopServiceName(config))

        val raop = MdnsTxtRecords.buildRaopTxt(config)
        assertEquals("48000", raop["sr"])
        assertEquals("16", raop["ss"])
        assertEquals("2", raop["ch"])
        assertEquals("AppleTV3,2", raop["am"])
        assertEquals("0x5A7FFFF7,0x1E", raop["ft"])
        assertEquals(64, raop["pk"]?.length)
    }
}
