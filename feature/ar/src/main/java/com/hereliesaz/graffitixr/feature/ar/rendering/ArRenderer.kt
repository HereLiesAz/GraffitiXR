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
import timber.log.Timber
import java.nio.ByteBuffer
import java.util.concurrent.locks.ReentrantLock
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.concurrent.withLock

class ArRenderer(
    private val context: Context,
    private val slamManager: SlamManager,
    private val isCaptureRequested: () -> Boolean,
    private val onTargetCaptured: (Bitmap, ByteBuffer?, Int, Int, FloatArray?) -> Unit,
    private val onTrackingUpdated: (Boolean, Int) -> Unit
) : GLSurfaceView.Renderer {

    private val sessionLock = ReentrantLock()
    var session: Session? = null
        private set

    private val backgroundRenderer = BackgroundRenderer()
    private val displayRotationHelper = DisplayRotationHelper(context)

    private val viewMatrix = FloatArray(16)
    private val projMatrix = FloatArray(16)
    private var frameCount = 0

    private var pendingFlashlightMode: Boolean? = null
    private var isSurfaceCreated = false

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
            val flashlightModeClass = Class.forName("com.google.ar.core.Config\$FlashMode")
            val fieldName = if (isOn) "TORCH" else "OFF"
            flashlightModeClass.getField(fieldName).get(null)
        } catch (e: Exception) {
            null
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
        backgroundRenderer.createOnGlThread(context)
        slamManager.ensureInitialized()
        slamManager.initGl()

        sessionLock.withLock {
            isSurfaceCreated = true
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

            pendingFlashlightMode?.let { isOn ->
                getFlashlightModeEnum(isOn)?.let { mode ->
                    try {
                        val config = activeSession.config
                        val flashlightModeClass = Class.forName("com.google.ar.core.Config\$FlashMode")
                        val method = config.javaClass.getMethod("setFlashMode", flashlightModeClass)
                        method.invoke(config, mode)
                        activeSession.configure(config)
                    } catch (e: Exception) {}
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
            isTracking = camera.trackingState == TrackingState.TRACKING

            camera.getViewMatrix(viewMatrix, 0)
            camera.getProjectionMatrix(projMatrix, 0, 0.1f, 100.0f)
            slamManager.setArCoreTrackingState(isTracking)
            slamManager.updateCamera(viewMatrix, projMatrix)

            if (isCaptureRequested()) {
                try {
                    val image = frame.acquireCameraImage()
                    val rgbaBuffer = ImageProcessingUtils.convertYuvToRgbaDirect(image)
                    val bitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
                    bitmap.copyPixelsFromBuffer(rgbaBuffer)
                    image.close()

                    var depthBuffer: ByteBuffer? = null
                    var depthWidth = 0
                    var depthHeight = 0

                    if (activeSession.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                        val depthImage = frame.acquireDepthImage16Bits()
                        val plane = depthImage.planes[0]
                        val limit = plane.buffer.limit()
                        depthBuffer = ByteBuffer.allocateDirect(limit)
                        depthBuffer.put(plane.buffer)
                        depthBuffer.rewind()
                        depthWidth = depthImage.width
                        depthHeight = depthImage.height
                        depthImage.close()
                    }

                    val intrinsics = frame.camera.imageIntrinsics
                    val fx = intrinsics.focalLength[0]
                    val fy = intrinsics.focalLength[1]
                    val cx = intrinsics.principalPoint[0]
                    val cy = intrinsics.principalPoint[1]

                    onTargetCaptured(bitmap, depthBuffer, depthWidth, depthHeight, floatArrayOf(fx, fy, cx, cy))
                } catch (e: Exception) {
                    Timber.e(e, "Failed to capture target frame")
                }
            }

            if (frameCount++ % 2 == 0) {
                try {
                    frame.acquireCameraImage().use { image ->
                        val rgbaBuffer = ImageProcessingUtils.convertYuvToRgbaDirect(image)
                        slamManager.feedColorFrame(rgbaBuffer, image.width, image.height)
                    }

                    if (activeSession.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                        frame.acquireDepthImage16Bits().use { depthImage ->
                            val depthPlane = depthImage.planes[0]
                            slamManager.feedArCoreDepth(
                                depthPlane.buffer,
                                depthImage.width,
                                depthImage.height,
                                depthPlane.rowStride
                            )
                        }
                    }
                } catch (e: NotYetAvailableException) {
                } catch (e: Exception) {}
            }

            slamManager.draw()
        }

        onTrackingUpdated(isTracking, slamManager.getSplatCount())
    }
}