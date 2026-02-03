package com.hereliesaz.graffitixr.common.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer

class BufferCopyTest {

    @Test
    fun testCopyStridedBuffer() {
        val width = 4
        val height = 4
        val rowStride = 6 // 4 bytes of data + 2 bytes padding
        val capacity = rowStride * height

        val buffer = ByteBuffer.allocate(capacity)

        // Fill buffer with known pattern
        // Row 0: 0, 1, 2, 3, [x, x]
        // Row 1: 4, 5, 6, 7, [x, x]
        // ...
        for (row in 0 until height) {
            for (col in 0 until width) {
                buffer.put(row * rowStride + col, (row * width + col).toByte())
            }
        }

        val output = ByteArray(width * height)

        // The logic to test
        copyYPlane(buffer, width, height, rowStride, output)

        // Verify output is contiguous
        for (i in 0 until width * height) {
            assertEquals("Byte at index $i mismatch", i.toByte(), output[i])
        }
    }

    // This is the function I plan to add to ArRenderer (or a util class)
    private fun copyYPlane(buffer: ByteBuffer, width: Int, height: Int, rowStride: Int, output: ByteArray) {
        buffer.rewind()
        if (width == rowStride) {
            // Fast path: contiguous
            buffer.get(output, 0, width * height)
        } else {
            // Slow path: strided
            for (row in 0 until height) {
                buffer.position(row * rowStride)
                buffer.get(output, row * width, width)
            }
        }
    }
}
