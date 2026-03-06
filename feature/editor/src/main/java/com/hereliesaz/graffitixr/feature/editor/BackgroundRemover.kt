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
            val imgMat = Mat()
            Utils.bitmapToMat(bitmap, imgMat)

            val bgdModel = Mat()
            val fgdModel = Mat()
            val mask = Mat(imgMat.size(), CvType.CV_8UC1, Scalar(Imgproc.GC_BGD.toDouble()))

            val marginX = (imgMat.cols() * 0.1).toInt()
            val marginY = (imgMat.rows() * 0.1).toInt()
            val rect = Rect(marginX, marginY, imgMat.cols() - marginX * 2, imgMat.rows() - marginY * 2)

            Imgproc.grabCut(imgMat, mask, rect, bgdModel, fgdModel, 5, Imgproc.GC_INIT_WITH_RECT)

            val source = Mat(1, 1, CvType.CV_8U, Scalar(Imgproc.GC_PR_FGD.toDouble()))
            val bgSource = Mat(1, 1, CvType.CV_8U, Scalar(Imgproc.GC_FGD.toDouble()))

            val finalMask = Mat()
            org.opencv.core.Core.compare(mask, source, finalMask, org.opencv.core.Core.CMP_EQ)

            val fgMask2 = Mat()
            org.opencv.core.Core.compare(mask, bgSource, fgMask2, org.opencv.core.Core.CMP_EQ)
            org.opencv.core.Core.bitwise_or(finalMask, fgMask2, finalMask)

            val outputMat = Mat(imgMat.size(), CvType.CV_8UC4, Scalar(0.0, 0.0, 0.0, 0.0))
            imgMat.copyTo(outputMat, finalMask)

            val outputBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(outputMat, outputBitmap)

            imgMat.release()
            bgdModel.release()
            fgdModel.release()
            mask.release()
            finalMask.release()
            fgMask2.release()
            source.release()
            bgSource.release()

            Result.success(outputBitmap)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}