package com.hereliesaz.graffitixr.graphics

import android.app.Activity
import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.Log
import android.view.View
import com.google.ar.core.Anchor
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.NotYetAvailableException
import java.util.concurrent.ConcurrentLinkedQueue
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * A simplified AR renderer that only handles the AR session, camera background,
 * point cloud, and detected planes. It no longer draws the overlay image, as
 * that is now handled by the Jetpack Compose UI layer.
 *
 * It exposes the view and projection matrices so that the UI layer can project
 * the 3D anchor point to a 2D screen coordinate.
 */
class ArRenderer(
    private val context: Context,
    private val view: View,
    private val onArImagePlaced: (Anchor) -> Unit,
    private val onPlanesDetected: (Boolean) -> Unit,
) : GLSurfaceView.Renderer {

    private var session: Session? = null
    private val backgroundRenderer = BackgroundRenderer()
    private val planeRenderer = PlaneRenderer()
    private val pointCloudRenderer = PointCloudRenderer()
    private val displayRotationHelper = DisplayRotationHelper(context)

    private val tapQueue = ConcurrentLinkedQueue<Pair<Float, Float>>()

    // Make matrices accessible to the UI thread.
    @Volatile
    var viewMatrix = FloatArray(16)
    @Volatile
    var projectionMatrix = FloatArray(16)

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f)
        backgroundRenderer.createOnGlThread()
        planeRenderer.createOnGlThread()
        pointCloudRenderer.createOnGlThread()
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

            // Update the matrices on each frame.
            camera.getProjectionMatrix(projectionMatrix, 0, 0.1f, 100.0f)
            camera.getViewMatrix(viewMatrix, 0)

            try {
                frame.acquirePointCloud().use { pointCloud ->
                    pointCloudRenderer.update(pointCloud)
                    pointCloudRenderer.draw(viewMatrix, projectionMatrix)
                }
            } catch (e: NotYetAvailableException) {
                // Point cloud is not available yet.
            }

            handleTapForPlacement(frame)

            // Show planes so the user knows where they can tap.
            val allPlanes = it.getAllTrackables(Plane::class.java)
            onPlanesDetected(allPlanes.any { p -> p.trackingState == TrackingState.TRACKING })
            for (plane in allPlanes) {
                if (plane.trackingState == TrackingState.TRACKING) {
                    planeRenderer.draw(plane, viewMatrix, projectionMatrix)
                }
            }
        }
    }

    fun onSurfaceTapped(x: Float, y: Float) {
        tapQueue.add(Pair(x, y))
    }

    private fun handleTapForPlacement(frame: Frame) {
        val tap = tapQueue.poll() ?: return
        for (hit in frame.hitTest(tap.first, tap.second)) {
            val trackable = hit.trackable
            if (trackable is Plane && trackable.isPoseInPolygon(hit.hitPose) &&
                isLookingAtPlane(frame.camera.pose, hit.hitPose)
            ) {
                onArImagePlaced(hit.createAnchor())
                break
            }
        }
    }

    private fun isLookingAtPlane(cameraPose: com.google.ar.core.Pose, planePose: com.google.ar.core.Pose): Boolean {
        val cameraToPlane = floatArrayOf(
            planePose.tx() - cameraPose.tx(),
            planePose.ty() - cameraPose.ty(),
            planePose.tz() - cameraPose.tz()
        )
        val dotProduct = cameraPose.zAxis.zip(cameraToPlane).sumOf { (a, b) -> (a * b).toDouble() }.toFloat()
        return dotProduct < 0
    }

    fun resume() {
        if (session == null) {
            try {
                when (ArCoreApk.getInstance().requestInstall(context as Activity, true)) {
                    ArCoreApk.InstallStatus.INSTALLED -> {
                        session = Session(context)
                        val config = Config(session)
                        config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                        config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
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