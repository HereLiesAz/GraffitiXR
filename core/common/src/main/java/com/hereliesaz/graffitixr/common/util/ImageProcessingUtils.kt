// FILE: common/src/main/java/com/hereliesaz/graffitixr/common/util/ImageProcessingUtils.kt
package com.hereliesaz.graffitixr.common.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import java.io.ByteArrayOutputStream

/**
 * Translates the raw, unpalatable YUV_420_888 data of the camera sensor
 * into the comforting illusion of an ARGB_8888 Bitmap.
 */
object ImageProcessingUtils {

    /**
     * Surgically extracts the flesh of the camera frame, respecting the
     * hardware's arbitrary row strides, because Android fragmentation
     * guarantees reality is never contiguous.
     *
     * @param image The raw camera image buffer.
     * @return A strictly formatted RGB Bitmap.
     */
    fun yuvToRgbBitmap(image: Image): Bitmap {
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        yBuffer.rewind()
        uBuffer.rewind()
        vBuffer.rewind()

        val ySize = yBuffer.remaining()
        val nv21 = ByteArray(ySize + (image.width * image.height / 2))

        var pos = 0
        if (yPlane.rowStride == image.width) {
            yBuffer.get(nv21, 0, ySize)
            pos = ySize
        } else {
            var yBufferPos = 0
            for (row in 0 until image.height) {
                yBuffer.position(yBufferPos)
                yBuffer.get(nv21, pos, image.width)
                pos += image.width
                yBufferPos += yPlane.rowStride
            }
        }

        val rowStride = vPlane.rowStride
        val pixelStride = vPlane.pixelStride

        if (pixelStride == 2 && rowStride == image.width && uBuffer.get(0) == vBuffer.get(1)) {
            val vuSize = vBuffer.remaining()
            vBuffer.get(nv21, pos, vuSize)
        } else {
            for (row in 0 until image.height / 2) {
                var vBufferPos = row * rowStride
                var uBufferPos = row * uPlane.rowStride
                for (col in 0 until image.width / 2) {
                    nv21[pos++] = vBuffer.get(vBufferPos)
                    nv21[pos++] = uBuffer.get(uBufferPos)
                    vBufferPos += pixelStride
                    uBufferPos += uPlane.pixelStride
                }
            }
        }

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, yuvImage.width, yuvImage.height), 100, out)
        val imageBytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
    }
}