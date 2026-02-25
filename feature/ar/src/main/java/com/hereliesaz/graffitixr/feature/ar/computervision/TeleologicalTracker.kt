package com.hereliesaz.graffitixr.feature.ar.computervision

import android.media.Image
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import javax.inject.Inject
import com.hereliesaz.graffitixr.nativebridge.SlamManager

class TeleologicalTracker @Inject constructor(private val slamManager: SlamManager) {

    /**
     * Converts a YUV_420_888 Image directly to a Grayscale OpenCV Mat.
     * We bypass the Android Bitmap conversion entirely to save frame rate.
     * Furthermore, we cheat reality by only extracting the Y plane,
     * which inherently serves as our grayscale source.
     */
    fun processFrame(image: Image): Mat {
        val yPlane = image.planes[0].buffer
        val ySize = yPlane.remaining()

        val nv21 = ByteArray(ySize)
        yPlane.get(nv21, 0, ySize)

        val grayMat = Mat(image.height, image.width, CvType.CV_8UC1)
        grayMat.put(0, 0, nv21)

        return grayMat
    }
}