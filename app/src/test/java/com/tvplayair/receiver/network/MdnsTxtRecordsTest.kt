package com.tvplayair.receiver.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        assertEquals("0x5A7FFFF3,0x41C", txt["features"])
        assertEquals("0x5A7FFFF3,0x1C", MdnsTxtRecords.buildAirPlayTxt(config.copy(hevcEnabled = false))["features"])
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
        assertEquals("44100", raop["sr"])
        assertEquals("16", raop["ss"])
        assertEquals("2", raop["ch"])
        assertEquals("AppleTV3,2", raop["am"])
        assertEquals("0x5A7FFFF3,0x41C", raop["ft"])
        assertEquals(64, raop["pk"]?.length)
    }

    @Test
    fun retryNamesAvoidRepeatingTheConflictingInstance() {
        val config = AirPlayAdvertiseConfig(serviceName = "Sala", deviceId = "02:12:34:56:78:90")
        assertEquals("Sala", MdnsTxtRecords.buildAirPlayServiceName(config))
        assertEquals("Sala (TVPlayAir 567890-1)", MdnsTxtRecords.buildAirPlayServiceName(config, 1))
        assertEquals("Sala (TVPlayAir 567890-2)", MdnsTxtRecords.buildAirPlayServiceName(config, 2))
        assertEquals("021234567890@Sala (TVPlayAir 567890-1)", MdnsTxtRecords.buildRaopServiceName(config, 1))
    }

    @Test
    fun namesFitDnsLabelByteBudgetWithoutSplittingSupplementaryCharacters() {
        val displayName = "📺".repeat(30)
        val config = AirPlayAdvertiseConfig(serviceName = displayName, deviceId = "02:12:34:56:78:90")
        assertEquals("📺".repeat(15), MdnsTxtRecords.buildAirPlayServiceName(config))
        assertEquals("021234567890@" + "📺".repeat(12), MdnsTxtRecords.buildRaopServiceName(config))
        val retry = MdnsTxtRecords.buildRaopServiceName(config, 3)
        assertTrue(retry.toByteArray(Charsets.UTF_8).size <= 63)
        assertEquals("021234567890@" + "📺".repeat(7) + " (TVPlayAir 567890-3)", retry)
    }

}
