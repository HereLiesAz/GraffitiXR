package com.hereliesaz.graffitixr.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.Image

class YuvToRgbConverter(val context: Context) {
    private var nv21: ByteArray? = null
    private var argb: IntArray? = null

    fun yuvToRgb(image: Image, output: Bitmap) {
        if (image.format != ImageFormat.YUV_420_888) return

        val width = image.width
        val height = image.height
        val size = width * height

        // Ensure buffers
        if (nv21 == null || nv21!!.size != size * 3 / 2) {
            nv21 = ByteArray(size * 3 / 2)
            argb = IntArray(size)
        }

        // Capture to local variables to satisfy smart cast requirements
        val localNv21 = nv21 ?: return
        val localArgb = argb ?: return

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        // Y Copy
        if (yPlane.rowStride == width) {
            yBuffer.rewind()
            yBuffer.get(localNv21, 0, size)
        } else {
            var pos = 0
            for (row in 0 until height) {
                yBuffer.position(row * yPlane.rowStride)
                yBuffer.get(localNv21, pos, width)
                pos += width
            }
        }

        // UV Copy
        val uvHeight = height / 2
        val uvWidth = width / 2
        var uvPos = size
        val uPixelStride = uPlane.pixelStride
        val vPixelStride = vPlane.pixelStride
        val uRowStride = uPlane.rowStride
        val vRowStride = vPlane.rowStride

        for (row in 0 until uvHeight) {
            for (col in 0 until uvWidth) {
                val vIndex = row * vRowStride + col * vPixelStride
                val uIndex = row * uRowStride + col * uPixelStride

                // NV21 expects V then U
                localNv21[uvPos++] = vBuffer.get(vIndex)
                localNv21[uvPos++] = uBuffer.get(uIndex)
            }
        }

        decodeYUV420SP(localArgb, localNv21, width, height)
        output.setPixels(localArgb, 0, width, 0, 0, width, height)
    }

    private fun decodeYUV420SP(rgb: IntArray, yuv420sp: ByteArray, width: Int, height: Int) {
        val frameSize = width * height
        var yp = 0
        var uvp = 0
        var i = 0
        var y: Int
        var u = 0
        var v = 0
        var r: Int
        var g: Int
        var b: Int

        for (j in 0 until height) {
            uvp = frameSize + (j shr 1) * width
            u = 0
            v = 0
            for (k in 0 until width) {
                y = (yuv420sp[yp].toInt() and 0xff) - 16
                if (y < 0) y = 0
                if ((k and 1) == 0) {
                    v = (yuv420sp[uvp++].toInt() and 0xff) - 128
                    u = (yuv420sp[uvp++].toInt() and 0xff) - 128
                }

                val y1192 = 1192 * y
                r = (y1192 + 1634 * v)
                g = (y1192 - 833 * v - 400 * u)
                b = (y1192 + 2066 * u)

                if (r < 0) r = 0 else if (r > 262143) r = 262143
                if (g < 0) g = 0 else if (g > 262143) g = 262143
                if (b < 0) b = 0 else if (b > 262143) b = 262143

                rgb[i] = -0x1000000 or ((r shl 6) and 0xff0000) or ((g shr 2) and 0xff00) or ((b shr 10) and 0xff)
                i++
                yp++
            }
        }
    }
}