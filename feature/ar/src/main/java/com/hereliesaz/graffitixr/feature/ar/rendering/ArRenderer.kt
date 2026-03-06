package com.hereliesaz.graffitixr.feature.ar.rendering

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.view.Surface
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.hereliesaz.graffitixr.common.util.ImageProcessingUtils
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.concurrent.withLock

class ArRenderer(
    private val context: Context,
    private val slamManager: SlamManager,
    private val isCaptureRequested: () -> Boolean,
    private val onTargetCaptured: (Bitmap?, FloatArray?, Int, Int, FloatArray?) -> Unit,
    private val onTrackingUpdated: (Boolean, Int) -> Unit
) : GLSurfaceView.Renderer {

    private var session: Session? = null
    private val backgroundScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val sessionLock = ReentrantLock()
    var session: Session? = null
        private set

    private val backgroundRenderer = BackgroundRenderer()

    private val frameChannel = Channel<Boolean>(
        capacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    init {
        backgroundScope.launch {
            frameChannel.consumeAsFlow().collect { isTracking ->
                slamManager.setArCoreTrackingState(isTracking)
                val splatCount = slamManager.getSplatCount()
                onTrackingUpdated(isTracking, splatCount)
            }
        }
    }
    private var pendingFlashlightMode: Boolean? = null
    private var isSurfaceCreated = false

    fun setSession(session: Session) {
        this.session = session
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
        try {
            session?.let { activeSession ->
                val config = activeSession.config
                config.focusMode = Config.FocusMode.AUTO
                val flashlightModeClass = Class.forName("com.google.ar.core.Config\$FlashMode")
                val method = config.javaClass.getMethod("setFlashMode", flashlightModeClass)

                val flashModeEnum = if (isOn) {
                    flashlightModeClass.getField("TORCH").get(null)
                } else {
                    flashlightModeClass.getField("OFF").get(null)
                }

                method.invoke(config, flashModeEnum)
                activeSession.configure(config)
            }
    private fun getFlashlightModeEnum(isOn: Boolean): Any? {
        return try {
            val flashlightModeClass = Class.forName("com.google.ar.core.Config\$FlashMode")
            val fieldName = if (isOn) "TORCH" else "OFF"
            flashlightModeClass.getField(fieldName).get(null)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0.1f, 0.1f, 0.1f, 1.0f)
        GLES30.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
        backgroundRenderer.createOnGlThread(context)
        slamManager.initGl()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        session?.setDisplayGeometry(Surface.ROTATION_0, width, height)
        displayRotationHelper.onSurfaceChanged(width, height)
        slamManager.setViewportSize(width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        val currentSession = session ?: return
        var isTracking = false

        sessionLock.withLock {
            val activeSession = session ?: return

        try {
            currentSession.setCameraTextureName(backgroundRenderer.textureId)
            val frame = currentSession.update()
            // BULLETPROOF: Ensure the texture is bound every frame so the camera feed doesn't drop
            activeSession.setCameraTextureName(backgroundRenderer.textureId)

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

            backgroundRenderer.draw(frame)

            val viewMatrix = FloatArray(16)
            val projMatrix = FloatArray(16)
            camera.getViewMatrix(viewMatrix, 0)
            camera.getProjectionMatrix(projMatrix, 0, 0.1f, 100.0f)

            slamManager.updateCamera(viewMatrix, projMatrix)

            val isTracking = camera.trackingState == TrackingState.TRACKING
            frameChannel.trySend(isTracking)

            slamManager.draw()

            if (isCaptureRequested()) {
                frame.acquireCameraImage().use { image ->
                    val bmp = ImageProcessingUtils.yuvToRgbBitmap(image)
                    onTargetCaptured(bmp, null, image.width, image.height, null)
                }
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun destroy() {
        backgroundScope.cancel("Renderer detached and destroyed.")
        session = null
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