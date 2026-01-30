package com.hereliesaz.graffitixr.utils

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import androidx.compose.ui.geometry.Offset
import com.hereliesaz.graffitixr.data.Fingerprint
import org.opencv.android.Utils
import org.opencv.calib3d.Calib3d
import org.opencv.core.*
import org.opencv.features2d.DescriptorMatcher
import org.opencv.features2d.ORB
import org.opencv.imgproc.Imgproc
import java.nio.ByteBuffer

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

    // FIX: Added missing method
    fun generateFingerprint(bitmap: Bitmap): Fingerprint? {
        try {
            val mat = Mat()
            Utils.bitmapToMat(bitmap, mat)
            val gray = Mat()
            Imgproc.cvtColor(mat, gray, Imgproc.COLOR_RGB2GRAY)

            val orb = ORB.create(1000)
            val keypoints = MatOfKeyPoint()
            val descriptors = Mat()

            orb.detectAndCompute(gray, Mat(), keypoints, descriptors)

            if (descriptors.empty()) return null

            val descriptorData = ByteArray(descriptors.total().toInt() * descriptors.elemSize().toInt())
            descriptors.get(0, 0, descriptorData)

            return Fingerprint(
                keypoints = keypoints.toList(),
                descriptorsData = descriptorData,
                descriptorsRows = descriptors.rows(),
                descriptorsCols = descriptors.cols(),
                descriptorsType = descriptors.type()
            )
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    fun generateFingerprintWithDepth(
        bitmap: Bitmap,
        depthBuffer: ByteBuffer,
        depthWidth: Int,
        depthHeight: Int,
        cameraIntrinsics: FloatArray // fx, fy, cx, cy
    ): Fingerprint? {
        try {
            val mat = Mat()
            Utils.bitmapToMat(bitmap, mat)
            val gray = Mat()
            Imgproc.cvtColor(mat, gray, Imgproc.COLOR_RGB2GRAY)

            val orb = ORB.create(1000)
            val keypoints = MatOfKeyPoint()
            val descriptors = Mat()

            orb.detectAndCompute(gray, Mat(), keypoints, descriptors)

            if (descriptors.empty()) return null

            val fx = cameraIntrinsics[0]
            val fy = cameraIntrinsics[1]
            val cx = cameraIntrinsics[2]
            val cy = cameraIntrinsics[3]

            val points3d = ArrayList<Float>()
            val keypointList = keypoints.toList()

            // Depth Access helper
            fun getDepth(x: Int, y: Int): Float {
                if (x < 0 || x >= depthWidth || y < 0 || y >= depthHeight) return 0f
                // depthBuffer is 16-bit
                val index = (y * depthWidth + x) * 2
                if (index + 1 >= depthBuffer.limit()) return 0f

                // Read 2 bytes
                val low = depthBuffer.get(index).toInt() and 0xFF
                val high = depthBuffer.get(index + 1).toInt() and 0xFF
                val depthMm = (high shl 8) or low
                return depthMm * 0.001f // To meters
            }

            for (kp in keypointList) {
                // Scale coordinates if bitmap size differs from depth image size
                val scaleX = depthWidth.toFloat() / bitmap.width
                val scaleY = depthHeight.toFloat() / bitmap.height
                val dX = (kp.pt.x * scaleX).toInt()
                val dY = (kp.pt.y * scaleY).toInt()

                val z = getDepth(dX, dY)
                if (z > 0.1f && z < 5.0f) {
                    val x = (kp.pt.x.toFloat() - cx) * z / fx
                    val y = (kp.pt.y.toFloat() - cy) * z / fy
                    points3d.add(x)
                    points3d.add(y)
                    points3d.add(z)
                } else {
                    points3d.add(0f)
                    points3d.add(0f)
                    points3d.add(0f)
                }
            }

            val descriptorData = ByteArray(descriptors.total().toInt() * descriptors.elemSize().toInt())
            descriptors.get(0, 0, descriptorData)

            return Fingerprint(
                keypoints = keypointList,
                points3d = points3d,
                descriptorsData = descriptorData,
                descriptorsRows = descriptors.rows(),
                descriptorsCols = descriptors.cols(),
                descriptorsType = descriptors.type()
            )
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    fun matchFingerprint(
        bitmap: Bitmap,
        savedFingerprint: Fingerprint
    ): Mat? {
        try {
            val mat = Mat()
            Utils.bitmapToMat(bitmap, mat)
            val gray = Mat()
            Imgproc.cvtColor(mat, gray, Imgproc.COLOR_RGB2GRAY)

            val orb = ORB.create(1000)
            val keypoints = MatOfKeyPoint()
            val descriptors = Mat()

            orb.detectAndCompute(gray, Mat(), keypoints, descriptors)
            if (descriptors.empty()) return null

            // Reconstruct saved descriptor mat
            val savedDescriptors = Mat(
                savedFingerprint.descriptorsRows,
                savedFingerprint.descriptorsCols,
                savedFingerprint.descriptorsType
            )
            savedDescriptors.put(0, 0, savedFingerprint.descriptorsData)

            val matcher = DescriptorMatcher.create(DescriptorMatcher.BRUTEFORCE_HAMMING)
            val matches = MatOfDMatch()
            matcher.match(descriptors, savedDescriptors, matches)

            val matchesList = matches.toList()
            val goodMatches = matchesList.filter { it.distance < 50 } // Strict threshold

            if (goodMatches.size < 10) return null

            val srcPoints = ArrayList<Point>()
            val dstPoints = ArrayList<Point>()

            val savedKeypoints = savedFingerprint.keypoints
            val currentKeypoints = keypoints.toList()

            for (match in goodMatches) {
                srcPoints.add(savedKeypoints[match.trainIdx].pt)
                dstPoints.add(currentKeypoints[match.queryIdx].pt)
            }

            val srcMat = MatOfPoint2f(*srcPoints.toTypedArray())
            val dstMat = MatOfPoint2f(*dstPoints.toTypedArray())

            return Calib3d.findHomography(srcMat, dstMat, Calib3d.RANSAC, 5.0)
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    fun unwarpImage(bitmap: Bitmap, points: List<Offset>): Bitmap? {
        if (points.size != 4) return null
        return try {
            val srcMat = MatOfPoint2f(
                Point(points[0].x.toDouble(), points[0].y.toDouble()),
                Point(points[1].x.toDouble(), points[1].y.toDouble()),
                Point(points[2].x.toDouble(), points[2].y.toDouble()),
                Point(points[3].x.toDouble(), points[3].y.toDouble())
            )

            val width = bitmap.width.toDouble()
            val height = bitmap.height.toDouble()

            // Destination: Rectangular full image
            val dstMat = MatOfPoint2f(
                Point(0.0, 0.0),
                Point(width, 0.0),
                Point(width, height),
                Point(0.0, height)
            )

            val transform = Imgproc.getPerspectiveTransform(srcMat, dstMat)
            val srcImage = Mat()
            Utils.bitmapToMat(bitmap, srcImage)
            val dstImage = Mat()

            Imgproc.warpPerspective(srcImage, dstImage, transform, Size(width, height))

            val result = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(dstImage, result)
            result
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}