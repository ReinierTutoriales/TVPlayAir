package com.tvplayair.receiver.session

import org.bouncycastle.math.ec.rfc7748.X25519
import org.bouncycastle.math.ec.rfc8032.Ed25519
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class AirPlayPairingAndPlistTest {

    @Test
    fun completesFullTwoStepCurve25519Ed25519PairVerifyHandshake() {
        val random = SecureRandom()
        val server = AirPlayPairingSession(random)

        val clientEdPriv = ByteArray(Ed25519.SECRET_KEY_SIZE)
        val clientEdPub = ByteArray(Ed25519.PUBLIC_KEY_SIZE)
        Ed25519.generatePrivateKey(random, clientEdPriv)
        Ed25519.generatePublicKey(clientEdPriv, 0, clientEdPub, 0)

        // Pair-setup returns server's 32-byte Ed25519 public key.
        val serverEdPub = server.handlePairSetup(clientEdPub)
        assertNotNull(serverEdPub)
        assertEquals(32, serverEdPub!!.size)

        // Client generates ephemeral X25519 keypair for Step 1.
        val clientCurvePriv = ByteArray(X25519.SCALAR_SIZE)
        val clientCurvePub = ByteArray(X25519.POINT_SIZE)
        X25519.generatePrivateKey(random, clientCurvePriv)
        X25519.generatePublicKey(clientCurvePriv, 0, clientCurvePub, 0)

        val step1Req = ByteArray(68)
        step1Req[0] = 1
        System.arraycopy(clientCurvePub, 0, step1Req, 4, 32)
        System.arraycopy(clientEdPub, 0, step1Req, 36, 32)

        val step1Resp = server.handlePairVerify(step1Req)
        assertNotNull(step1Resp)
        assertEquals(96, step1Resp!!.size)

        val serverCurvePub = step1Resp.copyOfRange(0, 32)
        val encryptedServerSig = step1Resp.copyOfRange(32, 96)

        // Client computes shared secret, verifies server's Ed25519 signature, and sends Step 2.
        val clientSecret = ByteArray(X25519.POINT_SIZE)
        X25519.scalarMult(clientCurvePriv, 0, serverCurvePub, 0, clientSecret, 0)
        assertArrayEquals(server.sharedSecret, clientSecret)

        val aesKey = AirPlayPairingSession.sha512Trunc16("Pair-Verify-AES-Key", clientSecret)
        val aesIv = AirPlayPairingSession.sha512Trunc16("Pair-Verify-AES-IV", clientSecret)
        val clientCipher = Cipher.getInstance("AES/CTR/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(aesIv))
        }
        val serverSig = clientCipher.update(encryptedServerSig)!!
        val serverMsg = serverCurvePub + clientCurvePub
        assertTrue(Ed25519.verify(serverSig, 0, serverEdPub, 0, serverMsg, 0, serverMsg.size))

        val clientMsg = clientCurvePub + serverCurvePub
        val clientSig = ByteArray(Ed25519.SIGNATURE_SIZE)
        Ed25519.sign(clientEdPriv, 0, clientMsg, 0, clientMsg.size, clientSig, 0)
        val encryptedClientSig = clientCipher.update(clientSig)!!

        val step2Req = ByteArray(68)
        step2Req[0] = 0
        System.arraycopy(encryptedClientSig, 0, step2Req, 4, 64)

        val step2Resp = server.handlePairVerify(step2Req)
        assertNotNull(step2Resp)
        assertEquals(0, step2Resp!!.size)
        assertTrue(server.isVerified)
    }

    @Test
    fun rejectsTamperedClientSignatureInPairVerifyStep2() {
        val random = SecureRandom()
        val server = AirPlayPairingSession(random)
        val step1Req = ByteArray(68).apply { this[0] = 1 }
        random.nextBytes(ByteArray(32).also { System.arraycopy(it, 0, step1Req, 4, 32) })
        random.nextBytes(ByteArray(32).also { System.arraycopy(it, 0, step1Req, 36, 32) })

        assertNotNull(server.handlePairVerify(step1Req))
        val badStep2 = ByteArray(68) // all-zero invalid signature
        assertNull(server.handlePairVerify(badStep2))
        assertFalse(server.isVerified)
    }

    @Test
    fun derivesDeterministic16ByteMirrorStreamKeyAndIv() {
        val key = ByteArray(16) { (it + 7).toByte() }
        val k1 = AirPlayPairingSession.deriveMirrorStreamKey(key, 987654321L)
        val k2 = AirPlayPairingSession.deriveMirrorStreamKey(key, 987654321L)
        val iv = AirPlayPairingSession.deriveMirrorStreamIv(key, 987654321L)
        assertEquals(16, k1.size)
        assertEquals(16, iv.size)
        assertArrayEquals(k1, k2)
        assertFalse(k1.contentEquals(iv))
    }

    @Test
    fun parsesBinaryPlistDictionaryWithIntAndData() {
        // Minimal valid bplist00: { "type": 110, "eiv": <4 bytes> }
        val out = ByteArrayOutputStream()
        out.write("bplist00".toByteArray(StandardCharsets.US_ASCII))

        val off0 = out.size() // dict (2 entries): keys=[1,2], vals=[3,4]
        out.write(byteArrayOf(0xD2.toByte(), 0x01, 0x02, 0x03, 0x04))
        val off1 = out.size() // ASCII "type"
        out.write(0x54)
        out.write("type".toByteArray(StandardCharsets.US_ASCII))
        val off2 = out.size() // ASCII "eiv"
        out.write(0x53)
        out.write("eiv".toByteArray(StandardCharsets.US_ASCII))
        val off3 = out.size() // int 110 (1 byte)
        out.write(byteArrayOf(0x10, 110))
        val off4 = out.size() // data (4 bytes)
        out.write(byteArrayOf(0x44, 0x0A, 0x0B, 0x0C, 0x0D))

        val offsetTableOffset = out.size().toLong()
        out.write(byteArrayOf(off0.toByte(), off1.toByte(), off2.toByte(), off3.toByte(), off4.toByte()))

        val trailer = ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN).apply {
            put(ByteArray(6))
            put(1.toByte()) // offsetIntSize
            put(1.toByte()) // objectRefSize
            putLong(5L)     // numObjects
            putLong(0L)     // topObject
            putLong(offsetTableOffset)
        }.array()
        out.write(trailer)

        val parsed = BinaryPlistParser.parse(out.toByteArray()) as? Map<*, *>
        assertNotNull(parsed)
        assertEquals(110L, parsed!!["type"])
        assertArrayEquals(byteArrayOf(0x0A, 0x0B, 0x0C, 0x0D), parsed["eiv"] as ByteArray)
        assertNull(BinaryPlistParser.parse("not-a-bplist".toByteArray()))
    }

    @Test
    fun encodesAndDecodesNestedSetupBinaryPlistRoundTrip() {
        val original = linkedMapOf<String, Any>(
            "streams" to listOf(
                linkedMapOf<String, Any>(
                    "dataPort" to 7100L,
                    "type" to 110L
                )
            ),
            "timingPort" to 6002L
        )
        val encoded = BinaryPlistWriter.encode(original)
        assertTrue(BinaryPlistParser.isBinaryPlist(encoded))

        val decoded = BinaryPlistParser.parse(encoded) as? Map<*, *>
        assertNotNull(decoded)
        assertEquals(6002L, decoded!!["timingPort"])
        val streams = decoded["streams"] as? List<*>
        val first = streams?.firstOrNull() as? Map<*, *>
        assertEquals(7100L, first?.get("dataPort"))
        assertEquals(110L, first?.get("type"))
    }
}
