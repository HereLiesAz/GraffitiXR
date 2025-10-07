package com.hereliesaz.graffitixr.graphics

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import android.view.View
import coil.imageLoader
import coil.request.ImageRequest
import com.google.ar.core.Anchor
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import androidx.compose.ui.geometry.Offset
import com.google.ar.core.exceptions.NotYetAvailableException
import com.hereliesaz.graffitixr.ArState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.opencv.core.Mat
import java.util.concurrent.ConcurrentLinkedQueue
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class ArRenderer(
    private val context: Context,
    private val view: View,
    private val onArImagePlaced: (Anchor) -> Unit,
    private val onArFeaturesDetected: (ArFeaturePattern) -> Unit,
    private val onPlanesDetected: (Boolean) -> Unit,
    private val onArDrawingProgressChanged: (Float) -> Unit,
    val onShowTapFeedback: (Offset, Boolean) -> Unit
) : GLSurfaceView.Renderer {

    private var session: Session? = null
    private val backgroundRenderer = BackgroundRenderer()
    private val planeRenderer = PlaneRenderer()
    private val pointCloudRenderer = PointCloudRenderer()
    private val simpleQuadRenderer = SimpleQuadRenderer()
    private val projectedImageRenderer = ProjectedImageRenderer()
    private val markerDetector = MarkerDetector()
    private val displayRotationHelper = DisplayRotationHelper(context)


    private val tapQueue = ConcurrentLinkedQueue<Pair<Float, Float>>()

    var arImagePose: FloatArray? = null
    var arFeaturePattern: ArFeaturePattern? = null
    var overlayImageUri: Uri? = null
    var arState: ArState = ArState.SEARCHING
    var arObjectScale: Float = 1.0f
    var arObjectOrientation: Quaternion = Quaternion.identity()
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
                return
            }
            backgroundRenderer.draw(frame)

            val camera = frame.camera
            if (camera.trackingState != TrackingState.TRACKING) return

            val projectionMatrix = FloatArray(16)
            camera.getProjectionMatrix(projectionMatrix, 0, 0.1f, 100.0f)
            val viewMatrix = FloatArray(16)
            camera.getViewMatrix(viewMatrix, 0)

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
                    val cameraPose = frame.camera.pose
                    val allPlanes = it.getAllTrackables(Plane::class.java)
                    val filteredPlanes = allPlanes.filter { plane ->
                        if (plane.trackingState != TrackingState.TRACKING || plane.subsumedBy != null) {
                            false
                        } else {
                            val planeNormal = plane.centerPose.yAxis
                            val cameraToPlane = floatArrayOf(
                                plane.centerPose.tx() - cameraPose.tx(),
                                plane.centerPose.ty() - cameraPose.ty(),
                                plane.centerPose.tz() - cameraPose.tz()
                            )
                            val dotProduct = planeNormal.zip(cameraToPlane).sumOf { (a, b) -> (a * b).toDouble() }.toFloat()
                            dotProduct < 0
                        }
                    }

                    onPlanesDetected(filteredPlanes.isNotEmpty())
                    filteredPlanes.forEach { plane -> planeRenderer.draw(plane, viewMatrix, projectionMatrix) }
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
                        val modelMatrix = poseMatrix.clone()
                        val orientation = arObjectOrientation.toGlMatrix()
                        Matrix.multiplyMM(modelMatrix, 0, modelMatrix, 0, orientation, 0)

                        val aspectRatio = if (bmp.height > 0) bmp.width.toFloat() / bmp.height.toFloat() else 1f
                        Matrix.scaleM(modelMatrix, 0, arObjectScale * aspectRatio, arObjectScale, 1f)

                        simpleQuadRenderer.draw(modelMatrix, viewMatrix, projectionMatrix, bmp, opacity)
                    }
                }
            }
        }
    }

    fun onSurfaceTapped(x: Float, y: Float) {
        if (arState == ArState.SEARCHING) {
            tapQueue.add(Pair(x, y))
        }
    }

    private fun handleTapForPlacement(frame: Frame) {
        val tap = tapQueue.poll() ?: return
        val cameraPose = frame.camera.pose
        var wasSuccessful = false
        for (hit in frame.hitTest(tap.first, tap.second)) {
            val trackable = hit.trackable
            if (trackable is Plane && trackable.isPoseInPolygon(hit.hitPose)) {
                val planeNormal = trackable.centerPose.yAxis
                val cameraToPlane = floatArrayOf(
                    trackable.centerPose.tx() - cameraPose.tx(),
                    trackable.centerPose.ty() - cameraPose.ty(),
                    trackable.centerPose.tz() - cameraPose.tz()
                )
                val dotProduct = planeNormal.zip(cameraToPlane).sumOf { (a, b) -> (a * b).toDouble() }.toFloat()

                if (dotProduct < 0) {
                    onArImagePlaced(hit.createAnchor())
                    wasSuccessful = true
                    break
                }
            }
        }
        onShowTapFeedback(Offset(tap.first, tap.second), wasSuccessful)
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

            if (worldPoints.size >= 4) {
                val patternDescriptors = Mat()
                org.opencv.core.Core.vconcat(validDescriptorsList, patternDescriptors)
                Log.d("ArRenderer", "New feature pattern generated.")
                onArFeaturesDetected(ArFeaturePattern(patternDescriptors, worldPoints))
                featurePatternGenerated = true
            }
        } catch (e: NotYetAvailableException) {
            // Camera image not yet available
        }
    }

    private fun loadOverlayBitmap() {
        val uri = overlayImageUri
        if (uri == null) {
            overlayBitmap = null
            lastLoadedUri = null
            return
        }

        lastLoadedUri = uri
        CoroutineScope(Dispatchers.IO).launch {
            val request = ImageRequest.Builder(context)
                .data(uri)
                .allowHardware(false)
                .build()
            val result = (context.imageLoader.execute(request).drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
            if (result != null) {
                overlayBitmap = result
            }
        }
    }

    fun resume() {
        if (session == null) {
            try {
                when (ArCoreApk.getInstance().requestInstall(context as Activity, true)) {
                    ArCoreApk.InstallStatus.INSTALLED -> {
                        session = Session(context)
                        val config = Config(session)
                        config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                        session?.configure(config)
                    }
                    else -> {
                        Log.e("ArRenderer", "ARCore installation required.")
                        return
                    }
                }
            } catch (e: Exception) {
                Log.e("ArRenderer", "Failed to create AR session", e)
                return
            }
        }

        try {
            session?.resume()
        } catch (e: CameraNotAvailableException) {
            Log.e("ArRenderer", "Camera not available. Please restart the app.", e)
            session = null
        }
        displayRotationHelper.onResume()
    }

    fun pause() {
        displayRotationHelper.onPause()
        session?.pause()
    }
}
