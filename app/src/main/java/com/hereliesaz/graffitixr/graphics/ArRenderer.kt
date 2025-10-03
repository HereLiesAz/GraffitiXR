package com.hereliesaz.graffitixr.graphics

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.view.MotionEvent
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
import com.google.ar.core.exceptions.NotYetAvailableException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
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
    private val onArFeaturesDetected: (ArFeaturePattern) -> Unit
) : GLSurfaceView.Renderer {

    private var session: Session? = null
    private val backgroundRenderer = BackgroundRenderer()
    private val planeRenderer = PlaneRenderer()
    private val simpleQuadRenderer = SimpleQuadRenderer()
    private val projectedImageRenderer = ProjectedImageRenderer()
    private val markerDetector = MarkerDetector()
    private val displayRotationHelper = DisplayRotationHelper(context)

    private val tapQueue = ConcurrentLinkedQueue<MotionEvent>()

    var arImagePose: FloatArray? = null
    var arFeaturePattern: ArFeaturePattern? = null
    var overlayImageUri: Uri? = null
    var isArLocked: Boolean = false
    var opacity: Float = 1.0f

    private var overlayBitmap: Bitmap? = null
    private var lastLoadedUri: Uri? = null
    private var featurePatternGenerated = false

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f)
        backgroundRenderer.createOnGlThread()
        planeRenderer.createOnGlThread()
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
            displayRotationHelper.updateSessionIfNeeded(it)
            val frame = it.update()
            backgroundRenderer.draw(frame)

            val camera = frame.camera
            if (camera.trackingState != TrackingState.TRACKING) return

            if (isArLocked) {
                if (!featurePatternGenerated) {
                    generateFeaturePattern(frame)
                }
            } else {
                handleTapForPlacement(frame)
                featurePatternGenerated = false
            }

            val projectionMatrix = FloatArray(16)
            camera.getProjectionMatrix(projectionMatrix, 0, 0.1f, 100.0f)
            val viewMatrix = FloatArray(16)
            camera.getViewMatrix(viewMatrix, 0)

            if (!isArLocked) {
                val planes = it.getAllTrackables(Plane::class.java)
                for (plane in planes) {
                    if (plane.trackingState == TrackingState.TRACKING && plane.subsumedBy == null) {
                        planeRenderer.draw(plane, camera.displayOrientedPose, projectionMatrix)
                    }
                }
            }

            if (overlayImageUri != lastLoadedUri) {
                loadOverlayBitmap()
            }

            val bmp = overlayBitmap
            if (bmp != null) {
                if (isArLocked) {
                    arFeaturePattern?.let { pattern ->
                        val homography = HomographyHelper.calculateHomography(pattern.worldPoints, camera, view, bmp.width, bmp.height)
                        homography?.let {
                            projectedImageRenderer.draw(bmp, it, opacity)
                        }
                    }
                } else {
                    arImagePose?.let {
                        simpleQuadRenderer.draw(it, viewMatrix, projectionMatrix, bmp, opacity)
                    }
                }
            }
        }
    }

    fun onSurfaceTapped(event: MotionEvent) {
        if (!isArLocked) {
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
                val request = ImageRequest.Builder(context).data(uri).build()
                overlayBitmap = (context.imageLoader.execute(request).drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
            }
        }
    }

    fun onResume() {
        if (session == null) {
            try {
                val installStatus = ArCoreApk.getInstance().requestInstall(context as android.app.Activity, true)
                if (installStatus == ArCoreApk.InstallStatus.INSTALLED) {
                    session = Session(context)
                    val config = Config(session)
                    config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                    session!!.configure(config)
                }
            } catch (e: UnavailableUserDeclinedInstallationException) {
                // Handle error
            }
        }
        session?.resume()
        displayRotationHelper.onResume()
    }

    fun onPause() {
        displayRotationHelper.onPause()
        session?.pause()
    }
}