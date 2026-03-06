package com.hereliesaz.graffitixr.feature.ar.rendering

import android.content.Context
import android.graphics.Bitmap
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.nio.ByteBuffer
import java.util.concurrent.locks.ReentrantLock
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.concurrent.withLock

class ArRenderer(
    private val context: Context,
    private val slamManager: SlamManager,
    private val onTargetCaptured: (Bitmap?, ByteBuffer?, Int, Int, FloatArray?) -> Unit,
    private val onTrackingUpdated: (Boolean, Int) -> Unit,
    private val onLightUpdated: (Float) -> Unit
) : GLSurfaceView.Renderer {

    private val backgroundScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val sessionLock = ReentrantLock()

    var session: Session? = null
        private set

    private val backgroundRenderer = BackgroundRenderer()
    private val displayRotationHelper = DisplayRotationHelper(context)

    private val frameChannel = Channel<Boolean>(
        capacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private var frameCount = 0
    private var pendingFlashlightMode: Boolean? = null
    private var isSurfaceCreated = false

    // Thread-safe flag updated directly by Compose's AndroidView update block
    @Volatile var captureRequested: Boolean = false

    init {
        backgroundScope.launch {
            frameChannel.consumeAsFlow().collect { isTracking ->
                slamManager.setArCoreTrackingState(isTracking)
                val splatCount = slamManager.getSplatCount()
                onTrackingUpdated(isTracking, splatCount)
            }
        }
    }

    fun attachSession(session: Session?) {
        sessionLock.withLock {
            this.session = session
            if (session != null) {
                displayRotationHelper.onResume()
                if (isSurfaceCreated) {
                    session.setCameraTextureName(backgroundRenderer.textureId)
                }
            } else {
                displayRotationHelper.onPause()
            }
        }
    }

    fun updateFlashlight(isOn: Boolean) {
        pendingFlashlightMode = isOn
    }

    private fun getFlashlightModeEnum(isOn: Boolean): Any? {
        return try {
            val flashlightModeClass = Class.forName("com.google.ar.core.Config\$FlashlightMode")
            val fieldName = if (isOn) "TORCH" else "OFF"
            flashlightModeClass.getField(fieldName).get(null)
        } catch (e: Exception) {
            null
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
        backgroundRenderer.createOnGlThread(context)
        slamManager.initGl()
        isSurfaceCreated = true

        sessionLock.withLock {
            session?.setCameraTextureName(backgroundRenderer.textureId)
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        displayRotationHelper.onSurfaceChanged(width, height)
        slamManager.setViewportSize(width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        var isTracking = false
        sessionLock.withLock {
            val activeSession = session ?: return

            activeSession.setCameraTextureName(backgroundRenderer.textureId)

            pendingFlashlightMode?.let { isOn ->
                getFlashlightModeEnum(isOn)?.let { mode ->
                    try {
                        val config = activeSession.config
                        val method = config.javaClass.getMethod("setFlashlightMode", mode.javaClass)
                        method.invoke(config, mode)
                        activeSession.configure(config)
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to update flashlight mode")
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
                Timber.e(e, "ARCore session update failed")
                return
            }

            backgroundRenderer.draw(frame)
            val camera = frame.camera

            val viewMatrix = FloatArray(16)
            val projMatrix = FloatArray(16)
            camera.getViewMatrix(viewMatrix, 0)
            camera.getProjectionMatrix(projMatrix, 0, 0.1f, 100.0f)

            slamManager.updateCamera(viewMatrix, projMatrix, frame.timestamp)

            val lightEstimate = frame.lightEstimate
            if (lightEstimate.state == com.google.ar.core.LightEstimate.State.VALID) {
                onLightUpdated(lightEstimate.pixelIntensity)
            }

            isTracking = camera.trackingState == TrackingState.TRACKING
            frameChannel.trySend(isTracking)

            // Safely consume the flag and acquire the image
            if (captureRequested) {
                captureRequested = false
                try {
                    frame.acquireCameraImage().use { image ->
                        val rgbaBuffer = ImageProcessingUtils.convertYuvToRgbaDirect(image)
                        val bitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
                        bitmap.copyPixelsFromBuffer(rgbaBuffer)

                        var depthBuffer: ByteBuffer? = null
                        var depthWidth = 0
                        var depthHeight = 0

                        if (activeSession.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                            try {
                                frame.acquireDepthImage16Bits().use { depthImage ->
                                    val plane = depthImage.planes[0]
                                    depthBuffer = ByteBuffer.allocateDirect(plane.buffer.remaining())
                                    depthBuffer!!.put(plane.buffer)
                                    depthBuffer!!.rewind()
                                    depthWidth = depthImage.width
                                    depthHeight = depthImage.height
                                }
                            } catch (e: Exception) {}
                        }

                        val intrinsics = camera.imageIntrinsics
                        val intrArr = floatArrayOf(
                            intrinsics.focalLength[0], intrinsics.focalLength[1],
                            intrinsics.principalPoint[0], intrinsics.principalPoint[1]
                        )

                        onTargetCaptured(bitmap, depthBuffer, depthWidth, depthHeight, intrArr)
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Failed to capture target frame")
                }
            }

            if (frameCount++ % 2 == 0) {
                try {
                    frame.acquireCameraImage().use { image ->
                        val planes = image.planes
                        slamManager.feedYuvFrame(
                            planes[0].buffer, planes[1].buffer, planes[2].buffer,
                            image.width, image.height,
                            planes[0].rowStride, planes[1].rowStride, planes[1].pixelStride,
                            frame.timestamp
                        )
                    }
                    if (activeSession.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                        try {
                            frame.acquireDepthImage16Bits().use { depthImage ->
                                val depthPlane = depthImage.planes[0]
                                slamManager.feedArCoreDepth(depthPlane.buffer, depthImage.width, depthImage.height, depthPlane.rowStride)
                            }
                        } catch (e: NotYetAvailableException) {}
                    }
                } catch (e: Exception) {}
            }

            slamManager.draw()
        }
    }

    fun destroy() {
        backgroundScope.cancel("Renderer detached and destroyed.")
        sessionLock.withLock {
            session = null
        }
    }
}