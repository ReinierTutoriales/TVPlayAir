package com.tvplayair.receiver.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiverIdentityTest {

    @Test
    fun formatsLocallyAdministeredUnicastMac() {
        val mac = ReceiverIdentityHelper.formatLocalMac(
            byteArrayOf(0x01, 0x23, 0x45, 0x67, 0x89.toByte(), 0xAB.toByte())
        )
        // First byte 0x01 -> bit 0 cleared (unicast), bit 1 set (locally administered) = 0x02
        assertEquals("02:23:45:67:89:AB", mac)
    }

    @Test
    fun sanitizesAndCyclesRoomPresetsWithSystemName() {
        assertEquals("TV Sala", ReceiverIdentityHelper.sanitizeServiceName("  TV Sala\r\n "))
        assertEquals("TVPlayAir", ReceiverIdentityHelper.sanitizeServiceName("   "))

        val options = ReceiverIdentityHelper.availableNames("Living Room OLED")
        assertEquals("TVPlayAir Sala", options.first())
        assertTrue(options.contains("TVPlayAir Habitacion"))
        assertTrue(options.contains("TVPlayAir Living Room OLED"))

        val next = ReceiverIdentityHelper.nextReceiverName("TVPlayAir Sala", "Living Room OLED")
        assertEquals("TVPlayAir Habitacion", next)
    }
}
