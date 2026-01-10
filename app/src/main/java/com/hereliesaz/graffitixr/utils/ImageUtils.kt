package com.hereliesaz.graffitixr.utils

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.Log
import com.hereliesaz.graffitixr.data.Fingerprint
import com.hereliesaz.graffitixr.data.RefinementPath
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfKeyPoint
import org.opencv.features2d.ORB
import org.opencv.imgproc.Imgproc

/**
 * Converts a bitmap to a line drawing (edge detection).
 *
 * @param bitmap The source bitmap.
 * @param isWhite If true, the lines will be white on a transparent background.
 * @return A new bitmap containing the edge data as an alpha channel.
 */
fun convertToLineDrawing(bitmap: Bitmap, isWhite: Boolean = true): Bitmap {
    val mat = Mat()
    val grayMat = Mat()
    val edges = Mat()
    val colorChannel = Mat()
    val rgbaMat = Mat()

    try {
        Utils.bitmapToMat(bitmap, mat)

        Imgproc.cvtColor(mat, grayMat, Imgproc.COLOR_BGR2GRAY)
        Imgproc.Canny(grayMat, edges, 50.0, 150.0)

        val colorValue = if (isWhite) 255.0 else 0.0
        colorChannel.create(edges.size(), org.opencv.core.CvType.CV_8UC1)
        colorChannel.setTo(org.opencv.core.Scalar(colorValue))

        val channels = java.util.ArrayList<Mat>()
        channels.add(colorChannel) // R
        channels.add(colorChannel) // G
        channels.add(colorChannel) // B
        channels.add(edges)        // A

        org.opencv.core.Core.merge(channels, rgbaMat)

        val resultBitmap = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(rgbaMat, resultBitmap)
        return resultBitmap
    } finally {
        mat.release()
        grayMat.release()
        edges.release()
        colorChannel.release()
        rgbaMat.release()
    }
}

/**
 * Calculates a blur metric using the variance of the Laplacian.
 * Higher values indicate sharper images.
 */
fun calculateBlurMetric(bitmap: Bitmap): Double {
    val mat = Mat()
    val grayMat = Mat()
    val laplacianImage = Mat()
    val mean = MatOfDouble()
    val stdDev = MatOfDouble()
    
    try {
        Utils.bitmapToMat(bitmap, mat)
        Imgproc.cvtColor(mat, grayMat, Imgproc.COLOR_BGR2GRAY)
        Imgproc.Laplacian(grayMat, laplacianImage, org.opencv.core.CvType.CV_64F)
        org.opencv.core.Core.meanStdDev(laplacianImage, mean, stdDev)
        val stdDevVal = stdDev.get(0, 0)[0]
        return stdDevVal * stdDevVal
    } finally {
        mat.release()
        grayMat.release()
        laplacianImage.release()
        mean.release()
        stdDev.release()
    }
}

/**
 * Estimates the number of features detectable by ORB in the image.
 * Useful for assessing if an image is a good candidate for AR tracking.
 */
fun estimateFeatureRichness(bitmap: Bitmap): Int {
    val mat = Mat()
    val grayMat = Mat()
    val keypoints = MatOfKeyPoint()
    val orb = ORB.create()
    
    try {
        Utils.bitmapToMat(bitmap, mat)
        Imgproc.cvtColor(mat, grayMat, Imgproc.COLOR_BGR2GRAY)
        orb.detect(grayMat, keypoints)
        return keypoints.rows()
    } finally {
        mat.release()
        grayMat.release()
        keypoints.release()
        // Note: orb.clear() or similar might be needed if it was a native object, 
        // but ORB in Java wrapper doesn't have a release() usually, it's a factory object.
    }
}

/**
 * Detects features in the bitmap, respecting the provided refinement masks.
 * Returns the keypoints as normalized offsets (0..1).
 */
fun detectFeaturesWithMask(bitmap: Bitmap, refinementPaths: List<RefinementPath>, autoMask: Bitmap? = null): List<androidx.compose.ui.geometry.Offset> {
    val mat = Mat()
    val grayMat = Mat()
    val maskMat = Mat()
    val keypoints = MatOfKeyPoint()
    val orb = ORB.create()
    
    try {
        Utils.bitmapToMat(bitmap, mat)
        Imgproc.cvtColor(mat, grayMat, Imgproc.COLOR_BGR2GRAY)

        val createdMask = createMaskMatFromPaths(bitmap.width, bitmap.height, refinementPaths, autoMask)
        createdMask.copyTo(maskMat)
        createdMask.release()

        orb.detect(grayMat, keypoints, maskMat)

        val pointList = keypoints.toList()
        return pointList.map {
            androidx.compose.ui.geometry.Offset(it.pt.x.toFloat() / bitmap.width, it.pt.y.toFloat() / bitmap.height)
        }
    } finally {
        mat.release()
        grayMat.release()
        maskMat.release()
        keypoints.release()
    }
}

/**
 * Creates an OpenCV Mask (CV_8UC1) from the refinement paths and an optional auto-generated mask.
 * White pixels in the mask indicate areas to process; black pixels are ignored.
 */
fun createMaskMatFromPaths(width: Int, height: Int, refinementPaths: List<RefinementPath>, autoMask: Bitmap? = null): Mat {
    val maskBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(maskBitmap)

    if (autoMask != null) {
        val scaledMask = if (autoMask.width != width || autoMask.height != height) {
             Bitmap.createScaledBitmap(autoMask, width, height, true)
        } else {
             autoMask
        }
        canvas.drawBitmap(scaledMask, 0f, 0f, null)
    } else {
        canvas.drawColor(android.graphics.Color.WHITE)
    }

    val paint = Paint().apply {
        strokeWidth = 50f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    refinementPaths.forEach { rPath ->
        paint.color = if (rPath.isEraser) android.graphics.Color.WHITE else android.graphics.Color.BLACK
        val path = Path()
        if (rPath.points.isNotEmpty()) {
            path.moveTo(rPath.points.first().x * width, rPath.points.first().y * height)
            for (i in 1 until rPath.points.size) {
                path.lineTo(rPath.points[i].x * width, rPath.points[i].y * height)
            }
        }
        canvas.drawPath(path, paint)
    }

    val maskMatTemp = Mat()
    val maskMat = Mat()
    try {
        Utils.bitmapToMat(maskBitmap, maskMatTemp)
        Imgproc.cvtColor(maskMatTemp, maskMat, Imgproc.COLOR_BGR2GRAY)
        return maskMat
    } finally {
        maskMatTemp.release()
        // maskMat is returned, caller must release it
    }
}

/**
 * Applies the mask defined by refinement paths to the source bitmap.
 * The result is a bitmap where masked-out areas are transparent.
 */
fun applyMaskToBitmap(source: Bitmap, refinementPaths: List<RefinementPath>, autoMask: Bitmap? = null): Bitmap {
    val mat = Mat()
    val maskMat = Mat()
    val resultMat = Mat()
    
    try {
        Utils.bitmapToMat(source, mat)

        val createdMask = createMaskMatFromPaths(source.width, source.height, refinementPaths, autoMask)
        createdMask.copyTo(maskMat)
        createdMask.release()

        resultMat.create(mat.size(), mat.type())
        resultMat.setTo(org.opencv.core.Scalar(0.0, 0.0, 0.0, 255.0)) // Transparent

        mat.copyTo(resultMat, maskMat)

        val finalBitmap = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(resultMat, finalBitmap)

        return finalBitmap
    } finally {
        mat.release()
        maskMat.release()
        resultMat.release()
    }
}

/**
 * Generates the unique [Fingerprint] (Keypoints + Descriptors) for the given bitmap and mask.
 * This fingerprint is used for persistence and identifying the target in future sessions.
 */
fun resizeBitmapForArCore(bitmap: Bitmap, maxSize: Int = 1024): Bitmap {
    val maxDimension = maxOf(bitmap.width, bitmap.height)
    if (maxDimension <= maxSize) return bitmap

    val scale = maxSize.toFloat() / maxDimension
    val newWidth = (bitmap.width * scale).toInt()
    val newHeight = (bitmap.height * scale).toInt()

    return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
}

fun generateFingerprint(bitmap: Bitmap, refinementPaths: List<RefinementPath>, autoMask: Bitmap? = null): Fingerprint {
    val mat = Mat()
    val grayMat = Mat()
    val maskMat = Mat()
    val keypoints = MatOfKeyPoint()
    val descriptors = Mat()
    val orb = ORB.create()
    
    try {
        Utils.bitmapToMat(bitmap, mat)
        Imgproc.cvtColor(mat, grayMat, Imgproc.COLOR_BGR2GRAY)

        val createdMask = createMaskMatFromPaths(bitmap.width, bitmap.height, refinementPaths, autoMask)
        createdMask.copyTo(maskMat)
        createdMask.release()

        orb.detectAndCompute(grayMat, maskMat, keypoints, descriptors)

        if (descriptors.rows() == 0) {
            Log.w("ImageUtils", "No descriptors generated for fingerprint")
        }

        return Fingerprint(keypoints.toList(), descriptors.clone())
    } catch (e: Exception) {
        Log.e("ImageUtils", "Error generating fingerprint", e)
        throw e
    } finally {
        mat.release()
        grayMat.release()
        maskMat.release()
        keypoints.release()
        descriptors.release()
    }
}
