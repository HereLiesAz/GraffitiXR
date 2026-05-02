package com.hereliesaz.graffitixr.core.collaboration.wire

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.runner.RunWith

// Uses Robolectric because android.net.Uri is involved.
// Pin SDK to 34; compileSdk=37 exceeds what Robolectric 4.16.1 ships shadows for by default.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QrPayloadTest {

    @Test
    fun `encode produces expected string`() {
        val payload = QrPayload(host = "192.168.1.5", port = 12345, token = "abc", protocolVersion = 1)
        assertEquals("gxr://coop?h=192.168.1.5&p=12345&t=abc&v=1", payload.encode())
    }

    @Test
    fun `decode round-trips`() {
        val original = QrPayload(host = "10.0.0.1", port = 4096, token = "xyz", protocolVersion = 2)
        assertEquals(original, QrPayload.parse(original.encode()))
    }

    @Test
    fun `parse rejects wrong scheme`() {
        assertThrows(IllegalArgumentException::class.java) {
            QrPayload.parse("http://coop?h=1.1.1.1&p=80&t=x&v=1")
        }
    }

    @Test
    fun `parse rejects wrong host keyword`() {
        assertThrows(IllegalArgumentException::class.java) {
            QrPayload.parse("gxr://other?h=1.1.1.1&p=80&t=x&v=1")
        }
    }

    @Test
    fun `parse rejects missing fields`() {
        assertThrows(IllegalStateException::class.java) {
            QrPayload.parse("gxr://coop?h=1.1.1.1&p=80&t=x") // no v
        }
    }

    @Test
    fun `parse rejects out-of-range port`() {
        assertThrows(IllegalArgumentException::class.java) {
            QrPayload.parse("gxr://coop?h=1.1.1.1&p=99999&t=x&v=1")
        }
    }

    @Test
    fun `parse rejects oversized token`() {
        val bigToken = "a".repeat(257)
        assertThrows(IllegalArgumentException::class.java) {
            QrPayload.parse("gxr://coop?h=1.1.1.1&p=80&t=$bigToken&v=1")
        }
    }

    @Test
    fun `newToken produces distinct values`() {
        assertNotEquals(QrPayload.newToken(), QrPayload.newToken())
    }
}
