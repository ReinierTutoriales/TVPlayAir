package com.tvplayair.receiver.session

import org.bouncycastle.math.ec.rfc7748.X25519
import org.bouncycastle.math.ec.rfc8032.Ed25519
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Transient AirPlay pairing state machine (POST /pair-setup & 2-step POST /pair-verify)
 * using RFC 7748 X25519, RFC 8032 Ed25519, SHA-512 and AES-128-CTR.
 */
class AirPlayPairingSession(
    private val random: SecureRandom = SecureRandom(),
    initialEdPrivateKey: ByteArray? = null
) {
    private val edPrivateKey = ByteArray(Ed25519.SECRET_KEY_SIZE)
    private val edPublicKey = ByteArray(Ed25519.PUBLIC_KEY_SIZE)

    private var ephemeralServerPub: ByteArray? = null
    private var clientCurvePub: ByteArray? = null
    private var clientEdPub: ByteArray? = null
    private var verifyCipher: Cipher? = null

    var sharedSecret: ByteArray? = null
        private set
    var isVerified: Boolean = false
        private set

    init {
        if (initialEdPrivateKey != null && initialEdPrivateKey.size == Ed25519.SECRET_KEY_SIZE) {
            System.arraycopy(initialEdPrivateKey, 0, edPrivateKey, 0, Ed25519.SECRET_KEY_SIZE)
        } else {
            Ed25519.generatePrivateKey(random, edPrivateKey)
        }
        Ed25519.generatePublicKey(edPrivateKey, 0, edPublicKey, 0)
    }

    fun setEdPrivateKey(privateKey32: ByteArray) {
        if (privateKey32.size != Ed25519.SECRET_KEY_SIZE) return
        System.arraycopy(privateKey32, 0, edPrivateKey, 0, Ed25519.SECRET_KEY_SIZE)
        Ed25519.generatePublicKey(edPrivateKey, 0, edPublicKey, 0)
    }

    fun serverEdPublicKey(): ByteArray = edPublicKey.copyOf()

    /**
     * Handles POST /pair-setup (transient mode): returns the receiver's 32-byte Ed25519 public key.
     */
    fun handlePairSetup(requestBody: ByteArray): ByteArray? {
        if (requestBody.size != 32) return null
        return serverEdPublicKey()
    }

    /**
     * Handles 2-step POST /pair-verify:
     * - Step 1 (body[0] == 1, 68 bytes): returns 96 bytes (server X25519 pubkey || encrypted 64-byte Ed25519 signature).
     * - Step 2 (body[0] == 0, 68 bytes): decrypts and verifies client's Ed25519 signature; returns empty ByteArray on success.
     * Returns null on malformed input or invalid signature.
     */
    fun handlePairVerify(requestBody: ByteArray): ByteArray? {
        if (requestBody.size != 68) return null
        val flag = requestBody[0].toInt() and 0xFF
        return when (flag) {
            1 -> handleVerifyStep1(requestBody)
            0 -> handleVerifyStep2(requestBody)
            else -> null
        }
    }

    private fun handleVerifyStep1(body: ByteArray): ByteArray {
        isVerified = false
        val clientCurve = body.copyOfRange(4, 36)
        val clientEd = body.copyOfRange(36, 68)

        val serverCurvePriv = ByteArray(X25519.SCALAR_SIZE)
        val serverCurvePub = ByteArray(X25519.POINT_SIZE)
        X25519.generatePrivateKey(random, serverCurvePriv)
        X25519.generatePublicKey(serverCurvePriv, 0, serverCurvePub, 0)

        val secret = ByteArray(X25519.POINT_SIZE)
        X25519.scalarMult(serverCurvePriv, 0, clientCurve, 0, secret, 0)

        val aesKey = sha512Trunc16("Pair-Verify-AES-Key", secret)
        val aesIv = sha512Trunc16("Pair-Verify-AES-IV", secret)

        // Sign (serverCurvePub || clientCurve) with receiver's Ed25519 key.
        val toSign = ByteArray(64)
        System.arraycopy(serverCurvePub, 0, toSign, 0, 32)
        System.arraycopy(clientCurve, 0, toSign, 32, 32)
        val signature = ByteArray(Ed25519.SIGNATURE_SIZE)
        Ed25519.sign(edPrivateKey, 0, toSign, 0, toSign.size, signature, 0)

        val cipher = Cipher.getInstance("AES/CTR/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(aesIv))
        }
        val encryptedSig = cipher.update(signature) ?: ByteArray(64)

        ephemeralServerPub = serverCurvePub
        clientCurvePub = clientCurve
        clientEdPub = clientEd
        sharedSecret = secret
        verifyCipher = cipher

        val response = ByteArray(96)
        System.arraycopy(serverCurvePub, 0, response, 0, 32)
        System.arraycopy(encryptedSig, 0, response, 32, 64)
        return response
    }

    private fun handleVerifyStep2(body: ByteArray): ByteArray? {
        val cipher = verifyCipher ?: return null
        val serverPub = ephemeralServerPub ?: return null
        val clientPub = clientCurvePub ?: return null
        val clientEd = clientEdPub ?: return null

        val encryptedClientSig = body.copyOfRange(4, 68)
        // AES-CTR encryption and decryption are identical (XOR with continuing keystream).
        val clientSig = cipher.update(encryptedClientSig) ?: return null
        if (clientSig.size != Ed25519.SIGNATURE_SIZE) return null

        val expectedMsg = ByteArray(64)
        System.arraycopy(clientPub, 0, expectedMsg, 0, 32)
        System.arraycopy(serverPub, 0, expectedMsg, 32, 32)

        val valid = Ed25519.verify(clientSig, 0, clientEd, 0, expectedMsg, 0, expectedMsg.size)
        if (!valid) {
            isVerified = false
            return null
        }

        isVerified = true
        return ByteArray(0)
    }

    companion object {
        fun sha512Trunc16(prefix: String, secret: ByteArray): ByteArray {
            val md = MessageDigest.getInstance("SHA-512")
            md.update(prefix.toByteArray(StandardCharsets.UTF_8))
            md.update(secret)
            return md.digest().copyOfRange(0, 16)
        }

        fun deriveMirrorStreamKey(aesKey16: ByteArray, streamConnectionId: Long): ByteArray {
            require(aesKey16.size == 16) { "Expected 16-byte FairPlay AES key" }
            val prefix = "AirPlayStreamKey${java.lang.Long.toUnsignedString(streamConnectionId)}"
            return sha512Trunc16(prefix, aesKey16)
        }

        fun deriveMirrorStreamIv(aesKey16: ByteArray, streamConnectionId: Long): ByteArray {
            require(aesKey16.size == 16) { "Expected 16-byte FairPlay AES key" }
            val prefix = "AirPlayStreamIV${java.lang.Long.toUnsignedString(streamConnectionId)}"
            return sha512Trunc16(prefix, aesKey16)
        }
    }
}
