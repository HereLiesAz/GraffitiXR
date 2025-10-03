package com.hereliesaz.graffitixr.graphics

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import com.google.ar.core.Anchor
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.hereliesaz.graffitixr.UiState
import java.util.concurrent.ConcurrentLinkedQueue
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class ArRenderer(private val context: Context, private val onSessionInitialized: (Session) -> Unit) : GLSurfaceView.Renderer {

    private var session: Session? = null
    private val backgroundRenderer = BackgroundRenderer()
    private val objectRenderer = ObjectRenderer()
    private var displayRotationHelper: DisplayRotationHelper = DisplayRotationHelper(context)
    private val queuedSingleTaps = ConcurrentLinkedQueue<MotionEvent>()

    val anchors = mutableListOf<Anchor>()
    var uiState: UiState = UiState()

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f)
        backgroundRenderer.createOnGlThread()
        objectRenderer.createOnGlThread(context)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        displayRotationHelper.onSurfaceChanged(width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        // Ensure ARCore session is initialized
        if (session == null) {
            // Check for ARCore availability and create a session
            val availability = ArCoreApk.getInstance().checkAvailability(context)
            if (availability.isTransient) {
                // Re-check in a few frames
                return
            }
            session = if (availability.isSupported) {
                Session(context).also { onSessionInitialized(it) }
            } else {
                // ARCore not supported or installed
                null
            }
        }

        session?.let {
            // Update the session display geometry if the rotation changes
            displayRotationHelper.updateSessionIfNeeded(it)

            try {
                it.setCameraTextureName(backgroundRenderer.textureId)
                val frame = it.update()
                backgroundRenderer.draw(frame)

                val camera = frame.camera

                // Handle taps
                queuedSingleTaps.poll()?.let { tap ->
                    if (camera.trackingState == TrackingState.TRACKING) {
                        handleTap(frame, tap.x, tap.y)
                    }
                }

                if(camera.trackingState == TrackingState.PAUSED) return@let

                // Get projection matrix.
                val projmtx = FloatArray(16)
                camera.getProjectionMatrix(projmtx, 0, 0.1f, 100.0f)

                // Get camera matrix and draw objects.
                val viewmtx = FloatArray(16)
                camera.getViewMatrix(viewmtx, 0)

                // Draw all placed anchors
                for (anchor in anchors) {
                    if (anchor.trackingState == TrackingState.TRACKING) {
                        objectRenderer.draw(
                            viewmtx,
                            projmtx,
                            anchor,
                            uiState.opacity,
                            uiState.contrast,
                            uiState.saturation
                        )
                    }
                }

            } catch (e: CameraNotAvailableException) {
                // Handle camera not available exception
            }
        }
    }

    fun onSurfaceTapped(event: MotionEvent) {
        queuedSingleTaps.add(event)
    }

    fun updateTexture(uri: android.net.Uri) {
        objectRenderer.updateTexture(context, uri)
    }

    private fun handleTap(frame: Frame, x: Float, y: Float) {
        for (hit in frame.hitTest(x, y)) {
            val trackable = hit.trackable
            // Check if the hit was on a plane
            if (trackable is Plane && trackable.isPoseInPolygon(hit.hitPose)) {
                // Create an anchor at the hit pose
                anchors.add(hit.createAnchor())
                break // Stop after the first valid hit
            }
        }
    }

    fun onResume() {
        // The GLSurfaceView's onResume() is called on the main thread.
        // We need to make sure the ARCore session is resumed.
        session?.resume()
        displayRotationHelper.onResume()
    }

    fun onPause() {
        // The GLSurfaceView's onPause() is called on the main thread.
        // We need to make sure the ARCore session is paused.
        displayRotationHelper.onPause()
        session?.pause()
    }
}