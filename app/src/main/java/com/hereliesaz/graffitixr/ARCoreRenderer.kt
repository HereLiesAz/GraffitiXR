package com.hereliesaz.graffitixr

import android.graphics.Bitmap
import android.media.Image
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.NotYetAvailableException
import com.hereliesaz.graffitixr.rendering.HomographyHelper
import com.hereliesaz.graffitixr.rendering.PlaneRenderer
import com.hereliesaz.graffitixr.rendering.ProjectedImageRenderer
import com.hereliesaz.graffitixr.rendering.SimpleQuadRenderer
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfKeyPoint
import org.opencv.features2d.DescriptorMatcher
import org.opencv.features2d.ORB
import java.util.concurrent.ConcurrentLinkedQueue
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class ARCoreRenderer(private val arCoreManager: ARCoreManager) : GLSurfaceView.Renderer {

    // Renderers
    private val simpleQuadRenderer = SimpleQuadRenderer()
    private val projectedImageRenderer = ProjectedImageRenderer()
    private val planeRenderer = PlaneRenderer()

    // OpenCV
    private val orb by lazy { ORB.create() }
    private val matcher by lazy { DescriptorMatcher.create(DescriptorMatcher.BRUTEFORCE_HAMMING) }

    // State
    @Volatile var arState: ArState = ArState.SEARCHING
    @Volatile var overlayBitmap: Bitmap? = null
    @Volatile var opacity: Float = 1.0f
    @Volatile var colorBalanceR: Float = 1.0f
    @Volatile var colorBalanceG: Float = 1.0f
    @Volatile var colorBalanceB: Float = 1.0f

    // Transformation
    @Volatile var arImagePose: FloatArray? = null
    @Volatile var arObjectScale: Float = 1.0f
    @Volatile var arObjectRotation: Float = 0.0f

    // Feature Pattern (Locked State)
    data class ArFeaturePattern(val descriptors: Mat, val worldPoints: List<FloatArray>)
    var arFeaturePattern: ArFeaturePattern? = null
    private var featurePatternGenerated = false

    // Tap & Gesture Handling
    private val tapQueue = ConcurrentLinkedQueue<Pair<Float, Float>>()
    private var dragPoint: Pair<Float, Float>? = null // Last drag position

    var onPlanesDetected: ((Boolean) -> Unit)? = null
    var onImagePlaced: (() -> Unit)? = null

    private var surfaceWidth: Int = 0
    private var surfaceHeight: Int = 0

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        Log.d(TAG, "onSurfaceCreated")
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 0.0f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        arCoreManager.onSurfaceCreated()
        arCoreManager.backgroundRenderer.createOnGlThread()
        arCoreManager.pointCloudRenderer.createOnGlThread()
        planeRenderer.createOnGlThread()
        simpleQuadRenderer.createOnGlThread()
        projectedImageRenderer.createOnGlThread()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        Log.d(TAG, "onSurfaceChanged: width=$width, height=$height")
        GLES20.glViewport(0, 0, width, height)
        arCoreManager.displayRotationHelper.onSurfaceChanged(width, height)
        surfaceWidth = width
        surfaceHeight = height
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        val frame: Frame = arCoreManager.onDrawFrame(surfaceWidth, surfaceHeight) ?: return

        if (frame.timestamp == 0L) return

        arCoreManager.backgroundRenderer.draw(frame)

        val projectionMatrix = FloatArray(16)
        val viewMatrix = FloatArray(16)
        frame.camera.getProjectionMatrix(projectionMatrix, 0, 0.1f, 100.0f)
        frame.camera.getViewMatrix(viewMatrix, 0)

        // Point Cloud
        try {
            frame.acquirePointCloud().use { pointCloud ->
                arCoreManager.pointCloudRenderer.draw(pointCloud, viewMatrix, projectionMatrix)
            }
        } catch (e: NotYetAvailableException) {
            // Point cloud not available
        }

        when (arState) {
            ArState.SEARCHING -> {
                handleTapForPlacement(frame)
                featurePatternGenerated = false

                val planes = arCoreManager.session?.getAllTrackables(Plane::class.java) ?: emptyList()
                val hasPlanes = planes.any { it.trackingState == TrackingState.TRACKING }
                onPlanesDetected?.invoke(hasPlanes)

                for (plane in planes) {
                    if (plane.trackingState == TrackingState.TRACKING && plane.subsumedBy == null) {
                        planeRenderer.draw(plane, viewMatrix, projectionMatrix)
                    }
                }
            }
            ArState.PLACED -> {
                onPlanesDetected?.invoke(false)
                handleDrag(frame)
                drawPlacedImage(viewMatrix, projectionMatrix)
            }
            ArState.LOCKED -> {
                onPlanesDetected?.invoke(false)
                if (!featurePatternGenerated) {
                    generateFeaturePattern(frame)
                }
                drawLockedImage(frame)
            }
        }
    }

    fun onTap(x: Float, y: Float) {
        if (arState == ArState.SEARCHING) {
            tapQueue.add(Pair(x, y))
        }
    }

    fun onTransform(centroidX: Float, centroidY: Float, panX: Float, panY: Float) {
        if (arState == ArState.PLACED) {
            // Scale and Rotation are handled by ViewModel/UI State
            if (panX != 0f || panY != 0f) {
                dragPoint = Pair(centroidX, centroidY)
            }
        }
    }

    private fun handleTapForPlacement(frame: Frame) {
        val tap = tapQueue.poll() ?: return

        val hitResults = frame.hitTest(tap.first, tap.second)
        for (hit in hitResults) {
            val trackable = hit.trackable
            if (trackable is Plane && trackable.isPoseInPolygon(hit.hitPose)) {
                 val hitPose = hit.hitPose
                 val poseMatrix = FloatArray(16)
                 hitPose.toMatrix(poseMatrix, 0)
                 arImagePose = poseMatrix

                 // Notify placement success
                 onImagePlaced?.invoke()
                 break
            }
        }
    }

    private fun handleDrag(frame: Frame) {
        val point = dragPoint ?: return
        dragPoint = null // Consume

        val hitResults = frame.hitTest(point.first, point.second)
        for (hit in hitResults) {
             val trackable = hit.trackable
             if (trackable is Plane && trackable.isPoseInPolygon(hit.hitPose)) {
                  val poseMatrix = FloatArray(16)
                  hit.hitPose.toMatrix(poseMatrix, 0)
                  arImagePose = poseMatrix
                  break
             }
        }
    }

    private fun drawPlacedImage(viewMatrix: FloatArray, projectionMatrix: FloatArray) {
        val bmp = overlayBitmap ?: return
        val pose = arImagePose ?: return

        val modelMatrix = pose.clone()

        // Apply rotation (Z)
        Matrix.rotateM(modelMatrix, 0, arObjectRotation, 0f, 0f, 1f)

        // Apply Scale
        val aspectRatio = if (bmp.height > 0) bmp.width.toFloat() / bmp.height.toFloat() else 1f
        Matrix.scaleM(modelMatrix, 0, arObjectScale * aspectRatio, arObjectScale, 1f)

        simpleQuadRenderer.draw(modelMatrix, viewMatrix, projectionMatrix, bmp, opacity, colorBalanceR, colorBalanceG, colorBalanceB)
    }

    private fun drawLockedImage(frame: Frame) {
        val bmp = overlayBitmap ?: return
        val pattern = arFeaturePattern ?: return

        val homography = HomographyHelper.calculateHomography(
            pattern.worldPoints,
            frame.camera,
            surfaceWidth,
            surfaceHeight,
            bmp.width,
            bmp.height
        )

        homography?.let {
             val inverted = it.inv()
             projectedImageRenderer.draw(bmp, inverted, opacity, colorBalanceR, colorBalanceG, colorBalanceB)
             inverted.release()
             it.release()
        }
    }

    private fun generateFeaturePattern(frame: Frame) {
        try {
            val image = frame.acquireCameraImage()
            val mat = imageToMat(image)
            image.close()

            val keypoints = MatOfKeyPoint()
            val descriptors = Mat()
            orb.detectAndCompute(mat, Mat(), keypoints, descriptors)

            val worldPoints = mutableListOf<FloatArray>()
            val validDescriptorsList = mutableListOf<Mat>()

            keypoints.toList().forEachIndexed { i, keypoint ->
                val hitResults = frame.hitTest(keypoint.pt.x.toFloat(), keypoint.pt.y.toFloat())
                if (hitResults.isNotEmpty()) {
                    val hitResult = hitResults.first()
                    val pose = hitResult.hitPose
                    worldPoints.add(floatArrayOf(pose.tx(), pose.ty(), pose.tz()))
                    validDescriptorsList.add(descriptors.row(i))
                }
            }

            if (worldPoints.size >= 4) {
                val patternDescriptors = Mat()
                org.opencv.core.Core.vconcat(validDescriptorsList, patternDescriptors)
                Log.d(TAG, "New feature pattern generated.")
                arFeaturePattern = ArFeaturePattern(patternDescriptors, worldPoints)
                featurePatternGenerated = true
            }
        } catch (e: NotYetAvailableException) {
            // Camera image not yet available
        } catch (e: Exception) {
            Log.e(TAG, "Error generating feature pattern", e)
        }
    }

    private fun imageToMat(image: Image): Mat {
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        val mat = Mat(image.height, image.width, CvType.CV_8UC1)
        mat.put(0, 0, bytes)
        return mat
    }

    companion object {
        private const val TAG = "ARCoreRenderer"
    }
}
