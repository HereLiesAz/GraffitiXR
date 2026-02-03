package com.hereliesaz.graffitixr.common.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import androidx.compose.ui.geometry.Offset
import com.hereliesaz.graffitixr.common.model.Fingerprint
import org.opencv.android.Utils
import org.opencv.calib3d.Calib3d
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.DMatch
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.MatOfPoint3f
import org.opencv.core.Point
import org.opencv.core.Point3
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

    fun unwarpImage(bitmap: Bitmap, points: List<Offset>): Bitmap? {
        if (points.size != 4) return null

        val srcMat = Mat()
        Utils.bitmapToMat(bitmap, srcMat)

        val w = srcMat.cols().toDouble()
        val h = srcMat.rows().toDouble()

        val srcPoints = MatOfPoint2f(
            Point(points[0].x * w, points[0].y * h), // TL
            Point(points[1].x * w, points[1].y * h), // TR
            Point(points[2].x * w, points[2].y * h), // BR
            Point(points[3].x * w, points[3].y * h)  // BL
        )

        val dstPoints = MatOfPoint2f(
            Point(0.0, 0.0),
            Point(w, 0.0),
            Point(w, h),
            Point(0.0, h)
        )

        val transform = Imgproc.getPerspectiveTransform(srcPoints, dstPoints)
        val dstMat = Mat()

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

    fun generateFingerprint(bitmap: Bitmap): Fingerprint {
        return generateFingerprintWithDepth(bitmap, ByteBuffer.allocate(0), 0, 0, floatArrayOf(0f, 0f, 0f, 0f))
    }

    /**
     * Generates a Fingerprint with 3D depth information.
     *
     * @param bitmap The RGB image.
     * @param depthBuffer Raw 16-bit depth buffer (from ARCore Image).
     * @param depthWidth Width of depth image.
     * @param depthHeight Height of depth image.
     * @param intrinsics Camera intrinsics [fx, fy, cx, cy].
     */
    fun generateFingerprintWithDepth(
        bitmap: Bitmap,
        depthBuffer: ByteBuffer,
        depthWidth: Int,
        depthHeight: Int,
        intrinsics: FloatArray
    ): Fingerprint {
        val mat = Mat()
        val gray = Mat()
        val orb = ORB.create()
        val keypoints = MatOfKeyPoint()
        val descriptors = Mat()
        val emptyMask = Mat()

        try {
            Utils.bitmapToMat(bitmap, mat)
            Imgproc.cvtColor(mat, gray, Imgproc.COLOR_RGB2GRAY)
            orb.detectAndCompute(gray, emptyMask, keypoints, descriptors)

            val kpList = keypoints.toList()
            val points3d = ArrayList<Float>()

            val fx = intrinsics[0]
            val fy = intrinsics[1]
            val cx = intrinsics[2]
            val cy = intrinsics[3]

            // Scaling factors if depth image size differs from RGB image
            val scaleX = if (bitmap.width > 0) depthWidth.toFloat() / bitmap.width else 0f
            val scaleY = if (bitmap.height > 0) depthHeight.toFloat() / bitmap.height else 0f

            // Rewind buffer for reading
            depthBuffer.rewind()

            for (kp in kpList) {
                val u = kp.pt.x
                val v = kp.pt.y

                val dU = (u * scaleX).toInt().coerceIn(0, depthWidth - 1)
                val dV = (v * scaleY).toInt().coerceIn(0, depthHeight - 1)

                // 2 bytes per pixel. Row stride assumption: width * 2 (usually true for dense depth)
                val index = (dV * depthWidth + dU) * 2
                // Buffer bounds check
                if (depthWidth > 0 && index + 1 < depthBuffer.limit()) {
                    val dLow = depthBuffer.get(index).toInt() and 0xFF
                    val dHigh = depthBuffer.get(index + 1).toInt() and 0xFF
                    val depthMm = (dHigh shl 8) or dLow

                    if (depthMm > 0 && depthMm < 5000) { // Valid depth range
                        val z = depthMm * 0.001f // mm to meters
                        val x = (u.toFloat() - cx) * z / fx
                        val y = (v.toFloat() - cy) * z / fy

                        points3d.add(x)
                        points3d.add(y)
                        points3d.add(z)
                    } else {
                        // Invalid depth, add dummy to keep index alignment
                        points3d.add(0f); points3d.add(0f); points3d.add(0f)
                    }
                } else {
                    points3d.add(0f); points3d.add(0f); points3d.add(0f)
                }
            }

            val data = ByteArray(descriptors.rows() * descriptors.cols() * descriptors.elemSize().toInt())
            if (data.isNotEmpty()) {
                descriptors.get(0, 0, data)
            }

            return Fingerprint(kpList, points3d, data, descriptors.rows(), descriptors.cols(), descriptors.type())

        } finally {
            mat.release()
            gray.release()
            keypoints.release()
            descriptors.release()
            emptyMask.release()
            orb.clear()
        }
    }

    /**
     * Solves PnP to find the camera pose relative to the saved map.
     */
    fun solvePnP(
        sceneBitmap: Bitmap,
        fingerprint: Fingerprint,
        intrinsics: FloatArray
    ): Mat? {
        val savedDescriptors = Mat(fingerprint.descriptorsRows, fingerprint.descriptorsCols, fingerprint.descriptorsType)
        savedDescriptors.put(0, 0, fingerprint.descriptorsData)

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

        val matcher = DescriptorMatcher.create(DescriptorMatcher.BRUTEFORCE_HAMMING)
        val matches = MatOfDMatch()
        matcher.match(savedDescriptors, sceneDescriptors, matches)

        val goodMatches = matches.toList().filter { it.distance < 50.0f } // Strict threshold
        if (goodMatches.size < 10) {
            sceneMat.release(); sceneGray.release(); sceneKeypoints.release(); sceneDescriptors.release(); savedDescriptors.release(); matches.release()
            return null
        }

        // Prepare points for PnP
        val objectPointsList = ArrayList<Point3>()
        val imagePointsList = ArrayList<Point>()

        val sceneKeypointsList = sceneKeypoints.toList()

        for (match in goodMatches) {
            val qIdx = match.queryIdx // Index in saved fingerprint

            // Check if we have valid depth for this point
            // points3d is a flat list [x, y, z, x, y, z...]
            if (qIdx * 3 + 2 < fingerprint.points3d.size) {
                val x = fingerprint.points3d[qIdx * 3]
                val y = fingerprint.points3d[qIdx * 3 + 1]
                val z = fingerprint.points3d[qIdx * 3 + 2]

                if (z > 0.1f) { // Valid depth
                    objectPointsList.add(Point3(x.toDouble(), y.toDouble(), z.toDouble()))

                    val tIdx = match.trainIdx
                    imagePointsList.add(sceneKeypointsList[tIdx].pt)
                }
            }
        }

        if (objectPointsList.size < 6) {
            sceneMat.release(); sceneGray.release(); sceneKeypoints.release(); sceneDescriptors.release(); savedDescriptors.release(); matches.release()
            return null
        }

        val objectPoints = MatOfPoint3f(*objectPointsList.toTypedArray())
        val imagePoints = MatOfPoint2f(*imagePointsList.toTypedArray())

        val fx = intrinsics[0].toDouble()
        val fy = intrinsics[1].toDouble()
        val cx = intrinsics[2].toDouble()
        val cy = intrinsics[3].toDouble()

        val cameraMatrix = Mat(3, 3, CvType.CV_64F)
        cameraMatrix.put(0, 0, fx)
        cameraMatrix.put(0, 1, 0.0)
        cameraMatrix.put(0, 2, cx)
        cameraMatrix.put(1, 0, 0.0)
        cameraMatrix.put(1, 1, fy)
        cameraMatrix.put(1, 2, cy)
        cameraMatrix.put(2, 0, 0.0)
        cameraMatrix.put(2, 1, 0.0)
        cameraMatrix.put(2, 2, 1.0)

        val distCoeffs = MatOfDouble(0.0, 0.0, 0.0, 0.0) // Assume 0 distortion for ARCore frames

        val rvec = Mat()
        val tvec = Mat()

        val success = Calib3d.solvePnPRansac(objectPoints, imagePoints, cameraMatrix, distCoeffs, rvec, tvec)

        var resultTransform: Mat? = null

        if (success) {
            // Convert rvec/tvec to 4x4 transform
            val R = Mat()
            Calib3d.Rodrigues(rvec, R)

            resultTransform = Mat.eye(4, 4, CvType.CV_64F)

            // Copy rotation
            for(i in 0..2) {
                for(j in 0..2) {
                    val rotData = R.get(i, j)
                    if (rotData != null) {
                        resultTransform.put(i, j, rotData[0])
                    }
                }
            }

            // Copy translation
            for(i in 0..2) {
                val transData = tvec.get(i, 0)
                if (transData != null) {
                    resultTransform.put(i, 3, transData[0])
                }
            }

            R.release()
        }

        // Cleanup
        sceneMat.release()
        sceneGray.release()
        sceneKeypoints.release()
        sceneDescriptors.release()
        savedDescriptors.release()
        matches.release()
        objectPoints.release()
        imagePoints.release()
        cameraMatrix.release()
        distCoeffs.release()
        rvec.release()
        tvec.release()

        return resultTransform
    }

    // Homography Logic (Legacy fallback)
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

        var minDist = 100.0f
        var maxDist = 0.0f
        for (match in matchList) {
            val dist = match.distance
            if (dist < minDist) minDist = dist
            if (dist > maxDist) maxDist = dist
        }

        val goodMatches = ArrayList<DMatch>()
        val threshold = (3.0f * minDist).coerceAtLeast(30.0f)

        for (match in matchList) {
            if (match.distance <= threshold) {
                goodMatches.add(match)
            }
        }

        if (goodMatches.size < 20) {
            cleanup(sceneMat, sceneGray, sceneKeypoints, sceneDescriptors, savedDescriptors, matches)
            return null
        }

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
