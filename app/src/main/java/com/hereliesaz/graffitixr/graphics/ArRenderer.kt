package com.hereliesaz.graffitixr.graphics

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import android.view.MotionEvent
import android.view.View
import coil.imageLoader
import coil.request.ImageRequest
import com.google.ar.core.Anchor
import com.google.ar.core.ArCoreApk
import com.hereliesaz.graffitixr.ArState
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.NotYetAvailableException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.opencv.core.Mat
import java.util.concurrent.ConcurrentLinkedQueue
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * A GLES renderer for the AR experience.
 *
 * This class is responsible for managing the ARCore session, handling user input, and rendering the
 * AR scene. This includes drawing the camera background, detected planes, and the virtual content
 * (the user's overlay image). It now manages its own ARCore [Session] lifecycle, including robust
 * installation and availability checks.
 *
 * @param context The application context.
 * @param view The [View] that this renderer is attached to.
 * @param onArImagePlaced A callback invoked when the user places the AR image.
 * @param onArFeaturesDetected A callback invoked when a new set of AR features has been detected
 * and computed.
 */
class ArRenderer(
    private val context: Context,
    private val view: View,
    private val onArImagePlaced: (Anchor) -> Unit,
    private val onArFeaturesDetected: (ArFeaturePattern) -> Unit,
    private val onPlanesDetected: (Boolean) -> Unit
) : GLSurfaceView.Renderer {

    private var session: Session? = null
    private val backgroundRenderer = BackgroundRenderer()
    private val planeRenderer = PlaneRenderer()
    private val pointCloudRenderer = PointCloudRenderer()
    private val simpleQuadRenderer = SimpleQuadRenderer()
    private val projectedImageRenderer = ProjectedImageRenderer()
    private val markerDetector = MarkerDetector()
    private val displayRotationHelper = DisplayRotationHelper(context)

    private val tapQueue = ConcurrentLinkedQueue<MotionEvent>()

    var arImagePose: FloatArray? = null
    var arFeaturePattern: ArFeaturePattern? = null
    var overlayImageUri: Uri? = null
    var arState: ArState = ArState.SEARCHING
    var arObjectScale: Float = 1.0f
    var arObjectRotation: Float = 0.0f
    var opacity: Float = 1.0f

    private var overlayBitmap: Bitmap? = null
    private var lastLoadedUri: Uri? = null
    private var featurePatternGenerated = false

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f)
        backgroundRenderer.createOnGlThread()
        planeRenderer.createOnGlThread()
        pointCloudRenderer.createOnGlThread()
        simpleQuadRenderer.createOnGlThread()
        projectedImageRenderer.createOnGlThread()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        displayRotationHelper.onSurfaceChanged(width, height)
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        session?.let {
            it.setCameraTextureName(backgroundRenderer.textureId)
            displayRotationHelper.updateSessionIfNeeded(it)
            val frame = try {
                it.update()
            } catch (e: com.google.ar.core.exceptions.SessionPausedException) {
                // The session is paused, probably due to a lifecycle event.
                // This is expected and we can just return since we have nothing to render.
                return
            }
            backgroundRenderer.draw(frame)

            val camera = frame.camera
            if (camera.trackingState != TrackingState.TRACKING) return

            val projectionMatrix = FloatArray(16)
            camera.getProjectionMatrix(projectionMatrix, 0, 0.1f, 100.0f)
            val viewMatrix = FloatArray(16)
            camera.getViewMatrix(viewMatrix, 0)

            // Draw point cloud
            try {
                frame.acquirePointCloud().use { pointCloud ->
                    pointCloudRenderer.update(pointCloud)
                    pointCloudRenderer.draw(viewMatrix, projectionMatrix)
                }
            } catch (e: NotYetAvailableException) {
                // Point cloud is not available yet.
            }

            when (arState) {
                ArState.SEARCHING -> {
                    handleTapForPlacement(frame)
                    featurePatternGenerated = false
                    val planes = it.getAllTrackables(Plane::class.java)
                    val arePlanesDetected = planes.any { p -> p.trackingState == TrackingState.TRACKING && p.subsumedBy == null }
                    onPlanesDetected(arePlanesDetected)
                    planes.filter { p -> p.trackingState == TrackingState.TRACKING && p.subsumedBy == null }
                        .forEach { plane -> planeRenderer.draw(plane, viewMatrix, projectionMatrix) }
                }
                ArState.PLACED -> {
                    onPlanesDetected(false)
                }
                ArState.LOCKED -> {
                    onPlanesDetected(false)
                    if (!featurePatternGenerated) {
                        generateFeaturePattern(frame)
                    }
                }
            }

            if (overlayImageUri != lastLoadedUri) {
                loadOverlayBitmap()
            }

            val bmp = overlayBitmap
            if (bmp != null) {
                if (arState == ArState.LOCKED) {
                    arFeaturePattern?.let { pattern ->
                        val homography = HomographyHelper.calculateHomography(pattern.worldPoints, camera, view, bmp.width, bmp.height)
                        homography?.let {
                            projectedImageRenderer.draw(bmp, it, opacity)
                        }
                    }
                } else { // SEARCHING or PLACED
                    arImagePose?.let { poseMatrix ->
                        // Create a copy of the pose matrix to apply transformations
                        val modelMatrix = poseMatrix.clone()

                        // We rotate around the Y-axis of the anchor's pose, which corresponds to the plane's normal.
                        val upX = poseMatrix[4]
                        val upY = poseMatrix[5]
                        val upZ = poseMatrix[6]

                        // Apply the current rotation. The rotation is in radians from the gesture detector.
                        // We convert it to degrees for OpenGL.
                        Matrix.rotateM(modelMatrix, 0, Math.toDegrees(arObjectRotation.toDouble()).toFloat(), upX, upY, upZ)

                        // Apply the current scale.
                        Matrix.scaleM(modelMatrix, 0, arObjectScale, arObjectScale, arObjectScale)

                        // Draw the quad with the final transformed matrix.
                        simpleQuadRenderer.draw(modelMatrix, viewMatrix, projectionMatrix, bmp, opacity)
                    }
                }
            }
        }
    }

    fun onSurfaceTapped(event: MotionEvent) {
        if (arState == ArState.SEARCHING) {
            tapQueue.add(event)
        }
    }

    private fun handleTapForPlacement(frame: Frame) {
        val tap = tapQueue.poll() ?: return
        for (hit in frame.hitTest(tap)) {
            val trackable = hit.trackable
            if (trackable is Plane && trackable.isPoseInPolygon(hit.hitPose)) {
                onArImagePlaced(hit.createAnchor())
                break
            }
        }
    }

    private fun generateFeaturePattern(frame: Frame) {
        try {
            val image = frame.acquireCameraImage()
            val mat = ImageConverter.toMat(image)
            val (keypoints, descriptors) = markerDetector.detectAndCompute(mat)
            image.close()

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

            if (worldPoints.size >= 4) { // Need at least 4 points for homography
                val patternDescriptors = Mat()
                org.opencv.core.Core.vconcat(validDescriptorsList, patternDescriptors)
                onArFeaturesDetected(ArFeaturePattern(patternDescriptors, worldPoints))
                featurePatternGenerated = true
            }
        } catch (e: NotYetAvailableException) {
            // Camera image not yet available
        }
    }

    private fun loadOverlayBitmap() {
        lastLoadedUri = overlayImageUri
        overlayImageUri?.let { uri ->
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val request = ImageRequest.Builder(context).data(uri).build()
                    val result = context.imageLoader.execute(request).drawable
                    overlayBitmap = (result as? android.graphics.drawable.BitmapDrawable)?.bitmap
                } catch (e: Exception) {
                    Log.e("ArRenderer", "Failed to load overlay image", e)
                    overlayBitmap = null
                }
            }
        }
    }

    fun onResume() {
        if (session == null) {
            try {
                val availability = ArCoreApk.getInstance().checkAvailability(context)
                when (availability) {
                    ArCoreApk.Availability.SUPPORTED_INSTALLED -> {
                        session = Session(context)
                        val config = Config(session)
                        config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                        config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                        session!!.configure(config)
                    }
                    ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD,
                    ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED -> {
                        try {
                            ArCoreApk.getInstance().requestInstall(context as Activity, true)
                        } catch (e: Exception) {
                            Log.e("ArRenderer", "Failed to request ARCore installation/update.", e)
                        }
                        return
                    }
                    else -> {
                        Log.e("ArRenderer", "ARCore is not supported on this device. Availability: $availability")
                        return
                    }
                }
            } catch (e: Exception) {
                Log.e("ArRenderer", "Failed to create or configure AR session", e)
                session = null
                return
            }
        }

        try {
            session?.resume()
            displayRotationHelper.onResume()
        } catch (e: CameraNotAvailableException) {
            Log.e("ArRenderer", "Camera not available on resume", e)
            session = null
        }
    }

    fun onPause() {
        displayRotationHelper.onPause()
        session?.pause()
    }
}