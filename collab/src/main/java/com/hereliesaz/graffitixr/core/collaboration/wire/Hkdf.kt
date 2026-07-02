package com.hereliesaz.graffitixr.core.collaboration.wire

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HKDF (RFC 5869) over HMAC-SHA256. Hand-rolled because the JCE exposes no HKDF on minSdk 26;
 * it is a thin wrapper over `Mac("HmacSHA256")` and is covered against the RFC test vectors.
 */
internal object Hkdf {

    private const val HMAC = "HmacSHA256"
    private const val HASH_LEN = 32

    /** HKDF-Extract: PRK = HMAC(salt, ikm). An all-zero salt is used when [salt] is empty. */
    fun extract(salt: ByteArray, ikm: ByteArray): ByteArray {
        val key = if (salt.isEmpty()) ByteArray(HASH_LEN) else salt
        return hmac(key, ikm)
    }

    /** HKDF-Expand: derive [length] bytes of key material from [prk] and context [info]. */
    fun expand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 0..(255 * HASH_LEN)) { "HKDF length $length out of range" }
        val out = ByteArray(length)
        var t = ByteArray(0)
        var pos = 0
        var counter = 1
        while (pos < length) {
            val mac = Mac.getInstance(HMAC).apply { init(SecretKeySpec(prk, HMAC)) }
            mac.update(t)
            mac.update(info)
            mac.update(counter.toByte())
            t = mac.doFinal()
            val n = minOf(t.size, length - pos)
            System.arraycopy(t, 0, out, pos, n)
            pos += n
            counter++
        }
        return out
    }

    fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance(HMAC).apply { init(SecretKeySpec(key, HMAC)) }.doFinal(data)
}
