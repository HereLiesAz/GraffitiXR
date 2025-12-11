package com.hereliesaz.graffitixr.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.Image
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicYuvToRGB
import android.renderscript.Type
import java.nio.ByteBuffer

/**
 * Helper class for converting YUV images to RGB bitmaps.
 *
 * This class uses RenderScript to perform the YUV to RGB conversion, which is
 * much faster than doing it on the CPU.
 */
@Suppress("DEPRECATION")
class YuvToRgbConverter(context: Context) {

    private val rs = RenderScript.create(context)
    private val scriptYuvToRgb = ScriptIntrinsicYuvToRGB.create(rs, Element.U8_4(rs))

    // These allocations are reusable, so we create them only once.
    private var yuvBits: ByteBuffer? = null
    private var yuvAllocation: Allocation? = null
    private var rgbAllocation: Allocation? = null

    /**
     * Converts a YUV [Image] to a [Bitmap].
     *
     * @param image The YUV image to convert.
     * @return The converted RGB bitmap.
     */
    @Synchronized
    fun yuvToRgb(image: Image, output: Bitmap) {
        if (image.format != ImageFormat.YUV_420_888) {
            throw IllegalArgumentException("Invalid image format")
        }

        val width = image.width
        val height = image.height

        // Create the RenderScript allocations if they don\'t exist or the image dimensions have changed.
        if (yuvAllocation == null) {
            val yuvType = Type.Builder(rs, Element.U8(rs)).setX(width * height * 3 / 2)
            yuvAllocation = Allocation.createTyped(rs, yuvType.create(), Allocation.USAGE_SCRIPT)
            yuvBits = ByteBuffer.allocate(width * height * 3 / 2)
        }

        if (rgbAllocation == null || rgbAllocation!!.type.x != width || rgbAllocation!!.type.y != height) {
            val rgbType = Type.Builder(rs, Element.RGBA_8888(rs)).setX(width).setY(height)
            rgbAllocation = Allocation.createTyped(rs, rgbType.create(), Allocation.USAGE_SCRIPT)
        }

        yuvBits?.rewind()

        // Copy the Y, U, and V planes into a single contiguous buffer.
        image.planes.forEachIndexed { i, plane ->
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride

            val planeWidth = if (i == 0) width else width / 2
            val planeHeight = if (i == 0) height else height / 2

            if (pixelStride == 1 && rowStride == planeWidth) {
                // If the plane is contiguous, we can copy it directly.
                yuvBits!!.put(buffer)
            } else {
                // Otherwise, we have to copy it row by row.
                val rowData = ByteArray(rowStride)
                for (row in 0 until planeHeight - 1) {
                    buffer.get(rowData, 0, rowStride)
                    yuvBits!!.put(rowData, 0, planeWidth)
                }
                // Last row is a special case to avoid a buffer overflow.
                buffer.get(rowData, 0, minOf(rowStride, buffer.remaining()))
                yuvBits!!.put(rowData, 0, planeWidth)
            }
        }

        yuvBits?.rewind()
        yuvAllocation!!.copyFrom(yuvBits!!.array())

        scriptYuvToRgb.setInput(yuvAllocation)
        scriptYuvToRgb.forEach(rgbAllocation)
        rgbAllocation!!.copyTo(output)
    }
}