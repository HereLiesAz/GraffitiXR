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
import com.hereliesaz.graffitixr.common.model.ArScanMode
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
    private val onTargetCaptured: (Bitmap, Int, Int, ByteBuffer?, Int, Int, Int, FloatArray?, FloatArray, Int) -> Unit,
    private val onTrackingUpdated: (Boolean, Int, Boolean, Float) -> Unit,
    private val onLightUpdated: (Float) -> Unit,
    private val onDiag: (String) -> Unit = {}
) : GLSurfaceView.Renderer {

    private val backgroundScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val sessionLock = ReentrantLock()

    var session: Session? = null
        private set

    private val backgroundRenderer = BackgroundRenderer()
    private val displayRotationHelper = DisplayRotationHelper(context)
    private val overlayRenderer = OverlayRenderer(context)
    private val pointCloudRenderer = PointCloudRenderer()
    private val planeRenderer = PlaneRenderer()

    @Volatile var scanMode: ArScanMode = ArScanMode.CLOUD_POINTS
    @Volatile var showAnchorBoundary: Boolean = false

    fun saveCloudPoints(path: String) {
        pointCloudRenderer.saveToFile(path)
    }

    fun scheduleCloudPointsLoad(path: String) {
        pointCloudRenderer.pendingLoadPath = path
    }

    @Volatile private var pendingOverlayBitmap: Bitmap? = null
    @Volatile private var overlayBitmapDirty = false

    private var frameCount = 0
    private var diagFrameCount = 0
    private var sensorOrientation = 90  
    private var isSurfaceCreated = false
    private var arCameraId: String? = null

    @Volatile var captureRequested: Boolean = false

    fun attachSession(session: Session?) {
        sessionLock.withLock {
            this.session = session
            if (session != null) {
                displayRotationHelper.onResume()
                if (isSurfaceCreated) {
                    session.setCameraTextureName(backgroundRenderer.textureId)
                }
                try {
                    val cameraId = session.cameraConfig.cameraId
                    arCameraId = cameraId
                    val manager = context.getSystemService(android.content.Context.CAMERA_SERVICE)
                            as android.hardware.camera2.CameraManager
                    sensorOrientation = manager
                        .getCameraCharacteristics(cameraId)
                        .get(android.hardware.camera2.CameraCharacteristics.SENSOR_ORIENTATION)
                        ?: 90
                } catch (e: Exception) {
                    sensorOrientation = 90
                }
            } else {
                displayRotationHelper.onPause()
            }
        }
    }

    fun updateOverlayBitmap(bitmap: Bitmap?) {
        pendingOverlayBitmap = bitmap
        overlayBitmapDirty = true
    }

    fun updateOverlayExtent(halfW: Float, halfH: Float) {
        overlayRenderer.setExtent(halfW, halfH)
    }

    fun updateFlashlight(isOn: Boolean) {
        try {
            val manager = context.getSystemService(android.content.Context.CAMERA_SERVICE)
                as android.hardware.camera2.CameraManager
            // Prefer back-facing camera with flash over ARCore's camera ID, which may not
            // have FLASH_INFO_AVAILABLE on all devices.
            val cameraId = manager.cameraIdList.firstOrNull { id ->
                val chars = manager.getCameraCharacteristics(id)
                chars.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING) ==
                    android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK &&
                chars.get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: arCameraId ?: return
            manager.setTorchMode(cameraId, isOn)
        } catch (e: Exception) {
            Timber.e(e, "Failed to set torch mode (isOn=$isOn)")
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
        backgroundRenderer.createOnGlThread(context)
        
        // This is the critical fix. When the phone rotates, or the app goes to
        // background, the OpenGL context is completely destroyed by Android. 
        // We MUST tell the C++ layer to flush all its old GL handles (VBOs/Shaders)
        // and recompile them in this new context, otherwise everything disappears.
        slamManager.resetGlContext()
        slamManager.initGl()
        
        overlayRenderer.createOnGlThread()
        pointCloudRenderer.createOnGlThread(context)
        planeRenderer.createOnGlThread(context)
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

            GLES30.glDepthMask(true)
            GLES30.glEnable(GLES30.GL_DEPTH_TEST)
            GLES30.glDepthFunc(GLES30.GL_LEQUAL)

            val camera = frame.camera

            val viewMatrix = FloatArray(16)
            val projMatrix = FloatArray(16)
            camera.getViewMatrix(viewMatrix, 0)
            camera.getProjectionMatrix(projMatrix, 0, 0.1f, 100.0f)

            // Mapping matrices: use the unrotated sensor pose and sensor-aligned projection.
            // This ensures processDepthFrame and other native mapping functions
            // receive view matrices aligned with the sensor-oriented depth/color frames.
            val mappingViewMatrix = FloatArray(16)
            val mappingProjMatrix = FloatArray(16)
            camera.pose.inverse().toMatrix(mappingViewMatrix, 0)

            // Construct sensor-aligned projection matrix from image intrinsics.
            // This bypasses display rotation/aspect-ratio-scaling.
            val intrinsics = camera.imageIntrinsics
            val focalLength = intrinsics.focalLength
            val principalPoint = intrinsics.principalPoint
            val dims = intrinsics.imageDimensions
            
            mappingProjMatrix[0] = 2.0f * focalLength[0] / dims[0]
            mappingProjMatrix[5] = 2.0f * focalLength[1] / dims[1]
            mappingProjMatrix[8] = 2.0f * principalPoint[0] / dims[0] - 1.0f
            mappingProjMatrix[9] = 1.0f - 2.0f * principalPoint[1] / dims[1]
            mappingProjMatrix[10] = -(100.1f) / (99.9f) // far+near / near-far (0.1, 100)
            mappingProjMatrix[11] = -1.0f
            mappingProjMatrix[14] = -(2.0f * 100.0f * 0.1f) / (99.9f)
            mappingProjMatrix[15] = 0.0f

            slamManager.updateCamera(viewMatrix, projMatrix, mappingViewMatrix, mappingProjMatrix, frame.timestamp)

            val lightEstimate = frame.lightEstimate
            if (lightEstimate.state == com.google.ar.core.LightEstimate.State.VALID) {
                onLightUpdated(lightEstimate.pixelIntensity)
            }

            val isTracking = camera.trackingState == TrackingState.TRACKING
            val depthSupported = activeSession.isDepthModeSupported(Config.DepthMode.AUTOMATIC)

            // Extract camera yaw (horizontal heading) from the view matrix.
            // View matrix row 2 = camera backward in world space; forward = negation.
            val yawRad = kotlin.math.atan2(-viewMatrix[2].toDouble(), -viewMatrix[10].toDouble())
            val yawDeg = Math.toDegrees(yawRad).toFloat()

            slamManager.setArCoreTrackingState(isTracking)

            val currentScanMode = scanMode
            backgroundScope.launch {
                val count = if (currentScanMode == ArScanMode.CLOUD_POINTS) {
                    pointCloudRenderer.accumulatedPointCount
                } else {
                    slamManager.getSplatCount()
                }
                onTrackingUpdated(isTracking, count, depthSupported, yawDeg)
            }

            if (captureRequested) {
                captureRequested = false
                try {
                    frame.acquireCameraImage().use { image ->
                        val rgbaBuffer = ImageProcessingUtils.convertYuvToRgbaDirect(image)
                        val bitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
                        bitmap.copyPixelsFromBuffer(rgbaBuffer)

                        val displayDegrees = displayRotationHelper.getRotation() * 90
                        val rotationNeeded = (sensorOrientation - displayDegrees + 360) % 360

                        var depthBuffer: ByteBuffer? = null
                        var depthWidth = 0
                        var depthHeight = 0
                        var depthStride = 0

                        if (activeSession.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                            try {
                                frame.acquireDepthImage16Bits().use { depthImage ->
                                    val plane = depthImage.planes[0]
                                    depthBuffer = ByteBuffer.allocateDirect(plane.buffer.remaining())
                                    depthBuffer!!.put(plane.buffer)
                                    depthBuffer!!.rewind()
                                    depthWidth = depthImage.width
                                    depthHeight = depthImage.height
                                    depthStride = plane.rowStride
                                }
                            } catch (e: Exception) {}
                        }

                        val intrinsics = camera.imageIntrinsics
                        val intrArr = floatArrayOf(
                            intrinsics.focalLength[0], intrinsics.focalLength[1],
                            intrinsics.principalPoint[0], intrinsics.principalPoint[1]
                        )

                        onTargetCaptured(
                            bitmap, image.width, image.height,
                            depthBuffer,
                            depthWidth, depthHeight, depthStride,
                            intrArr, mappingViewMatrix.copyOf(),
                            rotationNeeded
                        )
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
                } catch (e: Exception) {}

                if (currentScanMode == ArScanMode.CLOUD_POINTS) {
                    try {
                        frame.acquirePointCloud().use { pointCloud ->
                            pointCloudRenderer.update(pointCloud)
                        }
                    } catch (e: Exception) {}
                } else {
                    if (depthSupported) {
                        try {
                            frame.acquireDepthImage16Bits().use { depthImage ->
                                val depthPlane = depthImage.planes[0]
                                
                                val intrinsics = camera.imageIntrinsics
                                val intrArr = floatArrayOf(
                                    intrinsics.focalLength[0], intrinsics.focalLength[1],
                                    intrinsics.principalPoint[0], intrinsics.principalPoint[1]
                                )
                                val cpuDim = intrinsics.imageDimensions

                                slamManager.feedArCoreDepth(
                                    depthPlane.buffer,
                                    depthImage.width, depthImage.height,
                                    depthPlane.rowStride,
                                    intrArr, cpuDim[0], cpuDim[1]
                                )
                            }
                        } catch (e: NotYetAvailableException) {
                        } catch (e: Exception) {}
                    }
                }
            }

            if (currentScanMode == ArScanMode.CLOUD_POINTS) {
                planeRenderer.drawPlanes(activeSession, viewMatrix, projMatrix, camera.pose)
                pointCloudRenderer.draw(viewMatrix, projMatrix)
            } else {
                slamManager.draw()
            }

            if (overlayBitmapDirty) {
                overlayBitmapDirty = false
                pendingOverlayBitmap?.let { overlayRenderer.updateTexture(it) }
            }

            val anchorMatrix = slamManager.getAnchorTransform()
            overlayRenderer.draw(viewMatrix, projMatrix, anchorMatrix)

            if (showAnchorBoundary) {
                overlayRenderer.drawAnchorBorder(viewMatrix, projMatrix, anchorMatrix)
            }
        }
    }

    fun destroy() {
        backgroundScope.cancel("Renderer detached and destroyed.")
        sessionLock.withLock {
            session = null
        }
    }
}