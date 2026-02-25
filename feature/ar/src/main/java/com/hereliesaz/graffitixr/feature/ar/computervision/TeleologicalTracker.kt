package com.hereliesaz.graffitixr.feature.ar.computervision

import android.graphics.Bitmap
import android.media.Image
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import javax.inject.Inject
import com.hereliesaz.graffitixr.nativebridge.SlamManager

class TeleologicalTracker @Inject constructor(private val slamManager: SlamManager) {

    fun processTeleologicalFrame(image: Image): Mat {
        val yPlane = image.planes[0].buffer
        val ySize = yPlane.remaining()

        val nv21 = ByteArray(ySize)
        yPlane.get(nv21, 0, ySize)

        val grayMat = Mat(image.height, image.width, CvType.CV_8UC1)
        grayMat.put(0, 0, nv21)

        return grayMat
    }

    fun processTeleologicalFrame(bitmap: Bitmap): Mat {
        val mat = Mat()
        Utils.bitmapToMat(bitmap, mat)
        val grayMat = Mat()
        if (mat.channels() == 4) {
            Imgproc.cvtColor(mat, grayMat, Imgproc.COLOR_RGBA2GRAY)
        } else {
            Imgproc.cvtColor(mat, grayMat, Imgproc.COLOR_RGB2GRAY)
        }
        mat.release()
        return grayMat
    }
}