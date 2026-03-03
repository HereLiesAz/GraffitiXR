package com.hereliesaz.graffitixr.feature.ar.rendering

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.util.Log
import com.google.ar.core.Config
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
import kotlin.math.sin

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

    private var pendingFlashlightMode: Int? = null

    fun attachSession(session: Session?) {
        Log.i("ArRenderer", "attachSession: session is ${if (session != null) "NOT null" else "null"}")
        this.session = session
        if (session != null) {
            displayRotationHelper.onResume()
        } else {
            displayRotationHelper.onPause()
        }
    }

    /**
     * Toggles the flashlight if supported.
     * Note: Flashlight control requires ARCore 1.32.0+
     * We queue the change to be applied on the next frame to avoid CameraAccessException.
     */
    fun updateFlashlight(isOn: Boolean) {
        pendingFlashlightMode = if (isOn) 1 else 0
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        Log.i("ArRenderer", "onSurfaceCreated")
        GLES30.glClearColor(0.0f, 0.0f, 0.0f, 0.0f) // Fully transparent background
        backgroundRenderer.createOnGlThread(context)
        slamManager.ensureInitialized()
        slamManager.initGl() // Initialize GL resources for MobileGS
        cameraTextureNameSet = false
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        Log.i("ArRenderer", "onSurfaceChanged: ${width}x${height}")
        GLES30.glViewport(0, 0, width, height)
        displayRotationHelper.onSurfaceChanged(width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        // Clear with transparent black
        GLES30.glClearColor(0.0f, 0.0f, 0.0f, 0.0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        
        val activeSession = session
        if (activeSession == null) {
            if (frameCount % 100 == 0) Log.w("ArRenderer", "onDrawFrame: session is null")
            frameCount++
            return
        }

        // Apply pending flashlight changes
        pendingFlashlightMode?.let { mode ->
            try {
                val config = activeSession.config
                val method = config.javaClass.getMethod("setFlashlightMode", Int::class.javaPrimitiveType)
                method.invoke(config, mode)
                activeSession.configure(config)
                Log.d("ArRenderer", "Flashlight mode updated to $mode")
            } catch (e: Exception) {
                Log.e("ArRenderer", "Failed to update flashlight", e)
            } finally {
                pendingFlashlightMode = null
            }
        }

        if (!cameraTextureNameSet) {
            activeSession.setCameraTextureName(backgroundRenderer.textureId)
            cameraTextureNameSet = true
        }

        displayRotationHelper.updateSessionIfNeeded(activeSession)
        val frame: Frame = try {
            activeSession.update()
        } catch (e: SessionPausedException) {
            if (frameCount % 100 == 0) Log.w("ArRenderer", "onDrawFrame: session paused")
            frameCount++
            return
        } catch (e: Exception) {
            Log.e("ArRenderer", "Session update failed", e)
            frameCount++
            return
        }
        
        val camera = frame.camera

        // 1. Draw Camera Feed
        backgroundRenderer.draw(frame)

        val isTracking = camera.trackingState == TrackingState.TRACKING
        slamManager.setArCoreTrackingState(isTracking)

        // Always update camera matrices
        camera.getViewMatrix(viewMatrix, 0)
        camera.getProjectionMatrix(projMatrix, 0, 0.1f, 100.0f)
        slamManager.updateCamera(viewMatrix, projMatrix)

        val shouldFeedColorFrame = (frameCount % 3 == 0)
        frameCount++

        // 2. Process Data
        if (isTracking) {
            try {
                if (shouldFeedColorFrame) {
                    val cameraImage = frame.acquireCameraImage()
                    try {
                        val rgbaBuffer = ImageProcessingUtils.convertYuvToRgbaDirect(cameraImage)
                        slamManager.feedColorFrame(rgbaBuffer, cameraImage.width, cameraImage.height)
                    } finally {
                        cameraImage.close()
                    }
                }

                val depthImage = try {
                    frame.acquireDepthImage16Bits()
                } catch (e: NotYetAvailableException) {
                    null
                }
                
                depthImage?.let { image ->
                    try {
                        val depthBuffer = image.planes[0].buffer
                        slamManager.feedArCoreDepth(depthBuffer, image.width, image.height)
                    } finally {
                        image.close()
                    }
                }

            } catch (e: NotYetAvailableException) {
                // Normal during ARCore initialization
            } catch (e: UnsupportedOperationException) {
                // Depth API not supported
            } catch (e: Exception) {
                Log.e("ArRenderer", "Frame processing error", e)
            }
        } else if (shouldFeedColorFrame) {
            try {
                val cameraImage = frame.acquireCameraImage()
                try {
                    val rgbaBuffer = ImageProcessingUtils.convertYuvToRgbaDirect(cameraImage)
                    slamManager.feedColorFrame(rgbaBuffer, cameraImage.width, cameraImage.height)
                } finally {
                    cameraImage.close()
                }
            } catch (e: Exception) { }
        }

        // 3. Draw Overlays
        slamManager.draw()

        onTrackingUpdated(isTracking)
    }
}
