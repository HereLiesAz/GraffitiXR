package com.hereliesaz.graffitixr.utils

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import com.hereliesaz.graffitixr.data.RefinementPath
import com.hereliesaz.graffitixr.data.Fingerprint
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfKeyPoint
import org.opencv.features2d.ORB
import org.opencv.imgproc.Imgproc

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

fun calculateBlurMetric(bitmap: Bitmap): Double {
    val mat = Mat()
    Utils.bitmapToMat(bitmap, mat)
    val grayMat = Mat()
    Imgproc.cvtColor(mat, grayMat, Imgproc.COLOR_BGR2GRAY)

    val laplacianImage = Mat()
    Imgproc.Laplacian(grayMat, laplacianImage, org.opencv.core.CvType.CV_64F)

    val mean = MatOfDouble()
    val stdDev = MatOfDouble()
    org.opencv.core.Core.meanStdDev(laplacianImage, mean, stdDev)

    val variance = stdDev.get(0, 0)[0] * stdDev.get(0, 0)[0]
    return variance
}

fun estimateFeatureRichness(bitmap: Bitmap): Int {
    val mat = Mat()
    Utils.bitmapToMat(bitmap, mat)
    val grayMat = Mat()
    Imgproc.cvtColor(mat, grayMat, Imgproc.COLOR_BGR2GRAY)

    val orb = ORB.create()
    val keypoints = MatOfKeyPoint()
    orb.detect(grayMat, keypoints)

    return keypoints.rows()
}

fun detectFeaturesWithMask(bitmap: Bitmap, refinementPaths: List<RefinementPath>, autoMask: Bitmap? = null): List<androidx.compose.ui.geometry.Offset> {
    val mat = Mat()
    Utils.bitmapToMat(bitmap, mat)
    val grayMat = Mat()
    Imgproc.cvtColor(mat, grayMat, Imgproc.COLOR_BGR2GRAY)

    val maskMat = createMaskMatFromPaths(bitmap.width, bitmap.height, refinementPaths, autoMask)

    val orb = ORB.create()
    val keypoints = MatOfKeyPoint()
    orb.detect(grayMat, keypoints, maskMat)

    val pointList = keypoints.toList()
    return pointList.map {
        androidx.compose.ui.geometry.Offset(it.pt.x.toFloat() / bitmap.width, it.pt.y.toFloat() / bitmap.height)
    }
}

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

    val maskMat = Mat()
    Utils.bitmapToMat(maskBitmap, maskMat)
    Imgproc.cvtColor(maskMat, maskMat, Imgproc.COLOR_BGR2GRAY)
    return maskMat
}

fun applyMaskToBitmap(source: Bitmap, refinementPaths: List<RefinementPath>, autoMask: Bitmap? = null): Bitmap {
    val mat = Mat()
    Utils.bitmapToMat(source, mat)

    val maskMat = createMaskMatFromPaths(source.width, source.height, refinementPaths, autoMask)

    val resultMat = Mat()
    resultMat.create(mat.size(), mat.type())
    resultMat.setTo(org.opencv.core.Scalar(0.0, 0.0, 0.0, 255.0))

    mat.copyTo(resultMat, maskMat)

    val finalBitmap = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
    Utils.matToBitmap(resultMat, finalBitmap)

    return finalBitmap
}

fun generateFingerprint(bitmap: Bitmap, refinementPaths: List<RefinementPath>, autoMask: Bitmap? = null): Fingerprint {
    val mat = Mat()
    Utils.bitmapToMat(bitmap, mat)
    val grayMat = Mat()
    Imgproc.cvtColor(mat, grayMat, Imgproc.COLOR_BGR2GRAY)

    val maskMat = createMaskMatFromPaths(bitmap.width, bitmap.height, refinementPaths, autoMask)

    val orb = ORB.create()
    val keypoints = MatOfKeyPoint()
    val descriptors = Mat()
    orb.detectAndCompute(grayMat, maskMat, keypoints, descriptors)

    mat.release()
    grayMat.release()
    maskMat.release()

    val fingerprint = Fingerprint(keypoints.toList(), descriptors)
    keypoints.release()

    return fingerprint
}
