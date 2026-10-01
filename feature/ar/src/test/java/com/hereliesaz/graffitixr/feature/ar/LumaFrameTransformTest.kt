package com.hereliesaz.graffitixr.feature.ar

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class LumaFrameTransformTest {

    private fun stridedSource(): ByteBuffer {
        val bytes = ByteArray(13)
        bytes[0] = 1
        bytes[2] = 2
        bytes[4] = 3
        bytes[8] = 4
        bytes[10] = 5
        bytes[12] = 6
        return ByteBuffer.wrap(bytes)
    }

    @Test
    fun packsPixelAndRowStride_withoutRotating() {
        val out = LumaFrameTransform.packAndRotate(stridedSource(), 3, 2, 8, 2, 0)
        assertEquals(3, out.width)
        assertEquals(2, out.height)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), out.bytes)
    }

    @Test
    fun rotatesClockwise90_intoDisplayOrientation() {
        val out = LumaFrameTransform.packAndRotate(stridedSource(), 3, 2, 8, 2, 90)
        assertEquals(2, out.width)
        assertEquals(3, out.height)
        assertArrayEquals(byteArrayOf(4, 1, 5, 2, 6, 3), out.bytes)
    }

    @Test
    fun cropsStridedSource_beforeRotating() {
        // Logical 4x3 image with pixelStride=1, rowStride=4:
        //  1  2  3  4
        //  5  6  7  8
        //  9 10 11 12
        val source = ByteBuffer.wrap(byteArrayOf(
            1, 2, 3, 4,
            5, 6, 7, 8,
            9, 10, 11, 12,
        ))
        val out = LumaFrameTransform.packCropAndRotate(
            source = source,
            sourceWidth = 4,
            sourceHeight = 3,
            rowStride = 4,
            pixelStride = 1,
            cropLeft = 1,
            cropTop = 1,
            cropWidth = 2,
            cropHeight = 2,
            rotationDegrees = 90,
        )
        assertEquals(2, out.width)
        assertEquals(2, out.height)
        // Cropped 6,7 / 10,11, then clockwise 90°.
        assertArrayEquals(byteArrayOf(10, 6, 11, 7), out.bytes)
    }

    @Test
    fun rotates180_and270() {
        val r180 = LumaFrameTransform.packAndRotate(stridedSource(), 3, 2, 8, 2, 180)
        assertArrayEquals(byteArrayOf(6, 5, 4, 3, 2, 1), r180.bytes)

        val r270 = LumaFrameTransform.packAndRotate(stridedSource(), 3, 2, 8, 2, 270)
        assertEquals(2, r270.width)
        assertEquals(3, r270.height)
        assertArrayEquals(byteArrayOf(3, 6, 2, 5, 1, 4), r270.bytes)
    }
}
