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
    private val onLightUpdated: (Float) -> Unit,
    private val onDiag: (String) -> Unit = {}
) : GLSurfaceView.Renderer {

    private val backgroundScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val sessionLock = ReentrantLock()

    var session: Session? = null
        private set

    private val backgroundRenderer = BackgroundRenderer()
    private val displayRotationHelper = DisplayRotationHelper(context)

    private var frameCount = 0
    private var diagFrameCount = 0
    private var sensorOrientation = 90  // degrees; queried from CameraCharacteristics on session attach
    private var lastDepthSupported: Boolean? = null
    private var lastTrackingState: Boolean? = null
    private var lastDepthW = 0
    private var lastDepthH = 0
    private var lastDepthNotYetAvailable = false
    private var pendingFlashlightMode: Boolean? = null
    private var isSurfaceCreated = false

    // Thread-safe flag updated directly by Compose's AndroidView update block
    @Volatile var captureRequested: Boolean = false

    fun attachSession(session: Session?) {
        sessionLock.withLock {
            this.session = session
            if (session != null) {
                displayRotationHelper.onResume()
                if (isSurfaceCreated) {
                    session.setCameraTextureName(backgroundRenderer.textureId)
                }
                // Query sensor orientation once per session so depth rotation is
                // correct on any device, not just Pixel 5 (sensor_orientation=90).
                try {
                    val cameraId = session.cameraConfig.cameraId
                    val manager = context.getSystemService(android.content.Context.CAMERA_SERVICE)
                            as android.hardware.camera2.CameraManager
                    sensorOrientation = manager
                        .getCameraCharacteristics(cameraId)
                        .get(android.hardware.camera2.CameraCharacteristics.SENSOR_ORIENTATION)
                        ?: 90
                } catch (e: Exception) {
                    sensorOrientation = 90  // safe fallback — correct for most rear cameras
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

            val isTracking = camera.trackingState == TrackingState.TRACKING

            // FIX: Call setArCoreTrackingState synchronously here on the GL thread,
            // BEFORE feeding depth data. The old code sent this via a coroutine channel,
            // meaning processDepthFrame would check mIsArCoreTracking while it was still
            // false (the async update hadn't fired yet), silently dropping every depth
            // frame. Synchronous call guarantees the native tracking flag is set before
            // any depth for this frame reaches the map thread queue.
            slamManager.setArCoreTrackingState(isTracking)

            // Report tracking + splat count to the ViewModel on a background thread
            // so we don't block the GL thread on getSplatCount().
            backgroundScope.launch {
                val splatCount = slamManager.getSplatCount()
                onTrackingUpdated(isTracking, splatCount)
            }

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

            val depthSupported = activeSession.isDepthModeSupported(Config.DepthMode.AUTOMATIC)

            if (frameCount++ % 2 == 0) {
                var colorFed = false
                var colorW = 0; var colorH = 0
                try {
                    frame.acquireCameraImage().use { image ->
                        val planes = image.planes
                        slamManager.feedYuvFrame(
                            planes[0].buffer, planes[1].buffer, planes[2].buffer,
                            image.width, image.height,
                            planes[0].rowStride, planes[1].rowStride, planes[1].pixelStride,
                            frame.timestamp
                        )
                        colorFed = true
                        colorW = image.width; colorH = image.height
                    }
                } catch (e: Exception) {}

                var depthFed = false
                lastDepthNotYetAvailable = false
                if (depthSupported) {
                    try {
                        frame.acquireDepthImage16Bits().use { depthImage ->
                            val depthPlane = depthImage.planes[0]
                            // Compute cv::RotateFlags (-1=none, 0=CW90, 1=180, 2=CCW90).
                            // Depth pixels are in sensor orientation; we rotate to display
                            // orientation so they align with the display-corrected view matrix.
                            // Formula: (sensorOrientation - displayDegrees + 360) % 360
                            val displayDegrees = displayRotationHelper.getRotation() * 90
                            val cvRotateCode = when ((sensorOrientation - displayDegrees + 360) % 360) {
                                90  -> 0  // cv::ROTATE_90_CLOCKWISE
                                180 -> 1  // cv::ROTATE_180
                                270 -> 2  // cv::ROTATE_90_COUNTERCLOCKWISE
                                else -> -1 // no rotation needed
                            }
                            slamManager.feedArCoreDepth(
                                depthPlane.buffer,
                                depthImage.width, depthImage.height,
                                depthPlane.rowStride,
                                cvRotateCode
                            )
                            depthFed = true
                            lastDepthW = depthImage.width; lastDepthH = depthImage.height
                        }
                    } catch (e: NotYetAvailableException) {
                        lastDepthNotYetAvailable = true
                    } catch (e: Exception) {}
                }

                // Emit diag every 30 fed frames (~2 seconds at 30fps/every-other)
                diagFrameCount++
                if (diagFrameCount % 30 == 0) {
                    val splatCount = slamManager.getSplatCount()
                    val sb = StringBuilder()
                    sb.appendLine("=== Frame #$diagFrameCount ===")
                    sb.appendLine("Tracking: $isTracking")
                    sb.appendLine("DepthAPI: $depthSupported")
                    sb.appendLine("Color fed: $colorFed  ${colorW}x${colorH}")
                    sb.appendLine("Depth fed: $depthFed")
                    if (!depthFed && depthSupported) sb.appendLine("  ↳ NotYetAvailable: $lastDepthNotYetAvailable")
                    if (depthFed) sb.appendLine("  ↳ ${lastDepthW}x${lastDepthH}")
                    sb.appendLine("Splats: $splatCount")
                    onDiag(sb.toString().trimEnd())
                }
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
