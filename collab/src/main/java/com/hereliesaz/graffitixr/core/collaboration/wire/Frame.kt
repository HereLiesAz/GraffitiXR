package com.hereliesaz.graffitixr.core.collaboration.wire

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Length-prefixed wire framing.
 * Wire format: [4 bytes big-endian length][1 byte FrameType code][payload bytes]
 * Maximum payload size: 16 MB (defense against malformed input).
 */
internal object Frame {

    const val MAX_PAYLOAD_BYTES: Int = 16 * 1024 * 1024
    private const val HEADER_TYPE_BYTES = 1

    /** Encode and write a frame to the stream. Caller must flush. */
    @Throws(IOException::class)
    fun write(out: OutputStream, type: FrameType, payload: ByteArray) {
        require(payload.size <= MAX_PAYLOAD_BYTES) {
            "payload size ${payload.size} exceeds max $MAX_PAYLOAD_BYTES"
        }
        val data = DataOutputStream(out)
        data.writeInt(payload.size + HEADER_TYPE_BYTES) // length includes type byte
        data.writeByte(type.code.toInt())
        data.write(payload)
    }

    /**
     * Read one frame. Returns null on clean EOF (peer closed before the next frame).
     * Throws IOException on truncated frame or bad type code.
     */
    @Throws(IOException::class)
    fun read(input: InputStream): FrameRead? {
        val data = DataInputStream(input)
        val length = try {
            data.readInt()
        } catch (eof: EOFException) {
            return null
        }
        if (length < HEADER_TYPE_BYTES) {
            throw IOException("frame length $length below minimum $HEADER_TYPE_BYTES")
        }
        if (length - HEADER_TYPE_BYTES > MAX_PAYLOAD_BYTES) {
            throw IOException("frame payload ${length - HEADER_TYPE_BYTES} exceeds max $MAX_PAYLOAD_BYTES")
        }
        val typeCode = data.readByte()
        val type = FrameType.ofCode(typeCode)
            ?: throw IOException("unknown frame type code 0x${typeCode.toString(16)}")
        val payload = ByteArray(length - HEADER_TYPE_BYTES)
        data.readFully(payload)
        return FrameRead(type, payload)
    }

    data class FrameRead(val type: FrameType, val payload: ByteArray) {
        // Equality based on contents (ByteArray default uses identity).
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is FrameRead) return false
            return type == other.type && payload.contentEquals(other.payload)
        }

        override fun hashCode(): Int = 31 * type.hashCode() + payload.contentHashCode()
    }
}
