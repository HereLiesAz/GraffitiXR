package com.hereliesaz.graffitixr.graphics

import android.media.Image
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.nio.ByteBuffer

/**
 * A helper class for converting image formats.
 *
 * This class provides a utility method to convert an Android [Image] in YUV_420_888 format,
 * which is commonly provided by ARCore and CameraX, into an OpenCV [Mat] object that can be
 * used for computer vision tasks.
 */
object ImageConverter {

    /**
     * Converts an [Image] in YUV_420_888 format to an OpenCV [Mat] in RGBA format.
     *
     * @param image The input [Image] to convert.
     * @return A new [Mat] object in RGBA format.
     */
    fun toMat(image: Image): Mat {
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)

        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuv = Mat(image.height + image.height / 2, image.width, CvType.CV_8UC1)
        yuv.put(0, 0, nv21)
        val rgba = Mat()
        Imgproc.cvtColor(yuv, rgba, Imgproc.COLOR_YUV2RGBA_NV21, 4)

        return rgba
    }
}