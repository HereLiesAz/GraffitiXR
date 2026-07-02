package com.hereliesaz.graffitixr.core.collaboration.wire

import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Per-connection authenticated encryption for the co-op transport.
 *
 * The QR token (128-bit SecureRandom, delivered out-of-band by the camera) is the trust root. It
 * is never sent on the wire; the handshake proves knowledge of it with an HMAC over fresh nonces,
 * and both ends derive matching AES-256-GCM keys from token + both nonces via HKDF. After the
 * handshake every frame is wrapped in a [FrameType.ENC] frame whose payload is
 * `[8-byte send counter big-endian][GCM(ciphertext||tag)]`, with the inner plaintext being
 * `[1-byte inner FrameType][inner payload]`. The counter is the GCM nonce input and the AAD, and
 * is required to strictly increase on receive (reject replay/reorder). Keys and counters are fresh
 * on every (re)connection, so a nonce is never reused under a key.
 */
internal class SessionCrypto private constructor(
    private val sendKey: ByteArray,
    private val recvKey: ByteArray,
    private val sendIvSalt: ByteArray, // 12 bytes
    private val recvIvSalt: ByteArray, // 12 bytes
) {
    private var sendCounter: Long = 0
    private var lastRecvCounter: Long = -1

    /** Wrap ([type], [payload]) into an ENC frame payload. Not thread-safe; callers serialize. */
    fun seal(type: FrameType, payload: ByteArray): ByteArray {
        val counter = sendCounter++
        val counterBytes = counter.toBigEndianBytes()
        val inner = ByteArray(1 + payload.size)
        inner[0] = type.code
        System.arraycopy(payload, 0, inner, 1, payload.size)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(sendKey, "AES"),
            GCMParameterSpec(TAG_BITS, ivFor(sendIvSalt, counterBytes)),
        )
        cipher.updateAAD(counterBytes)
        val ct = cipher.doFinal(inner)

        val out = ByteArray(COUNTER_BYTES + ct.size)
        System.arraycopy(counterBytes, 0, out, 0, COUNTER_BYTES)
        System.arraycopy(ct, 0, out, COUNTER_BYTES, ct.size)
        return out
    }

    /** Decrypt an ENC frame payload back into the inner (type, payload). Throws on tamper/replay. */
    fun open(encPayload: ByteArray): Frame.FrameRead {
        if (encPayload.size < COUNTER_BYTES + TAG_BITS / 8) {
            throw javax.crypto.AEADBadTagException("ENC payload too short")
        }
        val counterBytes = encPayload.copyOfRange(0, COUNTER_BYTES)
        val counter = counterBytes.toLongBigEndian()
        if (counter <= lastRecvCounter) {
            throw javax.crypto.AEADBadTagException("replayed or reordered ENC counter $counter")
        }
        val ct = encPayload.copyOfRange(COUNTER_BYTES, encPayload.size)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(recvKey, "AES"),
            GCMParameterSpec(TAG_BITS, ivFor(recvIvSalt, counterBytes)),
        )
        cipher.updateAAD(counterBytes)
        val inner = cipher.doFinal(ct) // throws AEADBadTagException on tamper/wrong key

        if (inner.isEmpty()) throw javax.crypto.AEADBadTagException("empty inner frame")
        val type = FrameType.ofCode(inner[0])
            ?: throw javax.crypto.AEADBadTagException("unknown inner frame type 0x${inner[0].toString(16)}")
        lastRecvCounter = counter
        return Frame.FrameRead(type, inner.copyOfRange(1, inner.size))
    }

    private fun ivFor(salt: ByteArray, counterBytes: ByteArray): ByteArray {
        // 12-byte IV: low 8 bytes of the salt XOR the counter. Distinct per message under a key.
        val iv = salt.copyOf()
        for (i in 0 until COUNTER_BYTES) {
            iv[IV_BYTES - COUNTER_BYTES + i] = (iv[IV_BYTES - COUNTER_BYTES + i].toInt() xor counterBytes[i].toInt()).toByte()
        }
        return iv
    }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
        private const val IV_BYTES = 12
        private const val COUNTER_BYTES = 8
        private const val KEY_BYTES = 32

        private val EXTRACT_SALT = "GraffitiXR-coop-v2".toByteArray(StandardCharsets.UTF_8)

        /** Pseudo-random key derived from the shared token; input to the proofs and key schedule. */
        fun prk(token: String): ByteArray =
            Hkdf.extract(EXTRACT_SALT, token.toByteArray(StandardCharsets.UTF_8))

        /** Guest→host handshake proof of token knowledge, bound to the guest's nonce. */
        fun helloProof(prk: ByteArray, guestNonce: ByteArray): ByteArray =
            Hkdf.hmac(prk, "gxr/hello".toByteArray(StandardCharsets.UTF_8) + guestNonce)

        /** Host→guest handshake proof, binding both nonces so the guest authenticates the host. */
        fun helloOkProof(prk: ByteArray, hostNonce: ByteArray, guestNonce: ByteArray): ByteArray =
            Hkdf.hmac(prk, "gxr/hello-ok".toByteArray(StandardCharsets.UTF_8) + hostNonce + guestNonce)

        fun forHost(token: String, sessionId: String, guestNonce: ByteArray, hostNonce: ByteArray): SessionCrypto {
            val k = keyMaterial(token, sessionId, guestNonce, hostNonce)
            return SessionCrypto(
                sendKey = k.copyOfRange(0, 32),           // host→guest
                recvKey = k.copyOfRange(32, 64),          // guest→host
                sendIvSalt = k.copyOfRange(64, 76),
                recvIvSalt = k.copyOfRange(76, 88),
            )
        }

        fun forGuest(token: String, sessionId: String, guestNonce: ByteArray, hostNonce: ByteArray): SessionCrypto {
            val k = keyMaterial(token, sessionId, guestNonce, hostNonce)
            return SessionCrypto(
                sendKey = k.copyOfRange(32, 64),          // guest→host
                recvKey = k.copyOfRange(0, 32),           // host→guest
                sendIvSalt = k.copyOfRange(76, 88),
                recvIvSalt = k.copyOfRange(64, 76),
            )
        }

        private fun keyMaterial(token: String, sessionId: String, guestNonce: ByteArray, hostNonce: ByteArray): ByteArray {
            val prk = Hkdf.extract(guestNonce + hostNonce, token.toByteArray(StandardCharsets.UTF_8))
            val info = "gxr/keys".toByteArray(StandardCharsets.UTF_8) + sessionId.toByteArray(StandardCharsets.UTF_8)
            // 2 * 32-byte keys + 2 * 12-byte IV salts.
            return Hkdf.expand(prk, info, 2 * KEY_BYTES + 2 * IV_BYTES)
        }

        private fun Long.toBigEndianBytes(): ByteArray {
            val b = ByteArray(COUNTER_BYTES)
            for (i in 0 until COUNTER_BYTES) b[i] = (this shr (8 * (COUNTER_BYTES - 1 - i))).toByte()
            return b
        }

        private fun ByteArray.toLongBigEndian(): Long {
            var v = 0L
            for (i in 0 until COUNTER_BYTES) v = (v shl 8) or (this[i].toLong() and 0xFF)
            return v
        }
    }
}
