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
        // OpenCV asserts the bitmap MUST be ARGB_8888 or RGB_565.
        // CameraX/DualAnalyzer might be feeding us hardware bitmaps or other configs.
        val safeBitmap = if (bitmap.config != Bitmap.Config.ARGB_8888) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return Mat()
        } else {
            bitmap
        }

        val mat = Mat()
        Utils.bitmapToMat(safeBitmap, mat)

        val grayMat = Mat()
        if (mat.channels() == 4) {
            Imgproc.cvtColor(mat, grayMat, Imgproc.COLOR_RGBA2GRAY)
        } else if (mat.channels() == 3) {
            Imgproc.cvtColor(mat, grayMat, Imgproc.COLOR_RGB2GRAY)
        } else {
            // Already grayscale or a format OpenCV can't natively resolve via generic cvtColor.
            mat.copyTo(grayMat)
        }

        mat.release()

        // Only recycle if we created a temporary copy. Don't destroy the source payload.
        if (safeBitmap !== bitmap) {
            safeBitmap.recycle()
        }

        return grayMat
    }
}