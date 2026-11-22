package com.tvplayair.receiver.session

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RaopAlacKeySelectorTest {
    private val primary = ByteArray(16) { 7 }
    private val alternate = ByteArray(16) { 11 }
    private val iv = ByteArray(16) { it.toByte() }

    @Test
    fun nonAlacFirstPacketDoesNotLockOutTheNegotiatedAlternate() {
        val selector = RaopAlacKeySelector(listOf(primary, alternate), iv)
        val nonAlac = ByteArray(32).apply {
            this[1] = 0x60 // An AAC-looking header must not qualify for ALAC.
        }
        val first = encrypt(primary, nonAlac)
        assertNull(selector.decrypt(first, 0, first.size))
        assertNull(selector.selectedIndex)

        // A channel-pair element with a nonzero element instance tag.
        val alacPacket = ByteArray(35) { (it * 3).toByte() }.apply {
            this[0] = 0x26
            this[1] = 0
            this[2] = 0x12
        }
        val encrypted = encrypt(alternate, alacPacket)
        val prefixed = byteArrayOf(1, 2) + encrypted
        assertArrayEquals(alacPacket, selector.decrypt(prefixed, 2, encrypted.size))
        assertEquals(1, selector.selectedIndex)
        assertArrayEquals(alacPacket, selector.decrypt(encrypted, 0, encrypted.size))
    }

    private fun encrypt(key: ByteArray, payload: ByteArray): ByteArray {
        val blockSize = payload.size / 16 * 16
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(payload, 0, blockSize) + payload.copyOfRange(blockSize, payload.size)
    }
}
