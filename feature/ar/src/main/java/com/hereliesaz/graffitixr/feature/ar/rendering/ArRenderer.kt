package com.hereliesaz.graffitixr.feature.ar.rendering

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import timber.log.Timber
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class ArRenderer(
    private val context: Context,
    private val slamManager: SlamManager
) : GLSurfaceView.Renderer {

    var session: Session? = null
    private val backgroundRenderer = BackgroundRenderer()
    private var hasSetTextureNames = false

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        Timber.tag("AR_DEBUG").e(">>> [1] onSurfaceCreated() INITIATED")
        GLES30.glClearColor(0.1f, 0.1f, 0.1f, 1.0f)

        try {
            backgroundRenderer.createOnGlThread(context)
            Timber.tag("AR_DEBUG").e(">>> [2] BackgroundRenderer created successfully")

            slamManager.resetGLState()
            slamManager.initialize()
            slamManager.setVisualizationMode(0) // 0 = AR Mode
            Timber.tag("AR_DEBUG").e(">>> [3] SlamManager initialized for AR Mode")
        } catch (e: Exception) {
            Timber.tag("AR_DEBUG").e(e, ">>> [!] FATAL ERROR in onSurfaceCreated")
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        Timber.tag("AR_DEBUG").e(">>> [4] onSurfaceChanged() triggered. Width: $width, Height: $height")
        GLES30.glViewport(0, 0, width, height)
        slamManager.onSurfaceChanged(width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        val currentSession = session
        if (currentSession == null) {
            Timber.tag("AR_DEBUG").e(">>> [!] ABORT onDrawFrame: ARCore Session is NULL!")
            return
        }

        try {
            if (!hasSetTextureNames) {
                currentSession.setCameraTextureName(backgroundRenderer.textureId)
                hasSetTextureNames = true
                Timber.tag("AR_DEBUG").e(">>> [5] Camera texture name linked to ARCore Session")
            }

            // Update ARCore frame
            val frame = currentSession.update()
            val camera = frame.camera

            // Draw the camera feed
            backgroundRenderer.draw(frame)

            // CRITICAL CHECK: Is ARCore actually tracking the world?
            if (camera.trackingState == TrackingState.TRACKING) {
                // We log this as debug instead of error so it doesn't completely flood the red error logs 60 times a second,
                // but if you want it red, change .d to .e!
                Timber.tag("AR_DEBUG").d(">>> [6] Camera is TRACKING. Sending matrices to SlamManager.")

                val viewMatrix = FloatArray(16)
                val projMatrix = FloatArray(16)

                camera.getViewMatrix(viewMatrix, 0)
                camera.getProjectionMatrix(projMatrix, 0, 0.1f, 100.0f)

                slamManager.updateCamera(viewMatrix, projMatrix)
                slamManager.draw()
            } else {
                Timber.tag("AR_DEBUG").e(">>> [X] Camera NOT TRACKING. Reason: ${camera.trackingFailureReason}")
            }

        } catch (e: Exception) {
            Timber.tag("AR_DEBUG").e(e, ">>> [!] CRITICAL EXCEPTION during onDrawFrame")
        }
    }
}