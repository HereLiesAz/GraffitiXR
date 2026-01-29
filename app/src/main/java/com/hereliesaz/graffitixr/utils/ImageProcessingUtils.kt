package com.hereliesaz.graffitixr.utils

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.util.Log
import androidx.compose.ui.geometry.Offset
import com.hereliesaz.graffitixr.data.Fingerprint
import org.opencv.android.Utils
import org.opencv.calib3d.Calib3d
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.DMatch
import org.opencv.core.Mat
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.features2d.DescriptorMatcher
import org.opencv.features2d.ORB
import org.opencv.imgproc.Imgproc

object ImageProcessingUtils {

    private const val TAG = "ImageProcessingUtils"

    fun applyAdjustments(
        bitmap: Bitmap,
        brightness: Float,
        contrast: Float,
        saturation: Float
    ): Bitmap {
        val result = Bitmap.createBitmap(bitmap.width, bitmap.height, bitmap.config ?: Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint()

        val cm = ColorMatrix()
        cm.setSaturation(saturation)

        val contrastMatrix = floatArrayOf(
            contrast, 0f, 0f, 0f, (1f - contrast) * 128f + (brightness - 1f) * 255f,
            0f, contrast, 0f, 0f, (1f - contrast) * 128f + (brightness - 1f) * 255f,
            0f, 0f, contrast, 0f, (1f - contrast) * 128f + (brightness - 1f) * 255f,
            0f, 0f, 0f, 1f, 0f
        )

        val adjustMatrix = ColorMatrix(contrastMatrix)
        cm.postConcat(adjustMatrix)

        paint.colorFilter = ColorMatrixColorFilter(cm)
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
        return result
    }

    fun applyColorBalance(
        bitmap: Bitmap,
        red: Float,
        green: Float,
        blue: Float
    ): Bitmap {
        val result = Bitmap.createBitmap(bitmap.width, bitmap.height, bitmap.config ?: Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint()

        val cm = ColorMatrix(floatArrayOf(
            red, 0f, 0f, 0f, 0f,
            0f, green, 0f, 0f, 0f,
            0f, 0f, blue, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        ))

        paint.colorFilter = ColorMatrixColorFilter(cm)
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
        return result
    }

    fun createOutline(bitmap: Bitmap): Bitmap {
        val mat = Mat()
        Utils.bitmapToMat(bitmap, mat)
        val edges = Mat()
        Imgproc.cvtColor(mat, edges, Imgproc.COLOR_RGBA2GRAY)
        Imgproc.GaussianBlur(edges, edges, org.opencv.core.Size(5.0, 5.0), 1.5)
        Imgproc.Canny(edges, edges, 50.0, 150.0)

        val white = org.opencv.core.Scalar(255.0, 255.0, 255.0, 255.0)
        val rgba = Mat(edges.size(), CvType.CV_8UC4, org.opencv.core.Scalar(0.0, 0.0, 0.0, 0.0))
        rgba.setTo(white, edges)

        val result = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(rgba, result)

        mat.release()
        edges.release()
        rgba.release()
        return result
    }

    /**
     * Rectifies a perspective-distorted image based on 4 normalized corner points.
     */
    fun unwarpImage(bitmap: Bitmap, points: List<Offset>): Bitmap? {
        if (points.size != 4) return null

        val srcMat = Mat()
        Utils.bitmapToMat(bitmap, srcMat)

        val w = srcMat.cols().toDouble()
        val h = srcMat.rows().toDouble()

        // 1. Define Source Points (User's Quad)
        val srcPoints = MatOfPoint2f(
            Point(points[0].x * w, points[0].y * h), // TL
            Point(points[1].x * w, points[1].y * h), // TR
            Point(points[2].x * w, points[2].y * h), // BR
            Point(points[3].x * w, points[3].y * h)  // BL
        )

        // 2. Define Destination Points (Rectangular Image)
        val dstPoints = MatOfPoint2f(
            Point(0.0, 0.0),
            Point(w, 0.0),
            Point(w, h),
            Point(0.0, h)
        )

        // 3. Compute Perspective Transform
        val transform = Imgproc.getPerspectiveTransform(srcPoints, dstPoints)
        val dstMat = Mat()

        // 4. Apply Transform
        Imgproc.warpPerspective(srcMat, dstMat, transform, srcMat.size())

        val result = Bitmap.createBitmap(dstMat.cols(), dstMat.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(dstMat, result)

        srcMat.release()
        dstMat.release()
        srcPoints.release()
        dstPoints.release()
        transform.release()

        return result
    }

    /**
     * Attempts to find the saved fingerprint in the current camera frame.
     * Returns a Homography Matrix (3x3) if a match is found with sufficient inliers, null otherwise.
     */
    fun matchFingerprint(sceneBitmap: Bitmap, fingerprint: Fingerprint): Mat? {
        // 1. Reconstruct Saved Descriptors from ByteArray
        val savedDescriptors = Mat(fingerprint.descriptorsRows, fingerprint.descriptorsCols, fingerprint.descriptorsType)
        savedDescriptors.put(0, 0, fingerprint.descriptorsData)

        // 2. Extract Features from Live Scene
        val sceneMat = Mat()
        Utils.bitmapToMat(sceneBitmap, sceneMat)
        val sceneGray = Mat()
        Imgproc.cvtColor(sceneMat, sceneGray, Imgproc.COLOR_RGB2GRAY)

        val orb = ORB.create()
        val sceneKeypoints = MatOfKeyPoint()
        val sceneDescriptors = Mat()
        orb.detectAndCompute(sceneGray, Mat(), sceneKeypoints, sceneDescriptors)

        if (sceneDescriptors.empty() || savedDescriptors.empty()) {
            sceneMat.release(); sceneGray.release(); sceneKeypoints.release(); sceneDescriptors.release(); savedDescriptors.release()
            return null
        }

        // 3. Match Features (Hamming for ORB)
        val matcher = DescriptorMatcher.create(DescriptorMatcher.BRUTEFORCE_HAMMING)
        val matches = MatOfDMatch()
        matcher.match(savedDescriptors, sceneDescriptors, matches)

        // 4. Filter Matches (Distance Check)
        val matchList = matches.toList()
        if (matchList.isEmpty()) {
            cleanup(sceneMat, sceneGray, sceneKeypoints, sceneDescriptors, savedDescriptors, matches)
            return null
        }

        // Determine quality threshold (e.g., 2x min distance)
        var minDist = 100.0f
        var maxDist = 0.0f
        for (match in matchList) {
            val dist = match.distance
            if (dist < minDist) minDist = dist
            if (dist > maxDist) maxDist = dist
        }

        val goodMatches = ArrayList<DMatch>()
        // Threshold: strictly keep very good matches
        val threshold = (3.0f * minDist).coerceAtLeast(30.0f)

        for (match in matchList) {
            if (match.distance <= threshold) {
                goodMatches.add(match)
            }
        }

        // Requirement: At least 20 good inliers to consider it a lock (per Architecture.md)
        if (goodMatches.size < 20) {
            cleanup(sceneMat, sceneGray, sceneKeypoints, sceneDescriptors, savedDescriptors, matches)
            return null
        }

        // 5. Compute Homography
        val objPts = ArrayList<Point>()
        val scenePts = ArrayList<Point>()

        val savedKeypointsList = fingerprint.keypoints
        val sceneKeypointsList = sceneKeypoints.toList()

        for (match in goodMatches) {
            objPts.add(savedKeypointsList[match.queryIdx].pt)
            scenePts.add(sceneKeypointsList[match.trainIdx].pt)
        }

        val objMat = MatOfPoint2f(*objPts.toTypedArray())
        val sceneMat2f = MatOfPoint2f(*scenePts.toTypedArray())

        // RANSAC to filter geometric outliers
        val homography = Calib3d.findHomography(objMat, sceneMat2f, Calib3d.RANSAC, 5.0)

        cleanup(sceneMat, sceneGray, sceneKeypoints, sceneDescriptors, savedDescriptors, matches, objMat, sceneMat2f)

        return if (!homography.empty()) homography else null
    }

    private fun cleanup(vararg mats: Mat?) {
        for (mat in mats) {
            mat?.release()
        }
    }
}

// Top-level functions
fun detectFeaturesWithMask(bitmap: Bitmap): List<org.opencv.core.KeyPoint> {
    val mat = Mat()
    val gray = Mat()
    val orb = ORB.create()
    val keypoints = MatOfKeyPoint()
    return try {
        Utils.bitmapToMat(bitmap, mat)
        Imgproc.cvtColor(mat, gray, Imgproc.COLOR_RGB2GRAY)
        orb.detect(gray, keypoints)
        keypoints.toList()
    } finally {
        mat.release()
        gray.release()
        keypoints.release()
        orb.clear()
    }
}

fun generateFingerprint(bitmap: Bitmap): Fingerprint {
    val mat = Mat()
    val gray = Mat()
    val orb = ORB.create()
    val keypoints = MatOfKeyPoint()
    val descriptors = Mat()
    val emptyMask = Mat()
    return try {
        Utils.bitmapToMat(bitmap, mat)
        Imgproc.cvtColor(mat, gray, Imgproc.COLOR_RGB2GRAY)
        orb.detectAndCompute(gray, emptyMask, keypoints, descriptors)

        val data = ByteArray(descriptors.rows() * descriptors.cols() * descriptors.elemSize().toInt())
        if (data.isNotEmpty()) {
            descriptors.get(0, 0, data)
        }

        Fingerprint(keypoints.toList(), data, descriptors.rows(), descriptors.cols(), descriptors.type())
    } finally {
        mat.release()
        gray.release()
        keypoints.release()
        descriptors.release()
        emptyMask.release()
        orb.clear()
    }
}

fun enhanceImageForAr(bitmap: Bitmap): Bitmap {
    val mat = Mat()
    val lab = Mat()
    val channels = ArrayList<Mat>()
    return try {
        Utils.bitmapToMat(bitmap, mat)
        Imgproc.cvtColor(mat, lab, Imgproc.COLOR_RGB2Lab)
        Core.split(lab, channels)
        val clahe = Imgproc.createCLAHE()
        clahe.clipLimit = 4.0
        if (channels.isNotEmpty()) {
            clahe.apply(channels[0], channels[0])
        }
        Core.merge(channels, lab)
        Imgproc.cvtColor(lab, mat, Imgproc.COLOR_Lab2RGB)
        val result = Bitmap.createBitmap(bitmap.width, bitmap.height, bitmap.config ?: Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(mat, result)
        result
    } finally {
        mat.release()
        lab.release()
        channels.forEach { it.release() }
    }
}