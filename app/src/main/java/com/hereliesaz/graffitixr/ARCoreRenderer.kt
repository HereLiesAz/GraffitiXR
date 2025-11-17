package com.hereliesaz.graffitixr

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import com.google.ar.core.AugmentedImage
import com.google.ar.core.Frame
import com.google.ar.core.TrackingState
import com.google.ar.core.Plane
import com.hereliesaz.graffitixr.rendering.AugmentedImageRenderer
import android.util.Log
import com.hereliesaz.graffitixr.rendering.BackgroundRenderer
import com.hereliesaz.graffitixr.rendering.PlaneRenderer
import com.hereliesaz.graffitixr.rendering.PointCloudRenderer
import org.opencv.core.Mat
import org.opencv.android.Utils
import org.opencv.imgproc.Imgproc
import org.opencv.features2d.ORB
import org.opencv.features2d.DescriptorMatcher
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.MatOfDMatch
import org.opencv.calib3d.Calib3d
import org.opencv.core.MatOfPoint2f
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class ARCoreRenderer(private val arCoreManager: ARCoreManager) : GLSurfaceView.Renderer {

    private val augmentedImageRenderer = AugmentedImageRenderer()
    private val planeRenderer = PlaneRenderer()
    private val trackedImages = mutableMapOf<Int, Pair<AugmentedImage, AugmentedImageRenderer>>()
    private val orb by lazy { ORB.create() }
    private val matcher by lazy { DescriptorMatcher.create(DescriptorMatcher.BRUTEFORCE_HAMMING) }
    private var fingerprintKeypoints: MatOfKeyPoint? = null
    private var fingerprintDescriptors: Mat? = null
    private var surfaceWidth: Int = 0
    private var surfaceHeight: Int = 0

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        Log.d(TAG, "onSurfaceCreated")
        GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f)
        arCoreManager.backgroundRenderer.createOnGlThread()
        augmentedImageRenderer.createOnGlThread()
        arCoreManager.pointCloudRenderer.createOnGlThread()
        planeRenderer.createOnGlThread()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        Log.d(TAG, "onSurfaceChanged: width=$width, height=$height")
        GLES20.glViewport(0, 0, width, height)
        arCoreManager.displayRotationHelper.onSurfaceChanged(width, height)
        surfaceWidth = width
        surfaceHeight = height
    }

    override fun onDrawFrame(gl: GL10?) {
        Log.d(TAG, "onDrawFrame")
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        val frame: Frame = arCoreManager.onDrawFrame(surfaceWidth, surfaceHeight) ?: return
        arCoreManager.backgroundRenderer.draw(frame)

        val projectionMatrix = FloatArray(16)
        val viewMatrix = FloatArray(16)
        frame.camera.getProjectionMatrix(projectionMatrix, 0, 0.1f, 100.0f)
        frame.camera.getViewMatrix(viewMatrix, 0)

        frame.acquirePointCloud().use { pointCloud ->
            arCoreManager.pointCloudRenderer.draw(pointCloud, viewMatrix, projectionMatrix)
        }

        for (plane in frame.getUpdatedTrackables(Plane::class.java)) {
            planeRenderer.draw(plane, viewMatrix, projectionMatrix)
        }

//        try {
//            frame.acquireCameraImage().use { image ->
//                val yuvBytes = ByteArray(image.planes[0].buffer.remaining())
//                image.planes[0].buffer.get(yuvBytes)
//                val mat = Mat(image.height + image.height / 2, image.width, org.opencv.core.CvType.CV_8UC1)
//                mat.put(0, 0, yuvBytes)
//                val grayMat = Mat()
//                Imgproc.cvtColor(mat, grayMat, Imgproc.COLOR_YUV2GRAY_NV21)
//
//                val keypoints = MatOfKeyPoint()
//                val descriptors = Mat()
//                orb.detectAndCompute(grayMat, Mat(), keypoints, descriptors)
//
//                if (fingerprintKeypoints == null) {
//                    fingerprintKeypoints = keypoints
//                    fingerprintDescriptors = descriptors
//                } else {
//                    val matches = MatOfDMatch()
//                    matcher.match(descriptors, fingerprintDescriptors, matches)
//
//                    val goodMatches = matches.toList().filter { it.distance < 70 }
//                    val goodMatchesMat = MatOfDMatch()
//                    goodMatchesMat.fromList(goodMatches)
//
//                    if (goodMatches.size > 10) {
//                        val fingerprintPts = MatOfPoint2f()
//                        val currentPts = MatOfPoint2f()
//
//                        val fingerprintKeypointsList = fingerprintKeypoints!!.toList()
//                        val currentKeypointsList = keypoints.toList()
//
//                        fingerprintPts.fromList(goodMatches.map { fingerprintKeypointsList[it.trainIdx].pt })
//                        currentPts.fromList(goodMatches.map { currentKeypointsList[it.queryIdx].pt })
//
//                        val homography = Calibd.findHomography(fingerprintPts, currentPts, Calibd.RANSAC, 5.0)
//                        val area = Imgproc.contourArea(homography)
//                        Log.d("ARCoreRenderer", "Homography area: $area")
//                    }
//                }
//
//                mat.release()
//                grayMat.release()
//            }
//        } catch (e: Exception) {
//            // Handle exceptions
//        }

        val updatedAugmentedImages = frame.getUpdatedTrackables(AugmentedImage::class.java)

        for (augmentedImage in updatedAugmentedImages) {
            if (augmentedImage.trackingState == TrackingState.TRACKING) {
                if (!trackedImages.containsKey(augmentedImage.index)) {
                    val renderer = AugmentedImageRenderer()
                    renderer.createOnGlThread()
                    trackedImages[augmentedImage.index] = Pair(augmentedImage, renderer)
                }
                val (image, renderer) = trackedImages[augmentedImage.index]!!
                val projectionMatrix = FloatArray(16)
                val viewMatrix = FloatArray(16)
                frame.camera.getProjectionMatrix(projectionMatrix, 0, 0.1f, 100.0f)
                frame.camera.getViewMatrix(viewMatrix, 0)
                renderer.draw(viewMatrix, projectionMatrix, image.centerPose, image.extentX, image.extentZ)
            }
        }
    }

    companion object {
        private const val TAG = "ARCoreRenderer"
    }
}
