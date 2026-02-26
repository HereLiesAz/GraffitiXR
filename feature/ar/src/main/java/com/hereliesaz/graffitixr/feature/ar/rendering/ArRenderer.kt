package com.hereliesaz.graffitixr.feature.ar.rendering

import android.content.Context
import android.opengl.GLES11Ext
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
    private var hasSetTextureNames = false
    private var dummyTextureId = -1

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        Timber.tag("AR_DEBUG").e(">>> [1] onSurfaceCreated() INITIATED")

        // CRITICAL FIX: Ensure the GL surface is fully transparent to allow the CameraX
        // preview behind it to be visible.
        GLES30.glClearColor(0.0f, 0.0f, 0.0f, 0.0f)

        try {
            // Generate a dummy texture ID to satisfy ARCore's internal requirements
            // even though we aren't drawing the camera feed through OpenGL.
            val textures = IntArray(1)
            GLES30.glGenTextures(1, textures, 0)
            dummyTextureId = textures[0]
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, dummyTextureId)

            slamManager.createOnGlThread()
            slamManager.resetGLState()
            slamManager.ensureInitialized()
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
            return
        }

        try {
            if (!hasSetTextureNames) {
                currentSession.setCameraTextureName(dummyTextureId)
                hasSetTextureNames = true
                Timber.tag("AR_DEBUG").e(">>> [5] Camera texture name linked to ARCore Session")
            }

            // Update ARCore frame
            val frame = currentSession.update()
            val camera = frame.camera

            if (camera.trackingState == TrackingState.TRACKING) {
                val viewMatrix = FloatArray(16)
                val projMatrix = FloatArray(16)

                camera.getViewMatrix(viewMatrix, 0)
                camera.getProjectionMatrix(projMatrix, 0, 0.1f, 100.0f)

                slamManager.updateCamera(viewMatrix, projMatrix)
                slamManager.draw()
            }
        } catch (e: Exception) {
            Timber.tag("AR_DEBUG").e(e, ">>> [!] CRITICAL EXCEPTION during onDrawFrame")
        }
    }
}