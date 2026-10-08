@file:OptIn(com.hereliesaz.sphereslam.reloc.ExperimentalSphereSlamRelocApi::class)

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
import com.hereliesaz.graffitixr.feature.ar.anchor.MetricMarks
import com.hereliesaz.graffitixr.feature.ar.anchor.PoseFusion
import com.hereliesaz.graffitixr.feature.ar.rendering.ProjectionMatrix
import com.hereliesaz.graffitixr.feature.ar.util.RotationDeltaMath
import com.hereliesaz.graffitixr.feature.ar.anchor.StandaloneFingerprintFrame
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import com.hereliesaz.sphereslam.SphereSlamCalibration
import com.hereliesaz.sphereslam.SphereSlamStandaloneSession
import com.hereliesaz.sphereslam.reloc.RobustTrackingLoop
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

data class SphereSlamStandaloneAtlasReferenceImage(
    val pageNo: Int,
    val luma: ByteArray,
    val width: Int,
    val height: Int,
    val referenceWidthMeters: Float,
    val physicallyMetric: Boolean,
    val canonicalFromPage: FloatArray,
) {
    init {
        require(pageNo > 0)
        require(width > 0 && height > 0 && luma.size == width * height)
        require(referenceWidthMeters.isFinite() && referenceWidthMeters > 0f)
        require(canonicalFromPage.size == 16 && canonicalFromPage.all { it.isFinite() })
    }
}

enum class SphereSlamStandalonePoseSource {
    KPM,
    IMU_BRIDGE,
    /** MobileGS PnP against a protocol-v3 co-op peer fingerprint. */
    PEER_FINGERPRINT,
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

data class SphereSlamPhotosphereKeyframe(
    val timestampNs: Long,
    val luma: ByteArray,
    val width: Int,
    val height: Int,
    val intrinsics: FloatArray,
    val headingDeg: Float,
    val elevationDeg: Float,
    val orientationQuaternion: FloatArray?,
) {
    init {
        require(width > 0 && height > 0 && luma.size == width * height)
        require(intrinsics.size == 4 && intrinsics.all { it.isFinite() })
        require(headingDeg.isFinite() && elevationDeg.isFinite())
        require(orientationQuaternion == null || orientationQuaternion.size == 4)
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
    @Volatile private var referenceImage: SphereSlamStandaloneReferenceImage?,
    atlasReferenceImages: List<SphereSlamStandaloneAtlasReferenceImage> = emptyList(),
    private val slamManager: SlamManager? = null,
    @Volatile private var mobileGsFingerprint: Fingerprint? = null,
    @Volatile private var mobileGsFingerprintFrameVersion: Int =
        com.hereliesaz.graffitixr.common.model.SPHERE_SLAM_FINGERPRINT_FRAME_VERSION,
    @Volatile private var mobileGsWallFeatureMap: WallFeatureMap? = null,
    @Volatile private var mobileGsWallFeatureMapFrameVersion: Int =
        com.hereliesaz.graffitixr.common.model.SPHERE_SLAM_FINGERPRINT_FRAME_VERSION,
    private val onFrameTracked: (SphereSlamStandaloneFrame?) -> Unit,
    private val cameraAttitude: () -> Pair<Float, Float>? = { null },
    private val onPhotosphereKeyframe: (SphereSlamPhotosphereKeyframe) -> Unit = {},
    private val onReferenceReady: (SphereSlamStandaloneSession.Reference) -> Unit = {},
    private val onAtlasPageAdded: (StandaloneAtlasGrowthCandidate) -> Unit = {},
    // Phase 1b of the spherical-coverage map (docs/SPHERESLAM_SPHERE_MAP.md): the device bearing at
    // each atlas-growth keyframe. Defaults to a no-op, so this records nothing until the view model
    // opts in by wiring it — storage only, no tracking/reloc behavior change.
    private val onKeyframeOrientation: (Long, FloatArray) -> Unit = { _, _ -> },
    // Phase 2 of the spherical-coverage map: optional monocular depth source. When non-null AND loaded,
    // the analyzer estimates per-keyframe depth on the live frame and stashes it in the native engine
    // for depth-calibrated radial map-point placement. Null (the default, and the classic path) means
    // no depth is ever stashed, so the native map builds from wall-plane back-projection exactly as
    // before. The view model supplies this only on the standalone path with the feature-map flag on.
    private val depthEstimator: com.hereliesaz.graffitixr.feature.ar.depth.DepthEstimator? = null,
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
    private var lastGood: SphereSlamStandaloneFrame? = null

    // The library's fused per-frame loop (age -> acceptance -> correction -> smoothing -> state),
    // rebuilt per session in [ensureSession] with the wall reference's metric width. It owns the
    // acceptance policy, age policy, stabilizer and state machine; the proprietary reloc corroboration
    // rides its correctAcceptedPose hook, gating on the raw KPM pose and displaying the corrected one.
    // Bridging stays here (richer per-frame bridge frame), so no bridgeRotatedPose callback is wired.
    private var robustLoop: RobustTrackingLoop? = null
    private var lastMetricsDiagnosticMs = Long.MIN_VALUE
    private var staleObservationReported = false

    // Temporal low-pass for the emitted render pose (damps per-frame KPM jitter; see
    // StandalonePoseStabilizer). Reset wherever lastGood is cleared so it never blends across a
    // session rebuild or a tracking-loss discontinuity.
    private val poseStabilizer = StandalonePoseStabilizer()

    // Sequence of the last MobileGS reloc result consumed by fuseWithReloc, so a given relocalization
    // is evaluated once. 0 = none seen (the native no-result sentinel).
    private var lastRelocSeq = 0f
    private var frameBuffer: ByteBuffer? = null
    private val runtimeAtlasPages = linkedMapOf<Int, SphereSlamStandaloneAtlasReferenceImage>().apply {
        atlasReferenceImages.sortedBy { it.pageNo }.forEach { page ->
            put(
                page.pageNo,
                page.copy(
                    luma = page.luma.copyOf(),
                    canonicalFromPage = page.canonicalFromPage.copyOf(),
                ),
            )
        }
    }
    private var lastAtlasGrowthMs = Long.MIN_VALUE
    private var lastPhotosphereKeyframeMs = Long.MIN_VALUE
    // Phase 2: throttle MiDaS inference to a keyframe cadence (not per-frame — ORT CPU inference is
    // too expensive for 30fps and the map builds per-keyframe anyway).
    private var lastDepthMs = Long.MIN_VALUE
    @Volatile private var closed = false
    @Volatile private var fatal = false

    fun start() {
        if (!closed) {
            bridge.start()
            reportTrackingState(trackingStateMachine.state)
        }
    }

    /**
     * Hot-swap only the fingerprint/teleological enrichment layer.
     *
     * The CameraX analyzer, gyro sampling, and base photosphere remain alive. Rebuilding the planar
     * KPM sub-session here is allowed because it is an enrichment consumer of the base map, not the
     * owner of SphereSLAM's runtime lifecycle.
     */
    @Synchronized
    fun updatePrecisionLayer(
        reference: SphereSlamStandaloneReferenceImage?,
        atlasPages: List<SphereSlamStandaloneAtlasReferenceImage>,
        fingerprint: Fingerprint?,
        fingerprintFrameVersion: Int,
        wallFeatureMap: WallFeatureMap?,
        wallFeatureMapFrameVersion: Int,
    ) {
        if (closed) return

        val referenceChanged = referenceImage !== reference
        val fingerprintChanged = mobileGsFingerprint !== fingerprint ||
            mobileGsFingerprintFrameVersion != fingerprintFrameVersion
        val mapChanged = mobileGsWallFeatureMap !== wallFeatureMap ||
            mobileGsWallFeatureMapFrameVersion != wallFeatureMapFrameVersion

        referenceImage = reference
        mobileGsFingerprint = fingerprint
        mobileGsFingerprintFrameVersion = fingerprintFrameVersion
        mobileGsWallFeatureMap = wallFeatureMap
        mobileGsWallFeatureMapFrameVersion = wallFeatureMapFrameVersion

        runtimeAtlasPages.clear()
        atlasPages.sortedBy { it.pageNo }.forEach { page ->
            runtimeAtlasPages[page.pageNo] = page.copy(
                luma = page.luma.copyOf(),
                canonicalFromPage = page.canonicalFromPage.copyOf(),
            )
        }

        if (referenceChanged || fingerprintChanged || mapChanged) {
            session?.close()
            session = null
            sessionKey = null
            robustLoop = null
            lastGood = null
            poseStabilizer.reset()
            bridge.clearReference()
            lastRelocSeq = 0f
            // Deliberately DO NOT touch lastPhotosphereKeyframeMs or any photosphere state here.
            onDiagnostic(
                "SphereSLAM precision layer updated reference=" + (reference != null) +
                    " fingerprint=" + (fingerprint != null) +
                    "; base photosphere preserved"
            )
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

            val pendingImuReference = bridge.captureReferenceCandidate(rotationDeg)
            val timestampNs = image.imageInfo.timestamp
            val projection = ProjectionMatrix.buildFrom(intrinsics)
            val direct = directFrame(rotated.bytes)

            // SphereSLAM is the base runtime. Its photosphere updates for the whole standalone
            // session, before and after fingerprint creation. The fingerprint/teleological layer can
            // enrich this map, but never owns its lifetime.
            capturePhotosphereKeyframe(rotated, intrinsics, timestampNs)
            if (referenceImage == null) {
                slamManager?.setTrackingPoseValid(false)
                onFrameTracked(null)
                return
            }

            val active = ensureSession(intrinsics)
            val matchStartNs = android.os.SystemClock.elapsedRealtimeNanos()
            val pose = active.match(direct, timestampNs)
            val matchEndNs = android.os.SystemClock.elapsedRealtimeNanos()
            val matchDurationMs = (matchEndNs - matchStartNs).toFloat() / 1_000_000f
            // Read here for the frame metric/diagnostic; the loop re-checks age internally to gate
            // staleness, using this same policy instance, so the two verdicts agree.
            val observationAge = observationAgePolicy.evaluate(
                frameTimestampNs = timestampNs,
                nowElapsedRealtimeNs = matchEndNs,
                source = cameraTimestampSource,
            )

            // Single per-frame decision: age -> acceptance (on the raw KPM pose) -> correction
            // (fuseWithReloc, display only) -> smoothing -> state hysteresis. Bridging is handled
            // below, caller-side, so the loop is told whether a bridge is available but renders none.
            val loop = requireNotNull(robustLoop) { "robust tracking loop not initialised" }
            val outcome = loop.onFrame(
                viewMatrix = pose?.cameraFromCanonical,
                inlierCount = pose?.inlierCount ?: 0,
                reprojectionError = pose?.reprojectionError ?: Float.MAX_VALUE,
                frameTimestampNs = timestampNs,
                nowElapsedRealtimeNs = matchEndNs,
                timestampSource = cameraTimestampSource,
                nowMs = android.os.SystemClock.elapsedRealtime(),
                bridgeAvailable = canBridge(),
            )

            // MobileGS always gets the frame; it sees the RAW accepted KPM pose when one locked this
            // frame, else null — unchanged by the correction, which only affects the displayed pose.
            val acceptedRawView = if (outcome.accepted) pose!!.cameraFromCanonical else null
            feedMobileGsFrame(direct, rotated, timestampNs, projection, acceptedRawView)

            if (outcome.accepted) {
                // The loop accepts only a non-null candidate, so a pose is present here.
                val lockedPose = requireNotNull(pose)
                staleObservationReported = false
                lastPoseRejection = null
                lastFailureReason = null
                reportTrackingState(outcome.state)
                if (outcome.state != StandaloneTrackingState.LOCKED) {
                    onFrameTracked(null)
                    return
                }

                pendingImuReference?.let(bridge::commitReference)
                maybeGrowAtlas(
                    active = active,
                    frame = rotated,
                    pose = lockedPose,
                    intrinsics = intrinsics,
                )
                maybePushDepth(rotated)
                // outcome.renderPose = stabilize(fuseWithReloc(rawKpm)): the raw pose nudged toward a
                // corroborating MobileGS reloc (drift trim), then smoothed. Gating, the atlas and the
                // MobileGS feed all used the RAW pose — only the displayed pose is fused.
                val renderView = outcome.renderPose!!
                val tracked = SphereSlamStandaloneFrame(
                    viewMatrix = renderView,
                    projMatrix = projection,
                    frameAspect = rotated.width.toFloat() / rotated.height.toFloat(),
                    frameHeightPixels = rotated.height,
                    timestampNs = timestampNs,
                    pageNo = lockedPose.pageNo,
                    reprojectionError = lockedPose.reprojectionError,
                    inlierCount = lockedPose.inlierCount,
                    unitsPerPixel = unitsPerPixel(
                        renderView,
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

            // Not accepted: classify why for the failure taxonomy, then emit the bridge frame or null
            // per the loop's state decision. (Capture into a local val so the null check smart-casts.)
            val rejection = outcome.rejection
            when {
                outcome.stale -> {
                    staleObservationReported = true
                    reportFailure(
                        StandaloneFailureClassifier.event(
                            StandaloneFailureReason.STALE_OBSERVATION,
                            "ageMs=${observationAge.ageMs} matchMs=$matchDurationMs",
                        ),
                    )
                }
                rejection != null -> {
                    lastPoseRejection = rejection
                    reportFailure(
                        StandaloneFailureClassifier.fromPoseRejection(
                            rejection,
                            "page=${pose?.pageNo} inliers=${pose?.inlierCount} " +
                                "error=${pose?.reprojectionError}",
                        ),
                    )
                }
                else -> {
                    reportFailure(
                        StandaloneFailureClassifier.event(
                            StandaloneFailureReason.NO_CURRENT_PAGE_MATCH,
                            "matchMs=$matchDurationMs frameTimestampNs=$timestampNs",
                        ),
                    )
                }
            }
            publishVisualMiss(outcome.state, projection, rotated, timestampNs)
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

    // The state is already decided by RobustTrackingLoop (which ran onVisualMiss internally with the
    // [canBridge] availability we gave it). Here we only surface it and emit the matching frame: a
    // gyro-bridged pose while IMU_BRIDGE holds, otherwise null and the held frame is forgotten so a
    // re-lock snaps rather than drifting from a stale anchor.
    private fun publishVisualMiss(
        state: StandaloneTrackingState,
        projection: FloatArray,
        frame: RotatedLuma,
        timestampNs: Long,
    ) {
        reportTrackingState(state)
        if (state == StandaloneTrackingState.IMU_BRIDGE) {
            onFrameTracked(bridgeLastGood(projection, frame, timestampNs))
        } else {
            lastGood = null
            onFrameTracked(null)
        }
    }

    /**
     * Whether a gyro bridge can still hold a brief visual miss: a prior good frame exists, the IMU
     * reference is within [maxBridgeMs], and a rotation delta is available. Pure — no state change
     * (the loop owns the stabilizer reset; [publishVisualMiss] owns clearing [lastGood]).
     */
    private fun canBridge(): Boolean {
        if (lastGood == null) return false
        val elapsed = bridge.msSinceReference()
        if (elapsed < 0L || elapsed > maxBridgeMs) return false
        return bridge.cameraRotationDelta() != null
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

    private fun capturePhotosphereKeyframe(
        frame: RotatedLuma,
        intrinsics: CameraIntrinsics,
        timestampNs: Long,
    ) {
        val attitude = cameraAttitude() ?: return
        val nowMs = android.os.SystemClock.elapsedRealtime()
        if (
            lastPhotosphereKeyframeMs != Long.MIN_VALUE &&
            nowMs - lastPhotosphereKeyframeMs < PHOTOSPHERE_KEYFRAME_INTERVAL_MS
        ) return
        lastPhotosphereKeyframeMs = nowMs

        val quality = StandaloneTargetQuality.analyze(frame.bytes, frame.width, frame.height)
        if (StandaloneTargetQuality.blockingMessage(quality) != null) {
            return
        }

        onPhotosphereKeyframe(
            SphereSlamPhotosphereKeyframe(
                timestampNs = timestampNs,
                luma = frame.bytes.copyOf(),
                width = frame.width,
                height = frame.height,
                intrinsics = floatArrayOf(
                    intrinsics.fx,
                    intrinsics.fy,
                    intrinsics.cx,
                    intrinsics.cy,
                ),
                headingDeg = attitude.first,
                elevationDeg = attitude.second,
                orientationQuaternion = bridge.latestOrientationSample(),
            )
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
        poseStabilizer.reset()
        lastRelocSeq = 0f

        val rootReference = requireNotNull(referenceImage) {
            "planar SphereSLAM session requested before fingerprint/reference exists"
        }
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
            val refBuffer = ByteBuffer.allocateDirect(rootReference.luma.size).apply {
                put(rootReference.luma)
                flip()
            }
            val registeredReference = created.addReference(
                luma = refBuffer,
                width = rootReference.width,
                height = rootReference.height,
                referenceWidthMeters = rootReference.referenceWidthMeters,
                physicallyMetric = rootReference.physicallyMetric,
            )
            if (registeredReference.featureCount < targetQualityConfig.minKpmFeatures) {
                throw StandaloneReferenceTooWeakException(
                    featureCount = registeredReference.featureCount,
                    minimumFeatureCount = targetQualityConfig.minKpmFeatures,
                )
            }
            onDiagnostic(
                "SphereSLAM standalone reference features=" + registeredReference.featureCount +
                    " minimum=" + targetQualityConfig.minKpmFeatures,
            )

            val stablePages = runtimeAtlasPages.values.sortedBy { it.pageNo }
            require(stablePages.map { it.pageNo }.distinct().size == stablePages.size) {
                "SphereSLAM atlas contains duplicate page IDs"
            }
            stablePages.forEach { page ->
                val pageBuffer = ByteBuffer.allocateDirect(page.luma.size).apply {
                    put(page.luma)
                    flip()
                }
                val added = created.addReference(
                    luma = pageBuffer,
                    width = page.width,
                    height = page.height,
                    referenceWidthMeters = page.referenceWidthMeters,
                    physicallyMetric = page.physicallyMetric,
                    pageNo = page.pageNo,
                    canonicalFromPage = page.canonicalFromPage,
                )
                onDiagnostic(
                    "SphereSLAM atlas page=" + page.pageNo +
                        " features=" + added.featureCount +
                        " frame=canonical-centered-page",
                )
            }
            configureMobileGs(intrinsics)
            // Rebuild the fused loop for this session. referenceWidthUnits scales the acceptance
            // translation-jump limit; the primary wall reference's metric width is the right scale
            // (grown atlas pages share the wall's scale). The loop owns the (freshly reset above)
            // stabilizer and the state machine; the proprietary corroboration rides correctAcceptedPose.
            robustLoop = RobustTrackingLoop(
                referenceWidthUnits = registeredReference.geometry.widthMeters,
                acceptancePolicy = poseAcceptancePolicy,
                agePolicy = observationAgePolicy,
                stabilizer = poseStabilizer,
                stateMachine = trackingStateMachine,
                correctAcceptedPose = ::fuseWithReloc,
            )
            session = created
            sessionKey = key
            onReferenceReady(registeredReference)
            return created
        } catch (t: Throwable) {
            created.close()
            throw t
        }
    }

    /**
     * Phase 2 of the spherical-coverage map: estimate monocular depth on the live keyframe and stash
     * it in the native engine for depth-calibrated radial map-point placement. Throttled to a keyframe
     * cadence. Entirely optional and fail-soft: with no [depthEstimator] (the classic path), or when
     * the model is unavailable, nothing is stashed and the native map builds from the wall plane alone.
     */
    private fun maybePushDepth(frame: RotatedLuma) {
        val estimator = depthEstimator ?: return
        val slam = slamManager ?: return
        val now = android.os.SystemClock.elapsedRealtime()
        if (lastDepthMs != Long.MIN_VALUE && now - lastDepthMs < DEPTH_MIN_INTERVAL_MS) return
        lastDepthMs = now
        if (!estimator.isLoaded && !estimator.load()) return

        // MiDaS wants RGB; the live frame is luma. A gray bitmap is coarse but all this path needs —
        // depth here is downscaled and only calibrates a radius, not a texture.
        val w = frame.width
        val h = frame.height
        if (w <= 0 || h <= 0 || frame.bytes.size < w * h) return
        val pixels = IntArray(w * h)
        for (i in 0 until w * h) {
            val v = frame.bytes[i].toInt() and 0xFF
            pixels[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        val gray = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        gray.setPixels(pixels, 0, w, 0, 0, w, h)
        val depth = try {
            estimator.estimate(gray)
        } finally {
            gray.recycle()
        } ?: run {
            // Inference failed: clear any stale stashed depth so placement falls back to the wall plane.
            slam.setLatestDepthMap(null, 0, 0, 0, 0)
            return
        }
        slam.setLatestDepthMap(depth.data, depth.width, depth.height, w, h)
    }

    private fun maybeGrowAtlas(
        active: SphereSlamStandaloneSession,
        frame: RotatedLuma,
        pose: SphereSlamStandaloneSession.Pose,
        intrinsics: CameraIntrinsics,
    ) {
        if (
            runtimeAtlasPages.size + 1 >= StandaloneAtlasGrowth.MAX_PAGES ||
            pose.inlierCount < StandaloneAtlasGrowth.MIN_GROW_INLIERS ||
            pose.reprojectionError > StandaloneAtlasGrowth.MAX_GROW_REPROJECTION_ERROR
        ) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (
            lastAtlasGrowthMs != Long.MIN_VALUE &&
            now - lastAtlasGrowthMs < StandaloneAtlasGrowth.MIN_GROW_INTERVAL_MS
        ) return

        val rootReference = referenceImage ?: return
        val rootWidth = rootReference.referenceWidthMeters
        val rootHeight =
            rootWidth * rootReference.height.toFloat() / rootReference.width.toFloat()
        val existing = buildList {
            add(
                StandaloneAtlasPageWindow(
                    pageNo = 0,
                    centerX = 0f,
                    centerY = 0f,
                    width = rootWidth,
                    height = rootHeight,
                ),
            )
            runtimeAtlasPages.values.forEach { page ->
                add(
                    StandaloneAtlasPageWindow(
                        pageNo = page.pageNo,
                        centerX = page.canonicalFromPage[12],
                        centerY = page.canonicalFromPage[13],
                        width = page.referenceWidthMeters,
                        height =
                            page.referenceWidthMeters * page.height.toFloat() / page.width.toFloat(),
                    ),
                )
            }
        }
        val geometry = StandaloneAtlasGrowth.propose(
            cameraFromCanonicalOpenGl = pose.cameraFromCanonical,
            intrinsics = intrinsics,
            frameWidth = frame.width,
            frameHeight = frame.height,
            rootWidthUnits = rootWidth,
            rootHeightUnits = rootHeight,
            existingPages = existing,
        ) ?: return

        // Rectification/OpenCV is intentionally infrequent and stays on CameraX's analysis worker.
        // Mark the attempt now so a low-texture edge of the wall cannot trigger this cost every frame.
        lastAtlasGrowthMs = now
        val rectified = StandaloneAtlasGrowth.rectify(
            frameLuma = frame.bytes,
            frameWidth = frame.width,
            frameHeight = frame.height,
            geometry = geometry,
        ) ?: return
        val bitmap = rectified.first
        val luma = rectified.second
        val quality = StandaloneTargetQuality.analyze(luma, bitmap.width, bitmap.height)
        if (StandaloneTargetQuality.blockingMessage(quality) != null) {
            onDiagnostic(
                "SphereSLAM atlas growth skipped reason=target-quality " +
                    "center=(" + geometry.centerX + "," + geometry.centerY + ")",
            )
            return
        }

        val pageNo = (runtimeAtlasPages.keys.maxOrNull() ?: 0) + 1
        val canonicalFromPage =
            StandaloneAtlasGrowth.canonicalFromPage(geometry.centerX, geometry.centerY)
        val page = SphereSlamStandaloneAtlasReferenceImage(
            pageNo = pageNo,
            luma = luma,
            width = bitmap.width,
            height = bitmap.height,
            referenceWidthMeters = geometry.width,
            physicallyMetric = rootReference.physicallyMetric,
            canonicalFromPage = canonicalFromPage,
        )
        val buffer = ByteBuffer.allocateDirect(luma.size).apply {
            put(luma)
            flip()
        }
        val added = active.addReference(
            luma = buffer,
            width = page.width,
            height = page.height,
            referenceWidthMeters = page.referenceWidthMeters,
            physicallyMetric = page.physicallyMetric,
            pageNo = page.pageNo,
            canonicalFromPage = page.canonicalFromPage,
        )
        if (added.featureCount < targetQualityConfig.minKpmFeatures) {
            // KPM has no remove-page call. Rebuild on the next frame from the persisted/runtime set,
            // deliberately excluding this weak candidate.
            active.close()
            session = null
            sessionKey = null
            onDiagnostic(
                "SphereSLAM atlas growth rejected page=" + pageNo +
                    " features=" + added.featureCount +
                    " minimum=" + targetQualityConfig.minKpmFeatures,
            )
            return
        }

        runtimeAtlasPages[pageNo] = page
        onDiagnostic(
            "SphereSLAM atlas grew page=" + pageNo +
                " frame=canonical-centered-page center=(" +
                geometry.centerX + "," + geometry.centerY + ")" +
                " features=" + added.featureCount,
        )
        onAtlasPageAdded(
            StandaloneAtlasGrowthCandidate(
                pageNo = pageNo,
                bitmap = bitmap,
                luma = luma,
                referenceWidthUnits = page.referenceWidthMeters,
                physicallyMetric = page.physicallyMetric,
                canonicalFromPage = page.canonicalFromPage.copyOf(),
            ),
        )
        // Record the device bearing at this keyframe (the angular glue for the surrounding sphere).
        // Best-effort: skip silently when the sensor hasn't produced a sample.
        bridge.latestOrientationSample()?.let {
            onKeyframeOrientation(android.os.SystemClock.elapsedRealtimeNanos(), it)
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

    /**
     * Pull the raw KPM view toward the MobileGS relocalizer's independent estimate, but ONLY when
     * that estimate corroborates KPM — i.e. already agrees within [PoseFusion.diverged]'s thresholds.
     * This is the standalone drift corrector: a wide-baseline reloc that concurs with the drifting
     * planar KPM pose gently tightens it; a reloc that disagrees (stale during motion, a bad solve,
     * or — were the fingerprint/canonical-frame assumption ever to break — systematically off) is
     * ignored. So the loop can reduce accumulated drift but can never yank the overlay to a bad pose,
     * and it only ever blends, never snaps.
     *
     * `reloc[0..15]` is `camera_from_fingerprint` in the CV convention; the standalone fingerprint is
     * the centered-page (canonical wall) reference, so [MetricMarks.glViewToCv] (its own inverse)
     * maps it into the GL `camera_from_canonical` frame the KPM view already uses.
     */
    private fun fuseWithReloc(kpmView: FloatArray): FloatArray {
        val slam = slamManager ?: return kpmView
        val reloc = slam.getRelocResult()
        if (reloc.size < 19) return kpmView
        val seq = reloc[18]
        // Evaluate each relocalization once — accepted or not — mirroring PoseFusion's seq handling.
        if (seq <= 0f || seq == lastRelocSeq) return kpmView
        lastRelocSeq = seq
        val matches = reloc[17]
        val inliers = reloc[16]
        val inlierRatio = if (matches > 0f) inliers / matches else 0f
        if (inlierRatio < PoseFusion.MIN_INLIER_RATIO || inliers < RELOC_MIN_INLIERS) return kpmView
        val relocViewGl = MetricMarks.glViewToCv(reloc.copyOfRange(0, 16))
        if (!relocViewGl.all { it.isFinite() } || relocViewGl[15] != 1f) return kpmView
        // Trust the reloc only when it already corroborates the current KPM pose.
        if (PoseFusion.diverged(kpmView, relocViewGl)) return kpmView
        return PoseFusion.blend(kpmView, relocViewGl, RELOC_ALPHA)
    }

    private fun bridgeLastGood(
        projection: FloatArray,
        frame: RotatedLuma,
        timestampNs: Long,
    ): SphereSlamStandaloneFrame? {
        // Availability (held frame present, within maxBridgeMs, rotation delta exists) was already
        // confirmed by [canBridge] and reflected in the loop's IMU_BRIDGE decision; here we just build
        // the bridged frame. A null return (delta briefly unavailable) renders nothing this frame.
        val held = lastGood ?: return null
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
        poseStabilizer.reset()
        lastRelocSeq = 0f
        session?.close()
        session = null
        sessionKey = null
        lastDiagnosticKey = null
        lastPoseRejection = null
        lastFailureReason = null
        lastReportedTrackingState = null
        trackingStateMachine.reset()
        robustLoop = null
        lastMetricsDiagnosticMs = Long.MIN_VALUE
        staleObservationReported = false
        frameBuffer = null
        runtimeAtlasPages.clear()
    }

    private companion object {
        /** Photosphere keyframe cadence; visual mapping runs for the entire standalone session. */
        const val PHOTOSPHERE_KEYFRAME_INTERVAL_MS = 250L

        /** Phase 2: minimum interval between MiDaS depth inferences (keyframe cadence, not per-frame). */
        const val DEPTH_MIN_INTERVAL_MS = 500L

        /**
         * Minimum PnP inliers before a reloc is allowed to correct the pose, on top of the
         * [PoseFusion.MIN_INLIER_RATIO] ratio gate. A handful of inliers can satisfy a ratio by luck;
         * this floors the absolute evidence so only a well-supported solve nudges the overlay.
         */
        const val RELOC_MIN_INLIERS = 12f

        /**
         * Blend weight toward a corroborating reloc. Deliberately small: the reloc only ever fires
         * when it already agrees with KPM, so a gentle pull tightens accumulated drift without a
         * visible jump, and a slightly-off (but non-diverged) reloc can move the pose by at most this
         * fraction of an already-small gap.
         */
        const val RELOC_ALPHA = 0.15f
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
