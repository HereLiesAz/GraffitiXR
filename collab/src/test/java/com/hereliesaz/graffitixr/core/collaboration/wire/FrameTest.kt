package com.hereliesaz.graffitixr.core.collaboration.wire

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

class FrameTest {

    @Test
    fun `round-trip empty payload`() {
        val out = ByteArrayOutputStream()
        Frame.write(out, FrameType.PING, ByteArray(0))
        val read = Frame.read(ByteArrayInputStream(out.toByteArray()))
        assertEquals(Frame.FrameRead(FrameType.PING, ByteArray(0)), read)
    }

    @Test
    fun `round-trip non-empty payload`() {
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val out = ByteArrayOutputStream()
        Frame.write(out, FrameType.DELTA, payload)
        val read = Frame.read(ByteArrayInputStream(out.toByteArray()))
        assertEquals(Frame.FrameRead(FrameType.DELTA, payload), read)
    }

    @Test
    fun `clean EOF returns null`() {
        val read = Frame.read(ByteArrayInputStream(ByteArray(0)))
        assertNull(read)
    }

    @Test
    fun `truncated frame throws IOException`() {
        val out = ByteArrayOutputStream()
        Frame.write(out, FrameType.DELTA, byteArrayOf(1, 2, 3, 4, 5))
        val truncated = out.toByteArray().copyOfRange(0, 6) // mid-payload
        assertThrows(IOException::class.java) {
            Frame.read(ByteArrayInputStream(truncated))
        }
    }

    @Test
    fun `oversized payload throws on write`() {
        val out = ByteArrayOutputStream()
        val tooBig = ByteArray(Frame.MAX_PAYLOAD_BYTES + 1)
        assertThrows(IllegalArgumentException::class.java) {
            Frame.write(out, FrameType.DELTA, tooBig)
        }
    }

    @Test
    fun `oversized declared length throws on read`() {
        val out = ByteArrayOutputStream()
        // Manually craft a header declaring more than MAX_PAYLOAD_BYTES + 1
        val dataOut = java.io.DataOutputStream(out)
        dataOut.writeInt(Frame.MAX_PAYLOAD_BYTES + 2)
        dataOut.writeByte(FrameType.DELTA.code.toInt())
        assertThrows(IOException::class.java) {
            Frame.read(ByteArrayInputStream(out.toByteArray()))
        }
    }

    @Test
    fun `unknown frame type code throws`() {
        val out = ByteArrayOutputStream()
        val dataOut = java.io.DataOutputStream(out)
        dataOut.writeInt(1)
        dataOut.writeByte(0x7F)
        assertThrows(IOException::class.java) {
            Frame.read(ByteArrayInputStream(out.toByteArray()))
        }
    }
}
