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

            val scaleX = depthWidth.toFloat() / bitmap.width
            val scaleY = depthHeight.toFloat() / bitmap.height

            depthBuffer.rewind()

            for (kp in kpList) {
                val u = kp.pt.x
                val v = kp.pt.y

                val dU = (u * scaleX).toInt().coerceIn(0, depthWidth - 1)
                val dV = (v * scaleY).toInt().coerceIn(0, depthHeight - 1)

                val index = (dV * depthWidth + dU) * 2
                if (index + 1 < depthBuffer.limit()) {
                    val dLow = depthBuffer.get(index).toInt() and 0xFF
                    val dHigh = depthBuffer.get(index + 1).toInt() and 0xFF
                    val depthMm = (dHigh shl 8) or dLow

                    if (depthMm > 0 && depthMm < 5000) {
                        val z = depthMm * 0.001f
                        val x = (u - cx) * z / fx
                        val y = (v - cy) * z / fy

                        points3d.add(x.toFloat())
                        points3d.add(y.toFloat())
                        points3d.add(z)
                    } else {
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
     * Solves PnP and converts the result to OpenGL Coordinate System.
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

        val goodMatches = matches.toList().filter { it.distance < 50.0f }
        if (goodMatches.size < 10) {
            sceneMat.release(); sceneGray.release(); sceneKeypoints.release(); sceneDescriptors.release(); savedDescriptors.release(); matches.release()
            return null
        }

        val objectPointsList = ArrayList<Point3>()
        val imagePointsList = ArrayList<Point>()
        val sceneKeypointsList = sceneKeypoints.toList()

        for (match in goodMatches) {
            val qIdx = match.queryIdx
            if (qIdx * 3 + 2 < fingerprint.points3d.size) {
                val x = fingerprint.points3d[qIdx * 3]
                val y = fingerprint.points3d[qIdx * 3 + 1]
                val z = fingerprint.points3d[qIdx * 3 + 2]

                if (z > 0.1f) {
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
        cameraMatrix.put(0, 0, fx, 0.0, cx)
        cameraMatrix.put(1, 0, 0.0, fy, cy)
        cameraMatrix.put(2, 0, 0.0, 0.0, 1.0)

        val distCoeffs = MatOfDouble(0.0, 0.0, 0.0, 0.0)

        val rvec = Mat()
        val tvec = Mat()

        val success = Calib3d.solvePnPRansac(objectPoints, imagePoints, cameraMatrix, distCoeffs, rvec, tvec)

        var resultTransform: Mat? = null

        if (success) {
            val R = Mat()
            Calib3d.Rodrigues(rvec, R)

            // Convert CV (Right-Down-Forward) to GL (Right-Up-Back)
            // Matrix M_cv_to_gl = diag(1, -1, -1)
            val cvToGl = Mat.eye(3, 3, CvType.CV_64F)
            cvToGl.put(1, 1, -1.0)
            cvToGl.put(2, 2, -1.0)

            val R_gl = Mat()
            Core.gemm(cvToGl, R, 1.0, Mat(), 0.0, R_gl)
            // Also need to post-multiply? No, usually applying to camera pose.
            // Simplified: T_gl = [R_gl | t_gl]
            // where t_gl = cvToGl * t_cv

            val t_gl = Mat()
            Core.gemm(cvToGl, tvec, 1.0, Mat(), 0.0, t_gl)

            resultTransform = Mat.eye(4, 4, CvType.CV_64F)

            for(i in 0..2) {
                for(j in 0..2) {
                    resultTransform.put(i, j, R_gl.get(i, j))
                }
                resultTransform.put(i, 3, t_gl.get(i, 0))
            }

            R.release()
            R_gl.release()
            t_gl.release()
            cvToGl.release()
        }

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

    fun matchFingerprint(sceneBitmap: Bitmap, fingerprint: Fingerprint): Mat? {
        // [Same as previous logic for legacy homography]
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