package com.hereliesaz.graffitixr.utils

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import com.hereliesaz.graffitixr.data.RefinementPath
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfKeyPoint
import org.opencv.features2d.ORB
import org.opencv.imgproc.Imgproc

fun convertToLineDrawing(bitmap: Bitmap): Bitmap {
    val mat = Mat()
    Utils.bitmapToMat(bitmap, mat)
    Imgproc.cvtColor(mat, mat, Imgproc.COLOR_BGR2GRAY)
    Imgproc.Canny(mat, mat, 50.0, 150.0)
    val resultBitmap = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888)
    Utils.matToBitmap(mat, resultBitmap)
    return resultBitmap
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

fun detectFeaturesWithMask(bitmap: Bitmap, refinementPaths: List<RefinementPath>): List<androidx.compose.ui.geometry.Offset> {
    val mat = Mat()
    Utils.bitmapToMat(bitmap, mat)
    val grayMat = Mat()
    Imgproc.cvtColor(mat, grayMat, Imgproc.COLOR_BGR2GRAY)

    // Create Mask from Paths
    val maskBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(maskBitmap)
    canvas.drawColor(android.graphics.Color.WHITE) // Default: Allow all (White)

    val paint = Paint().apply {
        strokeWidth = 50f // Brush size - MUST MATCH UI
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    refinementPaths.forEach { rPath ->
        paint.color = if (rPath.isEraser) android.graphics.Color.WHITE else android.graphics.Color.BLACK
        val path = Path()
        if (rPath.points.isNotEmpty()) {
            path.moveTo(rPath.points.first().x * bitmap.width, rPath.points.first().y * bitmap.height)
            for (i in 1 until rPath.points.size) {
                path.lineTo(rPath.points[i].x * bitmap.width, rPath.points[i].y * bitmap.height)
            }
        }
        canvas.drawPath(path, paint)
    }

    val maskMat = Mat()
    Utils.bitmapToMat(maskBitmap, maskMat)
    Imgproc.cvtColor(maskMat, maskMat, Imgproc.COLOR_BGR2GRAY)

    val orb = ORB.create()
    val keypoints = MatOfKeyPoint()
    orb.detect(grayMat, keypoints, maskMat)

    val pointList = keypoints.toList()
    return pointList.map {
        androidx.compose.ui.geometry.Offset(it.pt.x.toFloat() / bitmap.width, it.pt.y.toFloat() / bitmap.height)
    }
}

fun createMaskMatFromPaths(width: Int, height: Int, refinementPaths: List<RefinementPath>): Mat {
    val maskBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(maskBitmap)
    canvas.drawColor(android.graphics.Color.WHITE)

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

/**
 * Applies the masking paths permanently to the bitmap.
 * Areas covered by the "mask" (non-eraser paths) are turned black.
 */
fun applyMaskToBitmap(source: Bitmap, refinementPaths: List<RefinementPath>): Bitmap {
    if (refinementPaths.isEmpty()) return source

    // Create a mutable copy to draw on
    val mutableBitmap = source.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = Canvas(mutableBitmap)

    val paint = Paint().apply {
        strokeWidth = 50f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        // Use Black to "erase" features from the perspective of the tracker
        color = android.graphics.Color.BLACK
    }

    // We only care about the "Mask" (Subtract) paths here.
    // The "Eraser" (Add) paths logically just cleared the mask buffer, but here we are drawing on the image.
    // If the refinement logic is "Paint Red to Mask", we just draw Black where Red was.
    // NOTE: This simple implementation assumes paths are additive masks.
    // If 'Eraser' allows restoring the image, we'd need to use a layer.
    // For robustness matching the OpenCV mask logic:

    // 1. Generate the B/W mask bitmap first (White = Keep, Black = Cut)
    val maskBitmap = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
    val maskCanvas = Canvas(maskBitmap)
    maskCanvas.drawColor(android.graphics.Color.WHITE) // Start with all valid

    refinementPaths.forEach { rPath ->
        paint.color = if (rPath.isEraser) android.graphics.Color.WHITE else android.graphics.Color.BLACK
        val path = Path()
        if (rPath.points.isNotEmpty()) {
            path.moveTo(rPath.points.first().x * source.width, rPath.points.first().y * source.height)
            for (i in 1 until rPath.points.size) {
                path.lineTo(rPath.points[i].x * source.width, rPath.points[i].y * source.height)
            }
        }
        maskCanvas.drawPath(path, paint)
    }

    // 2. Apply this mask to the source image using PorterDuff
    // We want: Result = Source WHERE Mask is White, else Black.

    val resultPaint = Paint()
    // Draw the mask onto the main image using Multiply (White * Source = Source, Black * Source = Black)
    // Actually, simpler: Draw the *inverse* of the mask (Black parts) onto the image?
    // Let's stick to the generated maskBitmap (White/Black).

    // Iterate pixels? Too slow.
    // Use Xfermode: DST_IN keeps source where mask is opaque? No, mask is B/W.
    // Simplest robust way without complex Xfermodes on colors:
    // Create a new bitmap, draw source, then draw a "Black Layer" masked by the inverse of our mask?
    // Actually, simply iterating pixels in C++ (OpenCV) is fast, but here...
    // Let's use OpenCV since we have it and it's robust.

    val mat = Mat()
    Utils.bitmapToMat(source, mat)

    val maskMat = Mat()
    Utils.bitmapToMat(maskBitmap, maskMat)
    Imgproc.cvtColor(maskMat, maskMat, Imgproc.COLOR_BGR2GRAY)

    val resultMat = Mat()
    // copyTo with mask: copies source to result where mask is non-zero.
    // Initialize result with Black.
    resultMat.create(mat.size(), mat.type())
    resultMat.setTo(org.opencv.core.Scalar(0.0, 0.0, 0.0, 255.0))

    mat.copyTo(resultMat, maskMat)

    val finalBitmap = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
    Utils.matToBitmap(resultMat, finalBitmap)

    return finalBitmap
}