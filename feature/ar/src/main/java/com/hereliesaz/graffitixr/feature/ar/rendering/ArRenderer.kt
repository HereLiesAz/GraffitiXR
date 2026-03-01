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

    @Volatile var session: Session? = null
    private val backgroundRenderer = BackgroundRenderer()
    private var hasSetTextureNames = false

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        Timber.tag("AR_DEBUG").e(">>> [1] onSurfaceCreated() INITIATED")
        GLES30.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
        try {
            backgroundRenderer.createOnGlThread(context)
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
        // Rotation 0 = portrait; update if the app supports landscape.
        session?.setDisplayGeometry(0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        val currentSession = session ?: return

        try {
            if (!hasSetTextureNames) {
                currentSession.setCameraTextureName(backgroundRenderer.textureId)
                hasSetTextureNames = true
                Timber.tag("AR_DEBUG").e(">>> [5] Camera texture linked to BackgroundRenderer")
            }

            val frame = currentSession.update()
            val camera = frame.camera

            // Always draw the camera feed as the background.
            backgroundRenderer.draw(frame)

            if (camera.trackingState == TrackingState.TRACKING) {
                val viewMatrix = FloatArray(16)
                val projMatrix = FloatArray(16)
                camera.getViewMatrix(viewMatrix, 0)
                camera.getProjectionMatrix(projMatrix, 0, 0.1f, 100.0f)
                slamManager.updateCamera(viewMatrix, projMatrix)

                // Feed luminance (Y-plane) to the SLAM monocular pipeline.
                try {
                    val image = frame.acquireCameraImage()
                    try {
                        val yPlane = image.planes[0]
                        slamManager.feedMonocularData(yPlane.buffer, image.width, image.height)
                    } finally {
                        image.close()
                    }
                } catch (_: Exception) { /* frame unavailable this tick */ }

                // Feed ARCore DEPTH16 to the SLAM depth pipeline when available.
                try {
                    val depthImage = frame.acquireDepthImage16Bits()
                    try {
                        val depthPlane = depthImage.planes[0]
                        slamManager.feedArCoreDepth(depthPlane.buffer, depthImage.width, depthImage.height)
                    } finally {
                        depthImage.close()
                    }
                } catch (_: Exception) { /* depth not available on this device or frame */ }
            }
        } catch (e: Exception) {
            Timber.tag("AR_DEBUG").e(e, ">>> [!] CRITICAL EXCEPTION during onDrawFrame")
        }
    }
}
