// FILE: feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/rendering/ArRenderer.kt
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
import com.hereliesaz.graffitixr.common.model.MuralMethod
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
    private val onTrackingUpdated: (Boolean, Int, Int, Boolean, Float, Float, Triple<Float, Float, Float>?, Boolean, Float, Float, Float) -> Unit,
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

    @Volatile var scanMode: ArScanMode = ArScanMode.MURAL
    @Volatile var muralMethod: MuralMethod = MuralMethod.VOXEL_HASH
    @Volatile var showAnchorBoundary: Boolean = false
    @Volatile var showBorderForConfirmation: Boolean = false
    @Volatile var anchorEstablished: Boolean = false
    /** When true the SLAM/cloud visualization is suppressed — processing continues but nothing is drawn. */
    @Volatile var hideVisualization: Boolean = false
    var stereoProvider: com.hereliesaz.graffitixr.nativebridge.depth.StereoDepthProvider? = null

    @Volatile private var isFlashlightRequested: Boolean = false

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

    @Volatile var captureRequested: Boolean = false
    @Volatile var isCapturingTarget: Boolean = false
    @Volatile var isInPlaneRealignment: Boolean = false

    @Volatile var exportRequested: Boolean = false
    var onExportCaptured: ((Bitmap) -> Unit)? = null

    // Pre-allocated buffers for Surface Mesh updates (32x32 grid)
    private val meshVerticesBuffer = FloatArray(32 * 32 * 3)
    private val meshWeightsBuffer = FloatArray(32 * 32)

    fun attachSession(session: Session?) {
        sessionLock.withLock {
            this.session = session
            if (session != null) {
                displayRotationHelper.onResume()
                if (isSurfaceCreated) {
                    session.setCameraTextureName(backgroundRenderer.textureId)
                }

                // Apply queued flashlight state immediately upon attachment
                applyFlashlightState()

                try {
                    val cameraId = session.cameraConfig.cameraId
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
        // Border marks the detected anchor region (small, derived from depth).
        overlayRenderer.setBorderExtent(halfW, halfH)
        // Image quad is always large so artwork is never spatially confined.
        overlayRenderer.setExtent(OverlayRenderer.QUAD_HALF_EXTENT, OverlayRenderer.QUAD_HALF_EXTENT)
    }

    fun updateFlashlight(isOn: Boolean) {
        isFlashlightRequested = isOn
        applyFlashlightState()
    }

    private fun applyFlashlightState() {
        val activeSession = session ?: return
        try {
            val config = activeSession.config
            val newMode = if (isFlashlightRequested) Config.FlashMode.TORCH else Config.FlashMode.OFF
            if (config.flashMode != newMode) {
                config.flashMode = newMode
                activeSession.configure(config)
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to set flash mode via ARCore Config")
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
        backgroundRenderer.createOnGlThread(context)

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
        frameCount++

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

            val mappingViewMatrix = FloatArray(16)
            val mappingProjMatrix = FloatArray(16)
            camera.pose.inverse().toMatrix(mappingViewMatrix, 0)

            val intrinsics = camera.imageIntrinsics
            val focalLength = intrinsics.focalLength
            val principalPoint = intrinsics.principalPoint
            val dims = intrinsics.imageDimensions

            mappingProjMatrix[0] = 2.0f * focalLength[0] / dims[0]
            mappingProjMatrix[5] = 2.0f * focalLength[1] / dims[1]
            mappingProjMatrix[8] = 2.0f * principalPoint[0] / dims[0] - 1.0f
            mappingProjMatrix[9] = 1.0f - 2.0f * principalPoint[1] / dims[1]
            mappingProjMatrix[10] = -(100.1f) / (99.9f)
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

            val yawRad = kotlin.math.atan2(-viewMatrix[2].toDouble(), -viewMatrix[10].toDouble())
            val yawDeg = Math.toDegrees(yawRad).toFloat()

            slamManager.setArCoreTrackingState(isTracking)

            val currentScanMode = scanMode
            slamManager.setArScanMode(currentScanMode.ordinal)
            slamManager.setMuralMethod(muralMethod.ordinal)
            val anchorMatrix = slamManager.getAnchorTransform()

            // Throttle UI and distance updates to 15Hz to match SLAM processing frequency and reduce state churn.
            if (frameCount % 4 == 0) {
                backgroundScope.launch {
                    val (count, immutableCount) = if (currentScanMode == ArScanMode.CLOUD_POINTS) {
                        pointCloudRenderer.accumulatedPointCount to 0
                    } else {
                        slamManager.getSplatCount() to slamManager.getImmutableSplatCount()
                    }

                    val visConf = slamManager.getVisibleConfidenceAvg()
                    val globConf = slamManager.getGlobalConfidenceAvg()
                    val isDualLens = stereoProvider?.isDualLensActive == true

                    var centerDepth = -1f
                    try {
                        frame.acquireDepthImage16Bits().use { depthImage ->
                            val plane = depthImage.planes[0]
                            val cx = depthImage.width / 2
                            val cy = depthImage.height / 2
                            val stride = plane.rowStride
                            val offset = cy * stride + cx * 2
                            if (offset + 2 <= plane.buffer.limit()) {
                                val raw = plane.buffer.getShort(offset).toInt() and 0xFFFF
                                centerDepth = (raw and 0x1FFF) / 1000f
                            }
                        }
                    } catch (e: Exception) { /* ignore */ }

                    var relDir: Triple<Float, Float, Float>? = null
                    val distanceMeters = run {
                        if (!anchorEstablished) return@run -1f
                        val camPose = FloatArray(16)
                        android.opengl.Matrix.invertM(camPose, 0, viewMatrix, 0)
                        val dx = anchorMatrix[12] - camPose[12]
                        val dy = anchorMatrix[13] - camPose[13]
                        val dz = anchorMatrix[14] - camPose[14]
                        val len = kotlin.math.sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()

                        if (len > 0.01f) {
                            val localX = dx * viewMatrix[0] + dy * viewMatrix[1] + dz * viewMatrix[2]
                            val localY = dx * viewMatrix[4] + dy * viewMatrix[5] + dz * viewMatrix[6]
                            val localZ = dx * viewMatrix[8] + dy * viewMatrix[9] + dz * viewMatrix[10]
                            relDir = Triple(localX / len, localY / len, localZ / len)
                        }

                        val fwdDot = dx * (-viewMatrix[8]) + dy * (-viewMatrix[9]) + dz * (-viewMatrix[10])
                        if (len > 0.01f && fwdDot > 0f) len else -1f
                    }

                    onTrackingUpdated(isTracking, count, immutableCount, depthSupported, yawDeg, distanceMeters, relDir, isDualLens, centerDepth, visConf, globConf)
                }
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
                                    val buf = ByteBuffer.allocateDirect(plane.buffer.remaining())
                                    buf.put(plane.buffer)
                                    buf.rewind()
                                    depthBuffer = buf
                                    depthWidth = depthImage.width
                                    depthHeight = depthImage.height
                                    depthStride = plane.rowStride
                                }
                            } catch (e: Exception) {
                                Timber.w(e, "Failed to acquire depth for capture")
                            }
                        }

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

            if (exportRequested) {
                exportRequested = false
                try {
                    frame.acquireCameraImage().use { image ->
                        val rgbaBuffer = ImageProcessingUtils.convertYuvToRgbaDirect(image)
                        val bitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
                        bitmap.copyPixelsFromBuffer(rgbaBuffer)

                        val displayDegrees = displayRotationHelper.getRotation() * 90
                        val rotationNeeded = (sensorOrientation - displayDegrees + 360) % 360

                        val rotatedBitmap = if (rotationNeeded != 0) {
                            val matrix = android.graphics.Matrix()
                            matrix.postRotate(rotationNeeded.toFloat())
                            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also {
                                bitmap.recycle()
                            }
                        } else {
                            bitmap
                        }

                        onExportCaptured?.invoke(rotatedBitmap)
                        onExportCaptured = null
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Failed to capture export frame")
                }
            }

            // Throttle frame feeding to 15Hz (every 4 frames at 60Hz) to halve processing-related power draw.
            // When tracking is stable and the device is stationary, we could throttle even further.
            // ── Frame Data Pipeline (Throttle to 20Hz or 2Hz for Battery Efficiency) ──
            val throttleRate = if (anchorEstablished) 30 else 3 // 2Hz vs 20Hz
            if (isTracking && frameCount % throttleRate == 0) {
                // Calculate rotation code to align sensor-native data with display orientation
                val displayRotation = displayRotationHelper.getRotation()
                val cvRotateCode = when ((sensorOrientation - displayRotation * 90 + 360) % 360) {
                    90 -> 0 // cv::ROTATE_90_CLOCKWISE
                    180 -> 1 // cv::ROTATE_180
                    270 -> 2 // cv::ROTATE_90_COUNTERCLOCKWISE
                    else -> -1
                }

                try {
                    frame.acquireCameraImage().use { image ->
                        val planes = image.planes
                        slamManager.feedYuvFrame(
                            planes[0].buffer, planes[1].buffer, planes[2].buffer,
                            image.width, image.height,
                            planes[0].rowStride, planes[1].rowStride, planes[1].pixelStride,
                            frame.timestamp,
                            cvRotateCode
                        )
                        // Feed temporal stereo ONLY when mapping
                        if (!anchorEstablished) {
                            stereoProvider?.submitFrame(planes[0].buffer, image.width, image.height, frame.timestamp)
                        }
                    }
                } catch (e: com.google.ar.core.exceptions.NotYetAvailableException) {
                    // Normal on first frames
                } catch (e: Exception) {
                    Timber.w(e, "Failed to feed YUV frame")
                }

                // 1. Point Cloud acquisition (only when scanning in CLOUD_POINTS mode)
                if (currentScanMode == ArScanMode.CLOUD_POINTS && !anchorEstablished) {
                    try {
                        frame.acquirePointCloud().use { pointCloud ->
                            pointCloudRenderer.update(pointCloud)
                        }
                    } catch (e: Exception) {
                        Timber.w(e, "Failed to acquire point cloud")
                    }
                }

                // 2. Depth acquisition (STOP processing depth once target is baked)
                if (depthSupported && !anchorEstablished) {
                    try {
                        frame.acquireDepthImage16Bits().use { depthImage ->
                            val depthPlane = depthImage.planes[0]
                            val intrArr = floatArrayOf(
                                intrinsics.focalLength[0], intrinsics.focalLength[1],
                                intrinsics.principalPoint[0], intrinsics.principalPoint[1]
                            )
                            val cpuDim = intrinsics.imageDimensions

                            slamManager.feedArCoreDepth(
                                depthPlane.buffer,
                                depthImage.width, depthImage.height,
                                depthPlane.rowStride,
                                intrArr, cpuDim[0], cpuDim[1],
                                cvRotateCode
                            )
                        }
                    } catch (e: com.google.ar.core.exceptions.NotYetAvailableException) {
                        // Normal on first frames
                    } catch (e: Exception) {
                        Timber.w(e, "Failed to feed depth frame")
                    }
                }
            }

            // Continuous wall-depth refinement: keep the overlay flush with the ARCore plane estimate.
            // Runs at ~1 Hz (every 30 frames) to amortise getAllTrackables() overhead.
            // Only runs BEFORE the anchor is established or during manual realignment to
            // prevent the image from following the camera gaze once locked to a target.
            if ((!anchorEstablished || isInPlaneRealignment) && frameCount % 30 == 0) {
                try {
                    refineAnchorFromBestPlane(activeSession, viewMatrix)
                } catch (e: Exception) {
                    // Non-fatal: skip this refinement cycle
                }
            }

            // Depth-based fallback: sample centre-screen depth every 10 frames
            if ((!anchorEstablished || isInPlaneRealignment) && depthSupported && frameCount % 10 == 0) {
                try {
                    frame.acquireDepthImage16Bits().use { depthImage ->
                        val plane = depthImage.planes[0]
                        val cx = depthImage.width / 2
                        val cy = depthImage.height / 2
                        val stride = plane.rowStride
                        val byteOffset = cy * stride + cx * 2
                        if (byteOffset + 2 <= plane.buffer.limit()) {
                            val rawVal = plane.buffer.getShort(byteOffset).toInt() and 0xFFFF
                            val depthMm = rawVal and 0x1FFF
                            if (depthMm in 100..15000) {   // 10 cm – 15 m
                                val depthM = depthMm / 1000f
                                val cameraMat = FloatArray(16)
                                android.opengl.Matrix.invertM(cameraMat, 0, viewMatrix, 0)
                                val hitX = cameraMat[12] + (-cameraMat[8]) * depthM
                                val hitY = cameraMat[13] + (-cameraMat[9]) * depthM
                                val hitZ = cameraMat[14] + (-cameraMat[10]) * depthM
                                val nx = -cameraMat[8]; val ny = -cameraMat[9]; val nz = -cameraMat[10]
                                var xx = 0f * nz - 1f * ny
                                var xy = 1f * nx - 0f * nz
                                var xz = 0f * ny - 0f * nx
                                val xLen = kotlin.math.sqrt((xx * xx + xy * xy + xz * xz).toDouble()).toFloat()
                                if (xLen > 0.0001f) {
                                    xx /= xLen; xy /= xLen; xz /= xLen
                                    val yx = ny * xz - nz * xy; val yy = nz * xx - nx * xz; val yz = nx * xy - ny * xx
                                    val depthAnchor = FloatArray(16)
                                    android.opengl.Matrix.setIdentityM(depthAnchor, 0)
                                    depthAnchor[0] = xx; depthAnchor[1] = xy; depthAnchor[2] = xz
                                    depthAnchor[4] = yx; depthAnchor[5] = yy; depthAnchor[6] = yz
                                    depthAnchor[8] = nx; depthAnchor[9] = ny; depthAnchor[10] = nz
                                    depthAnchor[12] = hitX; depthAnchor[13] = hitY; depthAnchor[14] = hitZ
                                    slamManager.updateAnchorTransform(depthAnchor)
                                }
                            }
                        }
                    }
                } catch (_: com.google.ar.core.exceptions.NotYetAvailableException) {
                } catch (e: Exception) { /* Non-fatal */ }
            }

            if (!hideVisualization) {
                val showDiagnostics = !anchorEstablished
                if (showDiagnostics) {
                    if (currentScanMode == ArScanMode.CLOUD_POINTS) {
                        planeRenderer.drawPlanes(activeSession, viewMatrix, projMatrix, camera.pose)
                        pointCloudRenderer.draw(viewMatrix, projMatrix)
                    } else {
                        slamManager.draw()
                    }
                }
            }

            if (overlayBitmapDirty) {
                overlayBitmapDirty = false
                val bmp = pendingOverlayBitmap
                if (bmp != null) overlayRenderer.updateTexture(bmp) else overlayRenderer.clearTexture()
            }

            val hasMeshData = if (scanMode == ArScanMode.MURAL && muralMethod == MuralMethod.SURFACE_MESH) {
                slamManager.getPersistentMesh(meshVerticesBuffer, meshWeightsBuffer)
                true
            } else false

            overlayRenderer.draw(viewMatrix, projMatrix, anchorMatrix, 
                if (hasMeshData) meshVerticesBuffer else null,
                if (hasMeshData) meshWeightsBuffer else null
            )

            val showBorder = !anchorEstablished && !isCapturingTarget &&
                (showAnchorBoundary || (showBorderForConfirmation && !overlayRenderer.hasTexture))
            if (showBorder) {
                overlayRenderer.drawAnchorBorder(viewMatrix, projMatrix, anchorMatrix)
            }
        }
    }

    /**
     * Continuously refines the overlay anchor by finding the best VERTICAL ARCore plane
     * in the camera's forward direction and updating the SLAM anchor transform to match it.
     * Called at ~1 Hz from onDrawFrame to keep the overlay flush with the wall as ARCore
     * refines its plane estimates over time.
     */
    private fun refineAnchorFromBestPlane(
        session: Session,
        viewMatrix: FloatArray
    ) {
        // Extract camera world position and forward vector from the view matrix
        val cameraMat = FloatArray(16)
        android.opengl.Matrix.invertM(cameraMat, 0, viewMatrix, 0)
        val camX = cameraMat[12]; val camY = cameraMat[13]; val camZ = cameraMat[14]
        val fwdX = -cameraMat[8]; val fwdY = -cameraMat[9]; val fwdZ = -cameraMat[10]

        // Find the plane most directly ahead of the camera (unbiased search)
        val planes = session.getAllTrackables(com.google.ar.core.Plane::class.java)
        var bestPlane: com.google.ar.core.Plane? = null
        var maxDot = 0.4f

        for (plane in planes) {
            if (plane.trackingState != TrackingState.TRACKING) continue
            
            val pose = plane.centerPose
            val dx = pose.tx() - camX
            val dy = pose.ty() - camY
            val dz = pose.tz() - camZ
            val len = kotlin.math.sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()
            if (len < 0.3f || len > 15f) continue
            
            val dot = (dx * fwdX + dy * fwdY + dz * fwdZ) / len
            if (dot > maxDot) { 
                maxDot = dot
                bestPlane = plane 
            }
        }

        val plane = bestPlane ?: return

        val planeMatrix = FloatArray(16)
        plane.centerPose.toMatrix(planeMatrix, 0)
        val nx = planeMatrix[4]; val ny = planeMatrix[5]; val nz = planeMatrix[6]  // plane normal (Y col)

        // Ray–plane intersection: where does the camera's forward ray hit this plane?
        val nDotD = nx * fwdX + ny * fwdY + nz * fwdZ
        if (kotlin.math.abs(nDotD) < 0.0001f) return   // Ray parallel to plane
        val t = ((planeMatrix[12] - camX) * nx +
                 (planeMatrix[13] - camY) * ny +
                 (planeMatrix[14] - camZ) * nz) / nDotD
        if (t < 0.1f) return   // Intersection behind camera

        val hitX = camX + fwdX * t
        val hitY = camY + fwdY * t
        val hitZ = camZ + fwdZ * t

        // Build an orthonormal anchor frame: Z = plane normal, X = horizontal, Y = up
        val zx = nx; val zy = ny; val zz = nz
        var refX = 0f; var refY = 1f; var refZ = 0f
        if (kotlin.math.abs(zy) > 0.9f) { refX = 1f; refY = 0f; refZ = 0f }
        
        var xx = refY * zz - refZ * zy
        var xy = refZ * zx - refX * zz
        var xz = refX * zy - refY * zx
        val xLen = kotlin.math.sqrt((xx * xx + xy * xy + xz * xz).toDouble()).toFloat()
        if (xLen < 0.0001f) return   // Degenerate
        xx /= xLen; xy /= xLen; xz /= xLen
        val yx = zy * xz - zz * xy   // Y = Z × X
        val yy = zz * xx - zx * xz
        val yz = zx * xy - zy * xx

        val anchorMat = FloatArray(16)
        android.opengl.Matrix.setIdentityM(anchorMat, 0)
        anchorMat[0] = xx;   anchorMat[1] = xy;   anchorMat[2] = xz
        anchorMat[4] = yx;   anchorMat[5] = yy;   anchorMat[6] = yz
        anchorMat[8] = zx;   anchorMat[9] = zy;   anchorMat[10] = zz
        anchorMat[12] = hitX; anchorMat[13] = hitY; anchorMat[14] = hitZ

        slamManager.updateAnchorTransform(anchorMat)
    }

    fun destroy() {
        backgroundScope.cancel("Renderer detached and destroyed.")
        sessionLock.withLock {
            session = null
        }
    }
}