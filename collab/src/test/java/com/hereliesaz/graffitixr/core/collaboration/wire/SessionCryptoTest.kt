package com.hereliesaz.graffitixr.core.collaboration.wire

import javax.crypto.AEADBadTagException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class SessionCryptoTest {

    private val token = "shared-qr-token"
    private val sessionId = "sid-123"
    private val guestNonce = ByteArray(16) { it.toByte() }
    private val hostNonce = ByteArray(16) { (it * 2 + 1).toByte() }

    private fun pair(): Pair<SessionCrypto, SessionCrypto> =
        SessionCrypto.forHost(token, sessionId, guestNonce, hostNonce) to
            SessionCrypto.forGuest(token, sessionId, guestNonce, hostNonce)

    @Test
    fun `host to guest round-trips`() {
        val (host, guest) = pair()
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val opened = guest.open(host.seal(FrameType.DELTA, payload))
        assertEquals(FrameType.DELTA, opened.type)
        assertArrayEquals(payload, opened.payload)
    }

    @Test
    fun `guest to host round-trips on the reverse keys`() {
        val (host, guest) = pair()
        val payload = byteArrayOf(9, 8, 7)
        val opened = host.open(guest.seal(FrameType.DELTA_ACK, payload))
        assertEquals(FrameType.DELTA_ACK, opened.type)
        assertArrayEquals(payload, opened.payload)
    }

    @Test
    fun `tampered ciphertext fails authentication`() {
        val (host, guest) = pair()
        val sealed = host.seal(FrameType.DELTA, byteArrayOf(1, 2, 3))
        sealed[sealed.size - 1] = (sealed[sealed.size - 1].toInt() xor 0x01).toByte()
        assertThrows(AEADBadTagException::class.java) { guest.open(sealed) }
    }

    @Test
    fun `wrong token cannot decrypt`() {
        val host = SessionCrypto.forHost(token, sessionId, guestNonce, hostNonce)
        val wrongGuest = SessionCrypto.forGuest("different-token", sessionId, guestNonce, hostNonce)
        assertThrows(AEADBadTagException::class.java) {
            wrongGuest.open(host.seal(FrameType.DELTA, byteArrayOf(1)))
        }
    }

    @Test
    fun `replayed counter is rejected`() {
        val (host, guest) = pair()
        val first = host.seal(FrameType.PING, byteArrayOf(0))
        host.seal(FrameType.PING, byteArrayOf(0)) // advance host counter
        guest.open(first)
        // Re-delivering the first frame (counter now <= lastRecv) must be refused.
        assertThrows(AEADBadTagException::class.java) { guest.open(first) }
    }

    @Test
    fun `direction keys differ so a frame cannot be opened with the sender's own crypto`() {
        val (host, _) = pair()
        val sealed = host.seal(FrameType.DELTA, byteArrayOf(4, 2))
        // Host's recv key is the guest→host key, not its own send key.
        assertThrows(AEADBadTagException::class.java) { host.open(sealed) }
    }

    @Test
    fun `each message uses a distinct nonce`() {
        val (host, _) = pair()
        val a = host.seal(FrameType.DELTA, byteArrayOf(0))
        val b = host.seal(FrameType.DELTA, byteArrayOf(0))
        // Same plaintext, different counter => different first 8 (counter) and ciphertext bytes.
        assertFalse(a.contentEquals(b))
    }
}
