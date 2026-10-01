package com.hereliesaz.graffitixr.feature.ar

import android.content.Context
import com.hereliesaz.graffitixr.common.model.Fingerprint
import com.hereliesaz.graffitixr.common.model.WallFeatureMap
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.hereliesaz.graffitixr.common.sensor.CameraIntrinsics
import com.hereliesaz.graffitixr.common.sensor.CameraIntrinsicsEstimator
import com.hereliesaz.graffitixr.feature.ar.anchor.CaptureRotation
import com.hereliesaz.graffitixr.feature.ar.rendering.ProjectionMatrix
import com.hereliesaz.graffitixr.feature.ar.util.RotationDeltaMath
import com.hereliesaz.graffitixr.feature.ar.anchor.StandaloneFingerprintFrame
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import com.hereliesaz.sphereslam.SphereSlamCalibration
import com.hereliesaz.sphereslam.SphereSlamStandaloneSession
import java.nio.ByteBuffer
import timber.log.Timber

/**
 * Display-oriented reference image consumed by the standalone SphereSLAM CameraX path.
 *
 * [referenceWidthMeters] may be a measured physical width or a normalized renderer width. The
 * initial no-ARCore UI deliberately uses a normalized width of 1.0 and sets [physicallyMetric] false:
 * visual wall registration is then self-consistent, while the app does not pretend it knows a real
 * tape-measure distance that was never measured.
 */
data class SphereSlamStandaloneReferenceImage(
    val luma: ByteArray,
    val width: Int,
    val height: Int,
    val referenceWidthMeters: Float = 1f,
    val physicallyMetric: Boolean = false,
) {
    init {
        require(width > 0 && height > 0)
        require(luma.size == width * height)
        require(referenceWidthMeters.isFinite() && referenceWidthMeters > 0f)
    }
}

enum class SphereSlamStandalonePoseSource {
    KPM,
    IMU_BRIDGE,
}

data class SphereSlamStandaloneFrame(
    val viewMatrix: FloatArray,
    val projMatrix: FloatArray,
    val frameAspect: Float,
    val frameHeightPixels: Int,
    val timestampNs: Long,
    val pageNo: Int,
    val reprojectionError: Float,
    val inlierCount: Int,
    /** Wall/render units represented by one vertical display-frame pixel at the target centre. */
    val unitsPerPixel: Float,
    val observationAgeMs: Float?,
    val matchDurationMs: Float,
    val source: SphereSlamStandalonePoseSource,
) {
    init {
        require(viewMatrix.size == 16)
        require(projMatrix.size == 16)
    }
}

/**
 * CameraX ImageAnalysis -> calibrated KPM wall pose for phones that cannot run ARCore.
 *
 * This is deliberately synchronous on the ImageAnalysis executor: each callback either has a KPM
 * pose for THIS frame, a short IMU-bridged hold of the last good pose, or no pose. There is no
 * asynchronous "latest match" that can silently pair an old wall observation with a newer camera
 * frame.
 *
 * The IMU bridge is rotation-only and lasts at most [maxBridgeMs]. It applies the same camera-space
 * rotation delta to the view rotation and translation column, preserving the held camera centre
 * during a pure rotation. It never double-integrates accelerometer values or invents displacement.
 */
internal class SphereSlamStandaloneTrackingAnalyzer(
    private val context: Context,
    private val cameraId: String,
    private val referenceImage: SphereSlamStandaloneReferenceImage,
    private val slamManager: SlamManager? = null,
    private val mobileGsFingerprint: Fingerprint? = null,
    private val mobileGsFingerprintFrameVersion: Int =
        com.hereliesaz.graffitixr.common.model.SPHERE_SLAM_FINGERPRINT_FRAME_VERSION,
    private val mobileGsWallFeatureMap: WallFeatureMap? = null,
    private val mobileGsWallFeatureMapFrameVersion: Int =
        com.hereliesaz.graffitixr.common.model.SPHERE_SLAM_FINGERPRINT_FRAME_VERSION,
    private val onFrameTracked: (SphereSlamStandaloneFrame?) -> Unit,
    private val onReferenceReady: (SphereSlamStandaloneSession.Reference) -> Unit = {},
    private val onDiagnostic: (String) -> Unit = {},
    private val onCalibrationChanged: (StandaloneCalibrationDiagnostics) -> Unit = {},
    private val onFailure: (StandaloneFailureEvent) -> Unit = {},
    private val onTrackingStateChanged: (StandaloneTrackingState) -> Unit = {},
    private val onFatalError: (Throwable) -> Unit = {},
    private val poseAcceptancePolicy: StandalonePoseAcceptancePolicy =
        StandalonePoseAcceptancePolicy(),
    private val observationAgePolicy: StandaloneObservationAgePolicy =
        StandaloneObservationAgePolicy(),
    private val targetQualityConfig: StandaloneTargetQualityConfig =
        StandaloneTargetQualityConfig(),
    private val bridge: GyroOrientationBridge = GyroOrientationBridge(context),
    private val trackingStateMachine: StandaloneTrackingStateMachine =
        StandaloneTrackingStateMachine(),
    private val maxBridgeMs: Long = 400L,
) : ImageAnalysis.Analyzer, AutoCloseable {

    private data class DiagnosticKey(
        val rawWidth: Int,
        val rawHeight: Int,
        val cropLeft: Int,
        val cropTop: Int,
        val cropWidth: Int,
        val cropHeight: Int,
        val rotationDegrees: Int,
        val displayWidth: Int,
        val displayHeight: Int,
        val fx: Float,
        val fy: Float,
        val cx: Float,
        val cy: Float,
    )

    private data class SessionKey(
        val width: Int,
        val height: Int,
        val fx: Float,
        val fy: Float,
        val cx: Float,
        val cy: Float,
    )

    private val cameraTimestampSource: StandaloneCameraTimestampSource =
        resolveCameraTimestampSource(context, cameraId)

    private var session: SphereSlamStandaloneSession? = null
    private var sessionKey: SessionKey? = null
    private var lastDiagnosticKey: DiagnosticKey? = null
    private var lastPoseRejection: StandalonePoseRejection? = null
    private var lastFailureReason: StandaloneFailureReason? = null
    private var lastReportedTrackingState: StandaloneTrackingState? = null
    private var lastAcceptedVisualView: FloatArray? = null
    private var lastGood: SphereSlamStandaloneFrame? = null
    private var lastMetricsDiagnosticMs = Long.MIN_VALUE
    private var staleObservationReported = false
    private var frameBuffer: ByteBuffer? = null
    @Volatile private var closed = false
    @Volatile private var fatal = false

    fun start() {
        if (!closed) {
            bridge.start()
            reportTrackingState(trackingStateMachine.state)
        }
    }

    override fun analyze(image: ImageProxy) {
        try {
            if (closed || fatal) return
            val planes = image.planes
            if (planes.isEmpty()) return
            val y = planes[0]
            val rawWidth = image.width
            val rawHeight = image.height
            val rotationDeg = image.imageInfo.rotationDegrees
            val crop = image.cropRect
            if (crop.width() <= 0 || crop.height() <= 0) return

            val rotated = LumaFrameTransform.packCropAndRotate(
                source = y.buffer,
                sourceWidth = rawWidth,
                sourceHeight = rawHeight,
                rowStride = y.rowStride,
                pixelStride = y.pixelStride,
                cropLeft = crop.left,
                cropTop = crop.top,
                cropWidth = crop.width(),
                cropHeight = crop.height(),
                rotationDegrees = rotationDeg,
            )

            val rawIntrinsics = CameraIntrinsicsEstimator.estimate(
                context,
                cameraId,
                rawWidth,
                rawHeight,
            ) ?: run {
                reportFailure(
                    StandaloneFailureClassifier.event(
                        StandaloneFailureReason.INTRINSICS_UNAVAILABLE,
                        "camera=$cameraId raw=${rawWidth}x${rawHeight}",
                    ),
                )
                return
            }
            val croppedIntrinsics = cropCameraIntrinsics(
                intrinsics = rawIntrinsics,
                cropLeft = crop.left,
                cropTop = crop.top,
                cropWidth = crop.width(),
                cropHeight = crop.height(),
            )
            val ri = CaptureRotation.rotateIntrinsics(
                croppedIntrinsics.fx,
                croppedIntrinsics.fy,
                croppedIntrinsics.cx,
                croppedIntrinsics.cy,
                croppedIntrinsics.width.toFloat(),
                croppedIntrinsics.height.toFloat(),
                rotationDeg,
            )
            val intrinsics = CameraIntrinsics(
                fx = ri[0],
                fy = ri[1],
                cx = ri[2],
                cy = ri[3],
                width = rotated.width,
                height = rotated.height,
            )
            emitCalibrationDiagnosticIfChanged(
                rawWidth = rawWidth,
                rawHeight = rawHeight,
                cropLeft = crop.left,
                cropTop = crop.top,
                cropWidth = crop.width(),
                cropHeight = crop.height(),
                rotationDegrees = rotationDeg,
                intrinsics = intrinsics,
            )
            slamManager?.setLiveIntrinsics(
                intrinsics.fx,
                intrinsics.fy,
                intrinsics.cx,
                intrinsics.cy,
            )

            val active = ensureSession(intrinsics)
            val pendingImuReference = bridge.captureReferenceCandidate(rotationDeg)
            val timestampNs = image.imageInfo.timestamp
            val projection = ProjectionMatrix.buildFrom(intrinsics)
            val direct = directFrame(rotated.bytes)

            val matchStartNs = android.os.SystemClock.elapsedRealtimeNanos()
            val pose = active.match(direct, timestampNs)
            val matchEndNs = android.os.SystemClock.elapsedRealtimeNanos()
            val matchDurationMs = (matchEndNs - matchStartNs).toFloat() / 1_000_000f
            val observationAge = observationAgePolicy.evaluate(
                frameTimestampNs = timestampNs,
                nowElapsedRealtimeNs = matchEndNs,
                source = cameraTimestampSource,
            )

            if (pose != null) {
                if (observationAge.stale) {
                    staleObservationReported = true
                    reportFailure(
                        StandaloneFailureClassifier.event(
                            StandaloneFailureReason.STALE_OBSERVATION,
                            "ageMs=${observationAge.ageMs} matchMs=$matchDurationMs",
                        ),
                    )
                    feedMobileGsFrame(direct, rotated, timestampNs, projection, null)
                    publishVisualMiss(projection, rotated, timestampNs)
                    return
                }
                staleObservationReported = false
                val acceptance = poseAcceptancePolicy.evaluate(
                    viewMatrix = pose.viewMatrix,
                    inlierCount = pose.inlierCount,
                    reprojectionError = pose.reprojectionError,
                    previousViewMatrix = lastAcceptedVisualView,
                    referenceWidthUnits = pose.reference.geometry.widthMeters,
                    // Once the short visual bridge has expired, the next legitimate wall return may
                    // be far from the previous camera pose. Keep continuity gates deliberately
                    // looser for that explicit reacquisition case.
                    reacquiring = trackingStateMachine.state == StandaloneTrackingState.REACQUIRING ||
                        trackingStateMachine.state == StandaloneTrackingState.LOST,
                )
                if (!acceptance.accepted) {
                    val rejection = acceptance.rejection
                    if (rejection != null) {
                        lastPoseRejection = rejection
                        reportFailure(
                            StandaloneFailureClassifier.fromPoseRejection(
                                rejection,
                                "page=${pose.pageNo} inliers=${pose.inlierCount} " +
                                    "error=${pose.reprojectionError}",
                            ),
                        )
                    }
                    feedMobileGsFrame(direct, rotated, timestampNs, projection, null)
                    publishVisualMiss(projection, rotated, timestampNs)
                    return
                }

                lastPoseRejection = null
                lastFailureReason = null
                lastAcceptedVisualView = pose.viewMatrix.copyOf()
                val state = trackingStateMachine.onAcceptedVisual(android.os.SystemClock.elapsedRealtime())
                reportTrackingState(state)
                feedMobileGsFrame(
                    direct,
                    rotated,
                    timestampNs,
                    projection,
                    pose.viewMatrix,
                )
                if (state != StandaloneTrackingState.LOCKED) {
                    onFrameTracked(null)
                    return
                }

                pendingImuReference?.let(bridge::commitReference)
                val tracked = SphereSlamStandaloneFrame(
                    viewMatrix = pose.viewMatrix,
                    projMatrix = projection,
                    frameAspect = rotated.width.toFloat() / rotated.height.toFloat(),
                    frameHeightPixels = rotated.height,
                    timestampNs = timestampNs,
                    pageNo = pose.pageNo,
                    reprojectionError = pose.reprojectionError,
                    inlierCount = pose.inlierCount,
                    unitsPerPixel = unitsPerPixel(
                        pose.viewMatrix,
                        projection,
                        rotated.height,
                    ),
                    observationAgeMs = observationAge.ageMs,
                    matchDurationMs = matchDurationMs,
                    source = SphereSlamStandalonePoseSource.KPM,
                )
                lastGood = tracked
                emitMatchMetricsIfDue(tracked)
                onFrameTracked(tracked)
                return
            }

            feedMobileGsFrame(direct, rotated, timestampNs, projection, null)
            reportFailure(
                StandaloneFailureClassifier.event(
                    StandaloneFailureReason.NO_CURRENT_PAGE_MATCH,
                    "matchMs=$matchDurationMs frameTimestampNs=$timestampNs",
                ),
            )
            publishVisualMiss(projection, rotated, timestampNs)
        } catch (t: Throwable) {
            if (!closed) {
                fatal = true
                Timber.e(t, "Standalone SphereSLAM analyzer failed")
                reportFailure(StandaloneFailureClassifier.fromThrowable(t))
                reportTrackingState(trackingStateMachine.onFatal())
                onFrameTracked(null)
                onFatalError(t)
            }
        } finally {
            image.close()
        }
    }

    private fun publishVisualMiss(
        projection: FloatArray,
        frame: RotatedLuma,
        timestampNs: Long,
    ) {
        val bridged = bridgeLastGood(projection, frame, timestampNs)
        val state = trackingStateMachine.onVisualMiss(
            bridgeAvailable = bridged != null,
            nowMs = android.os.SystemClock.elapsedRealtime(),
        )
        reportTrackingState(state)
        onFrameTracked(if (state == StandaloneTrackingState.IMU_BRIDGE) bridged else null)
    }

    private fun reportFailure(event: StandaloneFailureEvent) {
        if (event.reason == lastFailureReason) return
        lastFailureReason = event.reason
        onFailure(event)
        onDiagnostic(
            "SphereSLAM standalone failure=${event.reason} severity=${event.severity}" +
                if (event.diagnostic.isBlank()) "" else " ${event.diagnostic}",
        )
    }

    private fun reportTrackingState(state: StandaloneTrackingState) {
        if (state == lastReportedTrackingState) return
        lastReportedTrackingState = state
        onTrackingStateChanged(state)
        onDiagnostic("SphereSLAM standalone state=$state")
    }

    private fun emitMatchMetricsIfDue(frame: SphereSlamStandaloneFrame) {
        val nowMs = android.os.SystemClock.elapsedRealtime()
        if (lastMetricsDiagnosticMs != Long.MIN_VALUE && nowMs - lastMetricsDiagnosticMs < 5_000L) {
            return
        }
        lastMetricsDiagnosticMs = nowMs
        val age = frame.observationAgeMs?.let {
            java.lang.String.format(java.util.Locale.US, "%.1f", it)
        } ?: "unavailable"
        onDiagnostic(
            "SphereSLAM standalone KPM page=${frame.pageNo} inliers=${frame.inlierCount} " +
                "error=${frame.reprojectionError} ageMs=$age " +
                "matchMs=${java.lang.String.format(java.util.Locale.US, "%.1f", frame.matchDurationMs)}",
        )
    }

    private fun emitCalibrationDiagnosticIfChanged(
        rawWidth: Int,
        rawHeight: Int,
        cropLeft: Int,
        cropTop: Int,
        cropWidth: Int,
        cropHeight: Int,
        rotationDegrees: Int,
        intrinsics: CameraIntrinsics,
    ) {
        val key = DiagnosticKey(
            rawWidth = rawWidth,
            rawHeight = rawHeight,
            cropLeft = cropLeft,
            cropTop = cropTop,
            cropWidth = cropWidth,
            cropHeight = cropHeight,
            rotationDegrees = rotationDegrees,
            displayWidth = intrinsics.width,
            displayHeight = intrinsics.height,
            fx = intrinsics.fx,
            fy = intrinsics.fy,
            cx = intrinsics.cx,
            cy = intrinsics.cy,
        )
        if (key == lastDiagnosticKey) return
        lastDiagnosticKey = key
        onDiagnostic(
            java.lang.String.format(
                java.util.Locale.US,
                "SphereSLAM standalone camera=%s timestampSource=%s raw=%dx%d crop=(%d,%d %dx%d) display=%dx%d rot=%d fx=%.2f fy=%.2f cx=%.2f cy=%.2f",
                cameraId,
                cameraTimestampSource.name,
                rawWidth,
                rawHeight,
                cropLeft,
                cropTop,
                cropWidth,
                cropHeight,
                intrinsics.width,
                intrinsics.height,
                rotationDegrees,
                intrinsics.fx,
                intrinsics.fy,
                intrinsics.cx,
                intrinsics.cy,
            ),
        )
        onCalibrationChanged(
            StandaloneCalibrationDiagnostics(
                cameraId = cameraId,
                timestampSource = cameraTimestampSource,
                rawWidth = rawWidth,
                rawHeight = rawHeight,
                cropLeft = cropLeft,
                cropTop = cropTop,
                cropWidth = cropWidth,
                cropHeight = cropHeight,
                displayWidth = intrinsics.width,
                displayHeight = intrinsics.height,
                rotationDegrees = rotationDegrees,
                fx = intrinsics.fx,
                fy = intrinsics.fy,
                cx = intrinsics.cx,
                cy = intrinsics.cy,
            ),
        )
    }

    private fun ensureSession(intrinsics: CameraIntrinsics): SphereSlamStandaloneSession {
        val key = SessionKey(
            intrinsics.width,
            intrinsics.height,
            intrinsics.fx,
            intrinsics.fy,
            intrinsics.cx,
            intrinsics.cy,
        )
        val existing = session
        if (existing != null && key == sessionKey) return existing

        existing?.close()
        bridge.clearReference()
        lastGood = null

        val created = SphereSlamStandaloneSession(
            frameWidth = intrinsics.width,
            frameHeight = intrinsics.height,
            calibration = SphereSlamCalibration(
                intrinsics.fx,
                intrinsics.fy,
                intrinsics.cx,
                intrinsics.cy,
            ),
        )
        try {
            val refBuffer = ByteBuffer.allocateDirect(referenceImage.luma.size).apply {
                put(referenceImage.luma)
                flip()
            }
            val reference = created.addReference(
                luma = refBuffer,
                width = referenceImage.width,
                height = referenceImage.height,
                referenceWidthMeters = referenceImage.referenceWidthMeters,
                physicallyMetric = referenceImage.physicallyMetric,
            )
            if (reference.featureCount < targetQualityConfig.minKpmFeatures) {
                throw StandaloneReferenceTooWeakException(
                    featureCount = reference.featureCount,
                    minimumFeatureCount = targetQualityConfig.minKpmFeatures,
                )
            }
            onDiagnostic(
                "SphereSLAM standalone reference features=" + reference.featureCount +
                    " minimum=" + targetQualityConfig.minKpmFeatures,
            )
            configureMobileGs(intrinsics)
            session = created
            sessionKey = key
            onReferenceReady(reference)
            return created
        } catch (t: Throwable) {
            created.close()
            throw t
        }
    }

    private fun configureMobileGs(intrinsics: CameraIntrinsics) {
        val slam = slamManager ?: return
        slam.ensureInitialized()
        slam.setTrackingPoseValid(false)
        slam.setLiveIntrinsics(intrinsics.fx, intrinsics.fy, intrinsics.cx, intrinsics.cy)

        val fp = mobileGsFingerprint
        val expectedFrameVersion =
            com.hereliesaz.graffitixr.common.model.SPHERE_SLAM_FINGERPRINT_FRAME_VERSION
        if (fp != null && mobileGsFingerprintFrameVersion != expectedFrameVersion) {
            slam.clearWallFingerprint()
            slam.clearWallFeatureMap()
            slam.overlayMarkCenterLocal = null
            slam.captureAnchorCam = null
            onDiagnostic(
                "SphereSLAM MobileGS seed refused backend=standalone-kpm " +
                    "frame=centered-page version=" + mobileGsFingerprintFrameVersion +
                    " expected=" + expectedFrameVersion,
            )
            return
        }
        if (fp == null || fp.descriptorsRows <= 0 || fp.points3d.size != fp.descriptorsRows * 3) {
            // Native MobileGS is process-global. A standalone project with no seed must explicitly
            // clear a prior project's fingerprint AND its wide-wall map rather than continuing to
            // match coordinates from the previous project's frame.
            slam.clearWallFingerprint()
            slam.clearWallFeatureMap()
            slam.overlayMarkCenterLocal = null
            slam.captureAnchorCam = null
            return
        }

        slam.restoreWallFingerprintMetric(
            descriptorsData = fp.descriptorsData,
            rows = fp.descriptorsRows,
            cols = fp.descriptorsCols,
            type = fp.descriptorsType,
            points3d = fp.points3d.toFloatArray(),
            anchorMatrix = StandaloneFingerprintFrame.anchorFromFingerprint(),
            intrinsics = floatArrayOf(intrinsics.fx, intrinsics.fy, intrinsics.cx, intrinsics.cy),
            // Deliberately empty: the optional native rectifier interprets this as an ARCore
            // capture-camera view. Standalone points already live in the durable page/wall frame.
            viewMatrix = FloatArray(0),
            regions = ByteArray(0),
        )
        slam.overlayMarkCenterLocal =
            fp.markCenterLocal.takeIf { it.size == 3 }?.toFloatArray()
        slam.captureAnchorCam = null

        val map = mobileGsWallFeatureMap
        val mapFrameOk =
            mobileGsWallFeatureMapFrameVersion == expectedFrameVersion &&
                map != null &&
                StandaloneFingerprintFrame.isCenteredPageAnchor(map.anchor)
        if (mapFrameOk) {
            val compatibleMap = requireNotNull(map)
            slam.restoreWallFeatureMap(compatibleMap)
            onDiagnostic(
                "SphereSLAM MobileGS map restored backend=standalone-kpm frame=centered-page " +
                    "version=" + mobileGsWallFeatureMapFrameVersion +
                    " points=" + compatibleMap.pointCount,
            )
        } else {
            // Empty is normal for a new project. A non-empty incompatible map is deliberately
            // dropped rather than converted: no implicit ARCore-world -> centred-page transform
            // exists, and guessing one would poison wide-area relocalization after restart.
            slam.clearWallFeatureMap()
            if (map != null) {
                onDiagnostic(
                    "SphereSLAM MobileGS map refused backend=standalone-kpm frame=centered-page " +
                        "version=" + mobileGsWallFeatureMapFrameVersion +
                        " expected=" + expectedFrameVersion +
                        " anchorIdentity=" + StandaloneFingerprintFrame.isCenteredPageAnchor(map.anchor),
                )
            }
        }
        onDiagnostic(
            "SphereSLAM MobileGS seed backend=standalone-kpm frame=centered-page " +
                "version=" + mobileGsFingerprintFrameVersion +
                " rows=" + fp.descriptorsRows + " type=" + fp.descriptorsType,
        )
    }

    private fun feedMobileGsFrame(
        luma: ByteBuffer,
        frame: RotatedLuma,
        timestampNs: Long,
        projection: FloatArray,
        acceptedView: FloatArray?,
    ) {
        val slam = slamManager ?: return
        // This flag describes the freshness of updateCamera(), not which backend produced it.
        // A rejected/missing KPM observation must invalidate the previous view before the same
        // frame's pixels enter MobileGS, otherwise rectification or future pose-dependent helpers
        // can silently pair a stale pose with a new image.
        slam.setTrackingPoseValid(acceptedView != null)
        if (mobileGsFingerprint == null) return

        // PnP does not need a pose prior. Publish a view only when KPM accepted THIS frame; on a
        // visual miss, feeding the pixels without a new camera pose is safer than labelling stale
        // pose state as current.
        if (acceptedView != null) {
            slam.updateCamera(acceptedView, projection, timestampNs)
        }
        val copy = luma.duplicate()
        copy.rewind()
        slam.feedLumaFrame(copy, frame.width, frame.height, timestampNs)
    }

    private fun bridgeLastGood(
        projection: FloatArray,
        frame: RotatedLuma,
        timestampNs: Long,
    ): SphereSlamStandaloneFrame? {
        val held = lastGood ?: return null
        val elapsed = bridge.msSinceReference()
        if (elapsed < 0L || elapsed > maxBridgeMs) {
            lastGood = null
            return null
        }
        val delta = bridge.cameraRotationDelta() ?: return null
        return held.copy(
            viewMatrix = rotateViewPoseKeepingCameraCenter(held.viewMatrix, delta),
            projMatrix = projection,
            frameAspect = frame.width.toFloat() / frame.height.toFloat(),
            frameHeightPixels = frame.height,
            timestampNs = timestampNs,
            observationAgeMs = held.observationAgeMs,
            matchDurationMs = held.matchDurationMs,
            source = SphereSlamStandalonePoseSource.IMU_BRIDGE,
        )
    }

    private fun unitsPerPixel(
        viewMatrix: FloatArray,
        projection: FloatArray,
        frameHeightPixels: Int,
    ): Float {
        if (frameHeightPixels <= 0 || projection[5] == 0f) return 0f
        val depth = kotlin.math.abs(viewMatrix[14])
        val tanHalfFovY = 1f / projection[5]
        return depth * 2f * tanHalfFovY / frameHeightPixels.toFloat()
    }

    private fun directFrame(bytes: ByteArray): ByteBuffer {
        var buffer = frameBuffer
        if (buffer == null || buffer.capacity() < bytes.size) {
            buffer = ByteBuffer.allocateDirect(bytes.size)
            frameBuffer = buffer
        }
        buffer.clear()
        buffer.put(bytes)
        buffer.flip()
        return buffer
    }

    private fun resolveCameraTimestampSource(
        context: Context,
        cameraId: String,
    ): StandaloneCameraTimestampSource = runCatching {
        val manager = context.getSystemService(CameraManager::class.java)
        when (
            manager.getCameraCharacteristics(cameraId)
                .get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)
        ) {
            CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME ->
                StandaloneCameraTimestampSource.REALTIME
            else -> StandaloneCameraTimestampSource.UNKNOWN
        }
    }.getOrDefault(StandaloneCameraTimestampSource.UNKNOWN)

    override fun close() {
        if (closed) return
        closed = true
        slamManager?.setTrackingPoseValid(false)
        bridge.stop()
        bridge.clearReference()
        lastGood = null
        session?.close()
        session = null
        sessionKey = null
        lastDiagnosticKey = null
        lastPoseRejection = null
        lastFailureReason = null
        lastReportedTrackingState = null
        trackingStateMachine.reset()
        lastAcceptedVisualView = null
        lastMetricsDiagnosticMs = Long.MIN_VALUE
        staleObservationReported = false
        frameBuffer = null
    }
}

internal fun rotateViewPoseKeepingCameraCenter(
    viewMatrix: FloatArray,
    deltaCameraRowMajor: FloatArray,
): FloatArray {
    require(viewMatrix.size == 16)
    require(deltaCameraRowMajor.size == 9)

    val rotation = FloatArray(9)
    for (row in 0..2) {
        for (col in 0..2) {
            rotation[row * 3 + col] = viewMatrix[col * 4 + row]
        }
    }
    val translation = floatArrayOf(viewMatrix[12], viewMatrix[13], viewMatrix[14])
    val rotatedR = RotationDeltaMath.multiplyMat3(deltaCameraRowMajor, rotation)
    val rotatedT = RotationDeltaMath.multiplyMat3Vec3(deltaCameraRowMajor, translation)

    val out = FloatArray(16)
    for (row in 0..2) {
        for (col in 0..2) {
            out[col * 4 + row] = rotatedR[row * 3 + col]
        }
    }
    out[12] = rotatedT[0]
    out[13] = rotatedT[1]
    out[14] = rotatedT[2]
    out[15] = 1f
    return out
}
