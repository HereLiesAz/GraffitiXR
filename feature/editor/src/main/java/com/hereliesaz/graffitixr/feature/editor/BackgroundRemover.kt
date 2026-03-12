// FILE: feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/BackgroundRemover.kt
package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Uses OpenCV GrabCut to remove the background from an image natively.
 * Excising Google ML Kit to return to the offline, strictly decoupled local C++ roots.
 */
class BackgroundRemover @Inject constructor() {

    suspend fun removeBackground(bitmap: Bitmap): Result<Bitmap> = withContext(Dispatchers.Default) {
        try {
            // Convert Android ARGB bitmap → RGBA Mat → BGR Mat (GrabCut requires 3-channel BGR)
            val rgbaMat = Mat()
            Utils.bitmapToMat(bitmap, rgbaMat)

            val bgrMat = Mat()
            Imgproc.cvtColor(rgbaMat, bgrMat, Imgproc.COLOR_RGBA2BGR)

            val bgdModel = Mat()
            val fgdModel = Mat()
            val mask = Mat(bgrMat.size(), CvType.CV_8UC1, Scalar(Imgproc.GC_BGD.toDouble()))

            val marginX = (bgrMat.cols() * 0.1).toInt()
            val marginY = (bgrMat.rows() * 0.1).toInt()
            val rect = Rect(marginX, marginY, bgrMat.cols() - marginX * 2, bgrMat.rows() - marginY * 2)

            Imgproc.grabCut(bgrMat, mask, rect, bgdModel, fgdModel, 5, Imgproc.GC_INIT_WITH_RECT)

            val prFgd = Mat(1, 1, CvType.CV_8U, Scalar(Imgproc.GC_PR_FGD.toDouble()))
            val fgd   = Mat(1, 1, CvType.CV_8U, Scalar(Imgproc.GC_FGD.toDouble()))

            val finalMask = Mat()
            org.opencv.core.Core.compare(mask, prFgd, finalMask, org.opencv.core.Core.CMP_EQ)
            val fgMask2 = Mat()
            org.opencv.core.Core.compare(mask, fgd, fgMask2, org.opencv.core.Core.CMP_EQ)
            org.opencv.core.Core.bitwise_or(finalMask, fgMask2, finalMask)

            // Apply mask to the original RGBA image to preserve transparency
            val outputMat = Mat(rgbaMat.size(), CvType.CV_8UC4, Scalar(0.0, 0.0, 0.0, 0.0))
            rgbaMat.copyTo(outputMat, finalMask)

            val outputBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(outputMat, outputBitmap)

            rgbaMat.release()
            bgrMat.release()
            bgdModel.release()
            fgdModel.release()
            mask.release()
            finalMask.release()
            fgMask2.release()
            prFgd.release()
            fgd.release()
            outputMat.release()

            Result.success(outputBitmap)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
