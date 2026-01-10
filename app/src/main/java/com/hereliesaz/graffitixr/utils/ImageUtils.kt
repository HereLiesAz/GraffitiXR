package com.hereliesaz.graffitixr.utils

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.Log
import com.hereliesaz.graffitixr.data.Fingerprint
import com.hereliesaz.graffitixr.data.RefinementPath
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.Scalar
import org.opencv.features2d.ORB
import org.opencv.imgproc.Imgproc

/**
 * Ensures OpenCV is loaded and available for the current thread.
 * Bolt Optimization: Call this at the start of any function using OpenCV to prevent n_delete crashes.
 */
fun ensureOpenCVLoaded(): Boolean {
    if (OpenCVLoader.initLocal()) return true
    try {
        System.loadLibrary("opencv_java4")
        return true
    } catch (e: Exception) {
        try {
            System.loadLibrary("opencv_java")
            return true
        } catch (e2: Exception) {
            return false
        }
    }
}

/**
 * Converts a bitmap to a line drawing (edge detection).
 */
fun convertToLineDrawing(bitmap: Bitmap, isWhite: Boolean = true): Bitmap {
    if (!ensureOpenCVLoaded()) return bitmap
    
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
        colorChannel.create(edges.size(), CvType.CV_8UC1)
        colorChannel.setTo(Scalar(colorValue))

        val channels = java.util.ArrayList<Mat>()
        channels.add(colorChannel) // R
        channels.add(colorChannel) // G
        channels.add(colorChannel) // B
        channels.add(edges)        // A

        Core.merge(channels, rgbaMat)

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
 */
fun calculateBlurMetric(bitmap: Bitmap): Double {
    if (!ensureOpenCVLoaded()) return 0.0
    
    val mat = Mat()
    val grayMat = Mat()
    val laplacianImage = Mat()
    val mean = MatOfDouble()
    val stdDev = MatOfDouble()
    
    try {
        Utils.bitmapToMat(bitmap, mat)
        Imgproc.cvtColor(mat, grayMat, Imgproc.COLOR_BGR2GRAY)
        Imgproc.Laplacian(grayMat, laplacianImage, CvType.CV_64F)
        Core.meanStdDev(laplacianImage, mean, stdDev)
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
 */
fun estimateFeatureRichness(bitmap: Bitmap): Int {
    if (!ensureOpenCVLoaded()) return 0
    
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
    }
}

/**
 * Detects features in the bitmap, respecting the provided refinement masks.
 */
fun detectFeaturesWithMask(bitmap: Bitmap, refinementPaths: List<RefinementPath>, autoMask: Bitmap? = null): List<androidx.compose.ui.geometry.Offset> {
    if (!ensureOpenCVLoaded()) return emptyList()
    
    val mat = Mat()
    val grayMat = Mat()
    val maskMat = Mat()
    val keypoints = MatOfKeyPoint()
    val orb = ORB.create()
    
    try {
        Utils.bitmapToMat(bitmap, mat)
        Imgproc.cvtColor(mat, grayMat, Imgproc.COLOR_BGR2GRAY)

        val createdMask = createMaskMatFromPaths(bitmap.width, bitmap.height, refinementPaths, autoMask)
        try {
            orb.detect(grayMat, keypoints, createdMask)
        } finally {
            createdMask.release()
        }

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
 * Caller MUST release the returned Mat.
 */
fun createMaskMatFromPaths(width: Int, height: Int, refinementPaths: List<RefinementPath>, autoMask: Bitmap? = null): Mat {
    ensureOpenCVLoaded()
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
    }
}

/**
 * Applies the mask defined by refinement paths to the source bitmap.
 */
fun applyMaskToBitmap(source: Bitmap, refinementPaths: List<RefinementPath>, autoMask: Bitmap? = null): Bitmap {
    if (!ensureOpenCVLoaded()) return source
    
    val mat = Mat()
    val resultMat = Mat()
    
    try {
        Utils.bitmapToMat(source, mat)

        val createdMask = createMaskMatFromPaths(source.width, source.height, refinementPaths, autoMask)
        try {
            resultMat.create(mat.size(), mat.type())
            resultMat.setTo(Scalar(0.0, 0.0, 0.0, 255.0)) // Transparent
            mat.copyTo(resultMat, createdMask)
        } finally {
            createdMask.release()
        }

        val finalBitmap = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(resultMat, finalBitmap)
        return finalBitmap
    } finally {
        mat.release()
        resultMat.release()
    }
}

fun resizeBitmapForArCore(bitmap: Bitmap, maxSize: Int = 1024): Bitmap {
    val maxDimension = maxOf(bitmap.width, bitmap.height)
    if (maxDimension <= maxSize) return bitmap

    val scale = maxSize.toFloat() / maxDimension
    val newWidth = (bitmap.width * scale).toInt()
    val newHeight = (bitmap.height * scale).toInt()

    return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
}

/**
 * Generates the unique [Fingerprint] (Keypoints + Descriptors) for the given bitmap and mask.
 * 
 * Bolt Optimization: Immediately converts Mat descriptors to ByteArray and releases the Mat
 * to prevent finalizer UnsatisfiedLinkError crashes.
 */
fun generateFingerprint(bitmap: Bitmap, refinementPaths: List<RefinementPath>, autoMask: Bitmap? = null): Fingerprint {
    if (!ensureOpenCVLoaded()) throw IllegalStateException("OpenCV not available")
    
    val mat = Mat()
    val grayMat = Mat()
    val keypoints = MatOfKeyPoint()
    val descriptors = Mat()
    val orb = ORB.create()
    
    try {
        Utils.bitmapToMat(bitmap, mat)
        Imgproc.cvtColor(mat, grayMat, Imgproc.COLOR_BGR2GRAY)

        val createdMask = createMaskMatFromPaths(bitmap.width, bitmap.height, refinementPaths, autoMask)
        try {
            orb.detectAndCompute(grayMat, createdMask, keypoints, descriptors)
        } finally {
            createdMask.release()
        }

        if (descriptors.rows() == 0) {
            Log.w("ImageUtils", "No descriptors generated for fingerprint")
        }

        // Bolt Optimization: Extract descriptors to ByteArray and metadata
        val rows = descriptors.rows()
        val cols = descriptors.cols()
        val type = descriptors.type()
        val totalBytes = descriptors.total().toInt() * descriptors.elemSize().toInt()
        val data = ByteArray(totalBytes)
        if (totalBytes > 0) {
            descriptors.get(0, 0, data)
        }

        return Fingerprint(
            keypoints = keypoints.toList(),
            descriptorsData = data,
            descriptorsRows = rows,
            descriptorsCols = cols,
            descriptorsType = type
        )
    } catch (e: Exception) {
        Log.e("ImageUtils", "Error generating fingerprint", e)
        throw e
    } finally {
        mat.release()
        grayMat.release()
        keypoints.release()
        descriptors.release()
    }
}
