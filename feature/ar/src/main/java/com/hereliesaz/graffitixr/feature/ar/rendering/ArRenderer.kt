// FILE: feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/rendering/ArRenderer.kt
package com.hereliesaz.graffitixr.feature.ar.rendering

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.NotYetAvailableException
import com.google.ar.core.exceptions.SessionPausedException
import com.hereliesaz.graffitixr.common.util.ImageProcessingUtils
import com.hereliesaz.graffitixr.feature.ar.DisplayRotationHelper
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import timber.log.Timber
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
    private var frameCount = 0

    private var pendingFlashlightMode: Boolean? = null

    fun attachSession(session: Session?) {
        Timber.i("attachSession: session is ${if (session != null) "NOT null" else "null"}")
        this.session = session
        if (session != null) {
            displayRotationHelper.onResume()
        } else {
            displayRotationHelper.onPause()
        }
    }

    fun updateFlashlight(isOn: Boolean) {
        pendingFlashlightMode = isOn
    }

    private fun getFlashlightModeEnum(isOn: Boolean): Any? {
        return try {
            val flashlightModeClass = Class.forName("com.google.ar.core.Config\$FlashlightMode")
            val fieldName = if (isOn) "ON" else "OFF"
            flashlightModeClass.getField(fieldName).get(null)
        } catch (e: Exception) {
            Timber.e(e, "Failed to get FlashlightMode enum via reflection")
            null
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        Timber.i("onSurfaceCreated")
        GLES30.glClearColor(0.0f, 0.0f, 0.0f, 0.0f)
        backgroundRenderer.createOnGlThread(context)
        slamManager.ensureInitialized()
        slamManager.initGl()
        session?.setCameraTextureName(backgroundRenderer.textureId)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        Timber.i("onSurfaceChanged: ${width}x${height}")
        GLES30.glViewport(0, 0, width, height)
        displayRotationHelper.onSurfaceChanged(width, height)
        slamManager.updateViewport(width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        val activeSession = session ?: return

        pendingFlashlightMode?.let { isOn ->
            getFlashlightModeEnum(isOn)?.let { mode ->
                try {
                    val config = activeSession.config
                    val flashlightModeClass = Class.forName("com.google.ar.core.Config\$FlashlightMode")
                    val method = config.javaClass.getMethod("setFlashlightMode", flashlightModeClass)
                    method.invoke(config, mode)
                    activeSession.configure(config)
                } catch (e: Exception) {
                    Timber.e(e, "Failed to apply flashlight configuration")
                }
            }
            pendingFlashlightMode = null
        }

        displayRotationHelper.updateSessionIfNeeded(activeSession)

        val frame: Frame = try {
            activeSession.update()
        } catch (e: SessionPausedException) {
            return
        } catch (e: Exception) {
            Timber.e(e, "Session update failed")
            return
        }

        backgroundRenderer.draw(frame)
        val camera = frame.camera
        val isTracking = camera.trackingState == TrackingState.TRACKING

        camera.getViewMatrix(viewMatrix, 0)
        camera.getProjectionMatrix(projMatrix, 0, 0.1f, 100.0f)
        slamManager.setArCoreTrackingState(isTracking)
        slamManager.updateCamera(viewMatrix, projMatrix)

        if (frameCount++ % 2 == 0) {
            try {
                frame.acquireCameraImage().use { image ->
                    val rgbaBuffer = ImageProcessingUtils.convertYuvToRgbaDirect(image)
                    slamManager.feedColorFrame(rgbaBuffer, image.width, image.height)
                }

                frame.acquireDepthImage16Bits().use { depthImage ->
                    val depthPlane = depthImage.planes[0]
                    slamManager.feedArCoreDepth(
                        depthPlane.buffer,
                        depthImage.width,
                        depthImage.height,
                        depthPlane.rowStride
                    )
                }
            } catch (e: NotYetAvailableException) {
                // Expected at the beginning of a session
            } catch (e: Exception) {
                Timber.e(e, "Error processing frame data")
            }
        }

        slamManager.draw()
        onTrackingUpdated(isTracking)
    }
}