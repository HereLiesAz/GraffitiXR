package com.hereliesaz.graffitixr.feature.ar.computervision

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import javax.inject.Inject
import com.hereliesaz.graffitixr.nativebridge.SlamManager

/**
 * Handles the computer vision logic for Teleological SLAM (Map Alignment).
 */
class TeleologicalTracker @Inject constructor(private val slamManager: SlamManager) {

    /**
     * Processes a grayscale frame (Y-plane) for feature matching.
     */
    fun processTeleologicalFrame(yData: ByteArray, width: Int, height: Int): Mat {
        val grayMat = Mat(height, width, CvType.CV_8UC1)
        grayMat.put(0, 0, yData)

        // ORB feature detection or solvePnP logic would happen here or be delegated to C++
        // For now, we return the mat to satisfy the legacy pipeline
        return grayMat
    }

    /**
     * Legacy support for Bitmaps.
     */
    fun processTeleologicalFrame(bitmap: Bitmap): Mat {
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
            mat.copyTo(grayMat)
        }

        mat.release()
        if (safeBitmap !== bitmap) {
            safeBitmap.recycle()
        }

        return grayMat
    }
}