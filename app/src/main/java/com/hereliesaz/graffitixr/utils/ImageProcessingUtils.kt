package com.hereliesaz.graffitixr.utils

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import com.hereliesaz.graffitixr.data.Fingerprint
import com.hereliesaz.graffitixr.data.RefinementPath
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfKeyPoint
import org.opencv.features2d.ORB
import org.opencv.imgproc.Imgproc
import kotlin.math.min

/**
 * Utility functions for advanced image processing using OpenCV.
 */

fun enhanceImageForAr(bitmap: Bitmap): Bitmap {
    if (!ensureOpenCVLoaded()) return bitmap
    // Simple pass-through for now, can be enhanced with histogram equalization or sharpening
    return bitmap
}

fun resizeBitmapForArCore(bitmap: Bitmap): Bitmap {
    val maxDimension = 1024
    if (bitmap.width <= maxDimension && bitmap.height <= maxDimension) return bitmap

    val ratio = min(maxDimension.toFloat() / bitmap.width, maxDimension.toFloat() / bitmap.height)
    val newWidth = (bitmap.width * ratio).toInt()
    val newHeight = (bitmap.height * ratio).toInt()

    return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
}

fun convertToLineDrawing(bitmap: Bitmap, isWhite: Boolean): Bitmap {
    if (!ensureOpenCVLoaded()) return bitmap

    val mat = Mat()
    Utils.bitmapToMat(bitmap, mat)

    val gray = Mat()
    Imgproc.cvtColor(mat, gray, Imgproc.COLOR_RGB2GRAY)

    val edges = Mat()
    Imgproc.Canny(gray, edges, 50.0, 150.0)

    if (!isWhite) {
        Core.bitwise_not(edges, edges)
    }

    // Create an RGBA image from the edges
    // Canny is single channel. We need to convert to RGBA for Bitmap.
    // If isWhite (White Lines), edges are 255. Background 0.
    // If !isWhite (Black Lines), edges are 0. Background 255.

    val resultMat = Mat()
    Imgproc.cvtColor(edges, resultMat, Imgproc.COLOR_GRAY2RGBA)

    // If we want transparency, we'd need more complex handling,
    // but typically line drawing replaces the image.

    val result = Bitmap.createBitmap(resultMat.cols(), resultMat.rows(), Bitmap.Config.ARGB_8888)
    Utils.matToBitmap(resultMat, result)

    mat.release()
    gray.release()
    edges.release()
    resultMat.release()

    return result
}

fun detectFeaturesWithMask(bitmap: Bitmap, refinementPaths: List<RefinementPath>, mask: Bitmap?): List<Offset> {
    if (!ensureOpenCVLoaded()) return emptyList()

    val mat = Mat()
    Utils.bitmapToMat(bitmap, mat)
    Imgproc.cvtColor(mat, mat, Imgproc.COLOR_RGBA2GRAY)

    val maskMat = Mat()
    if (mask != null) {
        Utils.bitmapToMat(mask, maskMat)
        // Ensure mask is single channel
        if (maskMat.channels() > 1) {
             Imgproc.cvtColor(maskMat, maskMat, Imgproc.COLOR_RGBA2GRAY)
        }
    }

    // TODO: Apply refinementPaths to maskMat if needed to exclude areas

    val orb = ORB.create()
    val keypoints = MatOfKeyPoint()

    orb.detect(mat, keypoints, if (maskMat.empty()) null else maskMat)

    val points = keypoints.toList().map { Offset(it.pt.x.toFloat(), it.pt.y.toFloat()) }

    mat.release()
    maskMat.release()
    keypoints.release()

    return points
}

fun generateFingerprint(bitmap: Bitmap, refinementPaths: List<RefinementPath>, mask: Bitmap?): Fingerprint {
    if (!ensureOpenCVLoaded()) return Fingerprint(emptyList(), ByteArray(0), 0, 0, 0)

    val mat = Mat()
    Utils.bitmapToMat(bitmap, mat)
    Imgproc.cvtColor(mat, mat, Imgproc.COLOR_RGBA2GRAY)

    val maskMat = Mat()
    if (mask != null) {
        Utils.bitmapToMat(mask, maskMat)
        if (maskMat.channels() > 1) {
             Imgproc.cvtColor(maskMat, maskMat, Imgproc.COLOR_RGBA2GRAY)
        }
    }

    // TODO: Apply refinementPaths to maskMat

    val orb = ORB.create()
    val keypoints = MatOfKeyPoint()
    val descriptors = Mat()

    orb.detectAndCompute(mat, if (maskMat.empty()) null else maskMat, keypoints, descriptors)

    val kpsList = keypoints.toList()

    val descriptorsData = ByteArray((descriptors.total() * descriptors.elemSize()).toInt())
    if (descriptors.rows() > 0) {
        descriptors.get(0, 0, descriptorsData)
    }

    val fingerprint = Fingerprint(
        keypoints = kpsList,
        descriptorsData = descriptorsData,
        descriptorsRows = descriptors.rows(),
        descriptorsCols = descriptors.cols(),
        descriptorsType = descriptors.type()
    )

    mat.release()
    maskMat.release()
    keypoints.release()
    descriptors.release()

    return fingerprint
}
