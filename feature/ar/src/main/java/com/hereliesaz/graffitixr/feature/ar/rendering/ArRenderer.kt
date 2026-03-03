package com.hereliesaz.graffitixr.feature.ar.rendering

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.util.Log
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.NotYetAvailableException
import com.google.ar.core.exceptions.SessionPausedException
import com.hereliesaz.graffitixr.common.util.ImageProcessingUtils
import com.hereliesaz.graffitixr.feature.ar.DisplayRotationHelper
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class ArRenderer(
    private val context: Context,
    private val slamManager: SlamManager,
    private val onTrackingUpdated: (Boolean) -> Unit
) : GLSurfaceView.Renderer {

    var session: Session? = null
        private set
    private val backgroundRenderer = BackgroundRenderer()
    private val displayRotationHelper = DisplayRotationHelper(context)

    private val viewMatrix = FloatArray(16)
    private val projMatrix = FloatArray(16)
    private var cameraTextureNameSet = false
    private var frameCount = 0

    fun attachSession(session: Session?) {
        this.session = session
        if (session != null) {
            displayRotationHelper.onResume()
        } else {
            displayRotationHelper.onPause()
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
        backgroundRenderer.createOnGlThread(context)
        slamManager.ensureInitialized()
        cameraTextureNameSet = false
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        displayRotationHelper.onSurfaceChanged(width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        val activeSession = session ?: return

        if (!cameraTextureNameSet) {
            activeSession.setCameraTextureName(backgroundRenderer.textureId)
            cameraTextureNameSet = true
        }

        displayRotationHelper.updateSessionIfNeeded(activeSession)
        val frame: Frame = try {
            activeSession.update()
        } catch (e: SessionPausedException) {
            return
        }
        val camera = frame.camera

        backgroundRenderer.draw(frame)

        val isTracking = camera.trackingState == TrackingState.TRACKING
        slamManager.setArCoreTrackingState(isTracking)

        // Throttle the expensive Kotlin YUV→RGBA conversion to ~20fps.
        // At 60fps this loop consumes ~100% CPU and causes ANRs.
        val shouldFeedColorFrame = (frameCount++ % 3 == 0)

        if (isTracking) {
            camera.getViewMatrix(viewMatrix, 0)
            camera.getProjectionMatrix(projMatrix, 0, 0.1f, 100.0f)
            slamManager.updateCamera(viewMatrix, projMatrix)

            try {
                // To avoid the JNI bridge returning early because it hasn't seen a color frame yet,
                // we'll feed a color frame FIRST if we're doing both this frame.
                if (shouldFeedColorFrame) {
                    val cameraImage = frame.acquireCameraImage()
                    val rgbaBuffer = ImageProcessingUtils.convertYuvToRgbaDirect(cameraImage)
                    slamManager.feedColorFrame(rgbaBuffer, cameraImage.width, cameraImage.height)
                    cameraImage.close()
                }

                val depthImage = frame.acquireDepthImage16Bits()
                val depthBuffer = depthImage.planes[0].buffer
                slamManager.feedArCoreDepth(depthBuffer, depthImage.width, depthImage.height)
                depthImage.close()

            } catch (e: NotYetAvailableException) {
                // Normal during ARCore initialization — depth data not yet ready.
                if (frameCount % 100 == 0) Log.d("ArRenderer", "Depth not yet available...")
            } catch (e: UnsupportedOperationException) {
                // Depth API not supported on this device / session not configured for depth.
                Log.w("ArRenderer", "Depth API unavailable: ${e.message}")
            } catch (e: Exception) {
                Log.e("ArRenderer", "Depth frame error: ${e.message}")
            }
        } else if (shouldFeedColorFrame) {
            try {
                val cameraImage = frame.acquireCameraImage()
                val rgbaBuffer = ImageProcessingUtils.convertYuvToRgbaDirect(cameraImage)
                slamManager.feedColorFrame(rgbaBuffer, cameraImage.width, cameraImage.height)
                cameraImage.close()
                if (frameCount % 100 == 0) Log.d("ArRenderer", "Feeding color frame while not tracking (reloc mode)")
            } catch (e: Exception) {
                // Ignore
            }
        }

        slamManager.draw()

        onTrackingUpdated(isTracking)
    }
}
