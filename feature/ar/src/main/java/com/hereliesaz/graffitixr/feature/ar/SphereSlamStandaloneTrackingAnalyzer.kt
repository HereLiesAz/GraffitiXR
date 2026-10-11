@file:OptIn(com.hereliesaz.sphereslam.reloc.ExperimentalSphereSlamRelocApi::class)

package com.hereliesaz.graffitixr.feature.ar

import android.content.Context
import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.hereliesaz.graffitixr.common.model.Fingerprint
import com.hereliesaz.graffitixr.common.model.WallFeatureMap
import com.hereliesaz.graffitixr.common.util.NativeLibLoader
import com.hereliesaz.graffitixr.feature.ar.anchor.PoseFusion
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import com.hereliesaz.sphereslam.PhotosphereKeyframe
import com.hereliesaz.sphereslam.attitude.AttitudeExtrapolator
import com.hereliesaz.sphereslam.attitude.AttitudeSample
import com.hereliesaz.sphereslam.attitude.DeviceCameraRotation
import com.hereliesaz.sphereslam.attitude.DisplayPosePredictor
import com.hereliesaz.sphereslam.attitude.GameRotationAttitudeSource
import com.hereliesaz.sphereslam.camera.CameraIntrinsics
import com.hereliesaz.sphereslam.camera.CameraIntrinsicsEstimator
import com.hereliesaz.sphereslam.camera.CameraIntrinsicsTransforms
import com.hereliesaz.sphereslam.camera.CaptureRotation
import com.hereliesaz.sphereslam.camera.LumaFrameBufferPool
import com.hereliesaz.sphereslam.camera.RotatedLuma
import com.hereliesaz.sphereslam.math.RigidMath
import com.hereliesaz.sphereslam.reloc.PhotosphereTrackingSession
import com.hereliesaz.sphereslam.reloc.PoseBlend
import com.hereliesaz.sphereslam.reloc.VisualMatchMethod
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
    /** Projection of the full tracked frame (tracking-side metrics; not what the screen shows). */
    val projMatrix: FloatArray,
    /**
     * Intrinsics of the full display-upright tracked frame [projMatrix] was built from. The artwork
     * renderer re-derives its on-screen projection from these through the preview's `FILL_CENTER`
     * crop ([com.hereliesaz.sphereslam.camera.CameraFillCenter]).
     */
    val frameIntrinsics: CameraIntrinsics,
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
    /** How SphereSLAM solved the visual pose (null for sources without one, e.g. a co-op peer). */
    val matchMethod: VisualMatchMethod? = null,
) {
    init {
        require(viewMatrix.size == 16)
        require(projMatrix.size == 16)
    }
}

/** A wall atlas page the runtime grew, handed to the host for persistence. */
internal data class StandaloneAtlasGrowthCandidate(
    val pageNo: Int,
    val bitmap: Bitmap,
    val luma: ByteArray,
    val referenceWidthUnits: Float,
    val physicallyMetric: Boolean,
    val canonicalFromPage: FloatArray,
)

/**
 * CameraX ImageAnalysis -> calibrated KPM wall pose for phones that cannot run ARCore.
 *
 * The tracking runtime is SphereSLAM's [PhotosphereTrackingSession] (ported from this class): the
 * rotation-only base photosphere map and its keyframes, the planar KPM precision layer gated by
 * `RobustTrackingLoop`, the same-frame `map_from_fingerprint` solve, the <= [maxBridgeMs] gyro
 * bridge, multi-page atlas growth and the failure taxonomy. This class is the Android/app adapter
 * around it: CameraX frames (full Y plane, packed and rotated to display orientation), Camera2
 * characteristics (intrinsics, sensor orientation, timestamp source), the device attitude
 * ([attitudeSample] / [attitudeExtrapolator]: the host's `CameraAttitudeProvider`,
 * `TYPE_ROTATION_VECTOR` — the same sensor that stamps keyframes and drives the display-rate glow and
 * [displayPosePredictor]. The host wires it only when the device HAS that sensor; with none wired —
 * a phone without a rotation vector — the analyzer samples its own [GameRotationAttitudeSource] for
 * `Frame.attitude` (base photosphere pose and gyro bridge) and leaves the predictor unwired, because
 * a predictor must read the same sensor as `Frame.attitude`. The choice is made once per analyzer,
 * never per frame: mixing sensors between a bridge reference and a later frame would rotate the pose
 * by the yaw offset between two unrelated world frames), the native MobileGS relocalizer (seeded on every planar-session build, fed
 * each frame, and corroborating the accepted KPM pose through the session's `correctAcceptedPose`
 * hook), optional MiDaS depth for MobileGS, and the UI callbacks.
 *
 * Deliberately synchronous on the ImageAnalysis executor: each callback either has a KPM pose for
 * THIS frame, a short IMU-bridged hold of the last good pose, or no pose. There is no asynchronous
 * "latest match" that can silently pair an old wall observation with a newer camera frame. Since
 * SphereSLAM 0.24.3 the session builds and grows its KPM matcher on its own background thread and
 * swaps the result in at the start of a later frame; until the first build lands a frame carries
 * only the base photosphere pose (`FrameResult.pose == null`) — "no pose yet", not a failure. The
 * optional MiDaS depth is not a pose: it runs on its own drop-if-busy thread (see [maybePushDepth]) so
 * its inference (LiteRT GPU, or the slower CPU fallback) never stalls this executor.
 *
 * Frames are packed from the Y plane into a [LumaFrameBufferPool] (no ~300 KB allocation per frame).
 * Reuse contract: a pooled array is overwritten two frames later, and nothing here keeps one past
 * [analyze] — the session reads it without retaining it, MobileGS copies it into a direct buffer,
 * depth copies a small grid, and keyframes/atlas pages are the library's own copies.
 *
 * The body->camera rotation is [DeviceCameraRotation.cameraFromDevice] for this camera's sensor
 * orientation and the CameraX rotation applied to the tracked (display-upright) image — not
 * `rotationDegrees` itself.
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
    @Volatile private var mobileGsMapFromFingerprint: FloatArray? = null,
    @Volatile private var mobileGsWallFeatureMap: WallFeatureMap? = null,
    @Volatile private var mobileGsWallFeatureMapFrameVersion: Int =
        com.hereliesaz.graffitixr.common.model.SPHERE_SLAM_FINGERPRINT_FRAME_VERSION,
    private val onFrameTracked: (SphereSlamStandaloneFrame?) -> Unit,
    private val cameraAttitude: () -> Pair<Float, Float>? = { null },
    /**
     * Base photosphere keyframes (SphereSLAM [PhotosphereKeyframe]: display-upright luma, its
     * intrinsics, heading/elevation/roll and the rotation-only GL camera_from_map at capture).
     */
    private val onPhotosphereKeyframe: (PhotosphereKeyframe) -> Unit = {},
    /**
     * Roll of the tracked image's right axis above the horizon for a given body->camera rotation
     * (`CameraAttitudeProvider.cameraRollDegrees`), or null when unknown. Stamped on each keyframe so
     * a later fingerprint placement can rotate pixel rays correctly in any device orientation.
     */
    private val cameraRollDegrees: (cameraFromDevice: FloatArray) -> Float? = { null },
    /**
     * Solved on a frame where both base-map pose and KPM precision pose are known:
     * map_from_fingerprint = inverse(camera_from_map) * camera_from_fingerprint.
     */
    private val onMapFromFingerprintSolved: (FloatArray) -> Unit = {},
    private val onReferenceReady: (PhotosphereTrackingSession.RegisteredPage) -> Unit = {},
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
    private val depthEstimator: com.hereliesaz.sphereslam.models.DepthEstimator? = null,
    private val onDiagnostic: (String) -> Unit = {},
    private val onCalibrationChanged: (StandaloneCalibrationDiagnostics) -> Unit = {},
    private val onFailure: (StandaloneFailureEvent) -> Unit = {},
    private val onTrackingStateChanged: (StandaloneTrackingState) -> Unit = {},
    private val onFatalError: (Throwable) -> Unit = {},
    private val targetQualityConfig: StandaloneTargetQualityConfig =
        StandaloneTargetQualityConfig(),
    /**
     * Device attitude for every frame (`Frame.attitude`: base photosphere pose, gyro bridge, pose
     * prediction): the host's `CameraAttitudeProvider.latestSample()`. Null = the analyzer samples
     * its own `TYPE_GAME_ROTATION_VECTOR` and [displayPosePredictor] is not wired (a predictor must
     * read the same sensor as `Frame.attitude`). Pass null on a device without the host's sensor.
     */
    attitudeSample: (() -> AttitudeSample?)? = null,
    /**
     * The same host sensor's timestamped history (`CameraAttitudeProvider.attitudeExtrapolator`).
     * With a `REALTIME` camera timestamp source it supplies the attitude AT each frame's capture time
     * ([StandaloneFrameAttitude.atCapture]) rather than the latest sample at analyze() time, which is
     * what the gyro bridge and [DisplayPosePredictor.update] require. Ignored without [attitudeSample].
     */
    private val attitudeExtrapolator: AttitudeExtrapolator? = null,
    /**
     * Receives every rendered pose from the session for display-rate re-posing on the GL thread
     * (`HomographyOverlayRenderer`), cleared whenever a frame yields no pose. Ignored without
     * [attitudeSample].
     */
    displayPosePredictor: DisplayPosePredictor? = null,
    private val maxBridgeMs: Long = 400L,
) : ImageAnalysis.Analyzer, AutoCloseable {

    // Fallback only: no host attitude wired. Owned (started/stopped) by this analyzer.
    private val ownAttitudeSource: GameRotationAttitudeSource? =
        if (attitudeSample == null) GameRotationAttitudeSource(context) else null
    private val readAttitude: () -> AttitudeSample? =
        attitudeSample ?: { ownAttitudeSource?.latestSample() }
    private val captureAttitudeHistory: AttitudeExtrapolator? =
        attitudeExtrapolator.takeIf { attitudeSample != null }
    private val captureAttitudeScratch = FloatArray(4)

    // Pooled display-upright luma (SphereSLAM LumaFrameBufferPool); see the class doc for the reuse
    // contract. Two slots: the frame being analysed plus one of slack.
    private val lumaPool = LumaFrameBufferPool(slots = 2)

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

    private val cameraTimestampSource: StandaloneCameraTimestampSource =
        resolveCameraTimestampSource(context, cameraId)

    /** CameraCharacteristics.SENSOR_ORIENTATION of [cameraId] (a quarter turn; 90 when unknown). */
    private val sensorOrientationDeg: Int = resolveSensorOrientation(context, cameraId)

    private var lastDiagnosticKey: DiagnosticKey? = null
    private var lastFailureReason: StandaloneFailureReason? = null
    private var lastReportedTrackingState: StandaloneTrackingState? = null

    // Intrinsics of the frame currently inside session.onFrame — what the planar-session rebuild
    // (onReferenceReady) seeds MobileGS with, exactly as the frame's own session build did before.
    private var currentFrameIntrinsics: CameraIntrinsics? = null

    // Sequence of the last MobileGS reloc result consumed by fuseWithReloc, so a given relocalization
    // is evaluated once. 0 = none seen (the native no-result sentinel).
    private var lastRelocSeq = 0f
    private var frameBuffer: ByteBuffer? = null

    // The library session keys its precision-layer rebuild on reference identity, so each app
    // reference is converted once and the converted object reused while it stays current.
    private var convertedReferenceSource: SphereSlamStandaloneReferenceImage? = null
    private var convertedReference: PhotosphereTrackingSession.ReferenceImage? = null

    // Phase 2: MiDaS inference never runs on this analysis thread. Since SphereSLAM 0.24.3 it runs
    // on LiteRT's GPU delegate (with an XNNPACK CPU fallback that can take hundreds of ms), and
    // DepthEstimator blocks its caller until its own thread is done; inline, a slow run stalled
    // tracking and aged the next KPM observation past the session's observation-age gate
    // (STALE_OBSERVATION flicker). It runs on its own single thread, at most one
    // at a time (a request while one is in flight is dropped, never queued), on a view-tile-change /
    // atlas-growth cadence with a slow periodic refresh; the result is handed back and applied on
    // this thread at the start of the next analyze(). Created only when a depth estimator is wired.
    private val depthCadence = StandaloneDepthCadence()
    // Per-frame tracking stays synchronous (tools/check_sphereslam_architecture.py); this worker can
    // hold at most ONE depth job and never queues, so it adds no backlog behind the analyzer.
    private val depthWorker: DropIfBusyWorker<DepthOutcome>? =
        depthEstimator?.let { newStandaloneDepthWorker() }
    @Volatile private var closed = false
    @Volatile private var fatal = false

    // Diagnostics the session emits while this analyzer is still being constructed (the initial
    // precision layer) are not forwarded: the analyzer never reported its own construction.
    private var constructed = false

    private val session = PhotosphereTrackingSession(
        config = PhotosphereTrackingSession.Config(
            timestampSource = cameraTimestampSource,
            targetQuality = targetQualityConfig,
            maxBridgeMs = maxBridgeMs,
            keyframeIntervalMs = PHOTOSPHERE_KEYFRAME_INTERVAL_MS,
        ),
        callbacks = PhotosphereTrackingSession.Callbacks(
            onKeyframe = { keyframe ->
                depthCadence.onViewDirection(keyframe.headingDeg, keyframe.elevationDeg)
                onPhotosphereKeyframe(keyframe)
            },
            onMapFromFingerprintSolved = { mapFromFingerprint ->
                // Keep the MobileGS seed's bridge current at once, so a planar-session rebuild
                // seeds MobileGS before the asynchronous project write/reload completes.
                mobileGsMapFromFingerprint = mapFromFingerprint.copyOf()
                onMapFromFingerprintSolved(mapFromFingerprint)
            },
            onReferenceReady = { registered ->
                lastRelocSeq = 0f
                currentFrameIntrinsics?.let { configureMobileGs(it) }
                onReferenceReady(registered)
            },
            onAtlasPageAdded = { page, attitude ->
                depthCadence.onAtlasGrowth()
                onAtlasPageAdded(
                    StandaloneAtlasGrowthCandidate(
                        pageNo = page.pageNo,
                        bitmap = lumaBitmap(page.luma, page.width, page.height),
                        luma = page.luma,
                        referenceWidthUnits = page.referenceWidthUnits,
                        physicallyMetric = page.physicallyMetric,
                        canonicalFromPage = page.canonicalFromPage,
                    ),
                )
                // Record the device bearing at this keyframe (the angular glue for the surrounding
                // sphere). Best-effort: skip silently when the sensor hasn't produced a sample.
                // The library hands back the PROPOSING frame's Frame.attitude (growth is adopted
                // frames later); its timestamp is that frame's capture time when the capture-time
                // attitude was available (StandaloneFrameAttitude), else that sample's sensor time —
                // either way the proposing frame's, not this adoption's, elapsedRealtimeNanos base.
                attitude?.let { onKeyframeOrientation(it.timestampNs, it.quaternion) }
            },
            onFailure = { event -> reportFailure(event) },
            onTrackingStateChanged = { state -> reportTrackingState(state) },
            onDiagnostic = { text -> if (constructed && !isStateOrFailureLine(text)) onDiagnostic(text) },
            onFatalError = { error ->
                if (!closed) {
                    fatal = true
                    Timber.e(error, "Standalone SphereSLAM analyzer failed")
                    onFrameTracked(null)
                    onFatalError(error)
                }
            },
        ),
        // Single per-frame decision inside the session: age -> acceptance (on the raw KPM pose) ->
        // this correction (fuseWithReloc, display only) -> smoothing -> state hysteresis.
        correctAcceptedPose = ::fuseWithReloc,
    )

    init {
        // The session's frame-to-frame flow tracking (SphereSLAM 0.24.3) needs OpenCV's Java native
        // library loaded before the first onFrame; without it flow silently switches itself off and
        // every frame becomes a full KPM solve. GraffitiApplication already loads it; this idempotent
        // call makes the requirement local and visible. Fail-soft: tracking still works without it.
        runCatching { NativeLibLoader.loadAll() }
            .onFailure { Timber.w(it, "OpenCV not loaded; SphereSLAM flow tracking disabled") }
        // Same sensor as Frame.attitude, or not at all.
        session.displayPosePredictor = displayPosePredictor.takeIf { attitudeSample != null }
        session.updatePrecisionLayer(
            reference = libraryReference(referenceImage),
            atlasPages = atlasReferenceImages.map(::libraryAtlasPage),
            mapFromFingerprint = mobileGsMapFromFingerprint.validTransformOrNull(),
        )
        constructed = true
    }

    fun start() {
        if (!closed) {
            ownAttitudeSource?.start()
            reportTrackingState(session.trackingState)
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
        mapFromFingerprint: FloatArray?,
        wallFeatureMap: WallFeatureMap?,
        wallFeatureMapFrameVersion: Int,
    ) {
        if (closed) return

        // The MobileGS seed is part of the precision layer: a change to it rebuilds the planar
        // session too, so the next build re-seeds MobileGS (configureMobileGs).
        val fingerprintChanged = mobileGsFingerprint !== fingerprint ||
            mobileGsFingerprintFrameVersion != fingerprintFrameVersion ||
            !sameTransform(mobileGsMapFromFingerprint, mapFromFingerprint)
        val mapChanged = mobileGsWallFeatureMap !== wallFeatureMap ||
            mobileGsWallFeatureMapFrameVersion != wallFeatureMapFrameVersion

        referenceImage = reference
        mobileGsFingerprint = fingerprint
        mobileGsFingerprintFrameVersion = fingerprintFrameVersion
        mobileGsMapFromFingerprint = mapFromFingerprint?.copyOf()
        mobileGsWallFeatureMap = wallFeatureMap
        mobileGsWallFeatureMapFrameVersion = wallFeatureMapFrameVersion
        if (fingerprintChanged || mapChanged) lastRelocSeq = 0f

        // Deliberately does not touch the base photosphere (map orientation, keyframe cadence and
        // attitude reference survive inside the session).
        session.updatePrecisionLayer(
            reference = libraryReference(reference),
            atlasPages = atlasPages.map(::libraryAtlasPage),
            mapFromFingerprint = mapFromFingerprint.validTransformOrNull(),
            forceRebuild = fingerprintChanged || mapChanged,
        )
    }

    @Synchronized
    override fun analyze(image: ImageProxy) {
        try {
            if (closed || fatal) return
            applyFinishedDepth()
            val planes = image.planes
            if (planes.isEmpty()) return
            val y = planes[0]
            val rawWidth = image.width
            val rawHeight = image.height
            val rotationDeg = image.imageInfo.rotationDegrees
            // The full buffer, not image.cropRect. CameraController applies PreviewView's viewport
            // to every bound use case, so under CameraPreview's FILL_CENTER the analysis cropRect is
            // the on-screen crop — tracking on it would throw away the off-screen sides of the frame
            // (two fifths of a 3:4 frame's width on a 9:20 phone). The crop is a display concern only;
            // SphereSLAM camera.CameraFillCenter applies it where the frame meets the screen.
            // The full-frame "crop" is (0, 0, rawWidth, rawHeight); no per-frame Rect is built.
            if (rawWidth <= 0 || rawHeight <= 0) return

            val convertStartNs = android.os.SystemClock.elapsedRealtimeNanos()
            val rotated = lumaPool.packAndRotate(
                source = y.buffer,
                width = rawWidth,
                height = rawHeight,
                rowStride = y.rowStride,
                pixelStride = y.pixelStride,
                rotationDegrees = rotationDeg,
            )
            val convertDurationNs = android.os.SystemClock.elapsedRealtimeNanos() - convertStartNs

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
            val croppedIntrinsics = CameraIntrinsicsTransforms.crop(
                intrinsics = rawIntrinsics,
                cropLeft = 0,
                cropTop = 0,
                cropWidth = rawWidth,
                cropHeight = rawHeight,
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
                cropLeft = 0,
                cropTop = 0,
                cropWidth = rawWidth,
                cropHeight = rawHeight,
                rotationDegrees = rotationDeg,
                intrinsics = intrinsics,
            )
            slamManager?.setLiveIntrinsics(
                intrinsics.fx,
                intrinsics.fy,
                intrinsics.cx,
                intrinsics.cy,
            )

            // The tracked image is the raw buffer rotated CW by rotationDeg, so the device body maps
            // into its GL camera frame by R_z(sensorOrientation - rotationDeg) (== the display
            // rotation for a rear camera). The attitude is the one AT this frame's capture time
            // (interpolated from the host sensor's history on a REALTIME timestamp source), so a
            // committed bridge reference and the display predictor's anchor both sit at the frame
            // the pose is solved from, not at analyze() time (a frame can queue for tens of ms).
            val cameraFromDevice =
                DeviceCameraRotation.cameraFromDevice(sensorOrientationDeg, rotationDeg)
            val timestampNs = image.imageInfo.timestamp
            val heading = cameraAttitude()
            currentFrameIntrinsics = intrinsics
            val result = session.onFrame(
                PhotosphereTrackingSession.Frame(
                    luma = rotated.bytes,
                    intrinsics = intrinsics,
                    timestampNs = timestampNs,
                    attitude = StandaloneFrameAttitude.atCapture(
                        latest = readAttitude(),
                        history = captureAttitudeHistory,
                        frameTimestampNs = timestampNs,
                        frameTimestampIsRealtime =
                            cameraTimestampSource == StandaloneCameraTimestampSource.REALTIME,
                        scratch = captureAttitudeScratch,
                    ),
                    cameraFromDevice = cameraFromDevice,
                    headingDeg = heading?.first,
                    elevationDeg = heading?.second,
                    rollDeg = cameraRollDegrees(cameraFromDevice),
                    convertDurationNs = convertDurationNs,
                ),
            )
            currentFrameIntrinsics = null
            // A terminal failure inside the session was already reported (onFatalError above).
            if (session.isFatal) return

            if (referenceImage == null) {
                slamManager?.setTrackingPoseValid(false)
                onFrameTracked(null)
                return
            }
            if (result.visualAccepted) lastFailureReason = null

            val acceptedCameraFromFingerprint = result.acceptedCameraFromFingerprint
            when {
                // Accepted, but the lock is still being confirmed: nothing to render or feed yet.
                result.visualAccepted && acceptedCameraFromFingerprint == null -> onFrameTracked(null)
                result.visualAccepted -> {
                    // KPM and MobileGS are precision/enrichment estimators in fingerprint-local space;
                    // MobileGS is handed the accepted pose already rebased into the base SphereSLAM
                    // map. The fingerprint never becomes the world.
                    feedMobileGsFrame(rotated, timestampNs, result.projection, result.acceptedCameraFromMap)
                    maybePushDepth(rotated)
                    onFrameTracked(result.pose?.let { standaloneFrame(it) })
                }
                else -> {
                    // A visual miss still feeds pixels to the precision relocalizer, but with no
                    // current camera pose. This cannot overwrite the base-map pose with stale state.
                    // This is also the path while the session's first background matcher build is
                    // in flight (pose == null, no failure reported): nothing is torn down here, the
                    // UI keeps its state and only the artwork stays hidden until a pose exists.
                    feedMobileGsFrame(rotated, timestampNs, result.projection, null)
                    onFrameTracked(result.pose?.let { standaloneFrame(it) })
                }
            }
        } catch (t: Throwable) {
            if (!closed) {
                fatal = true
                Timber.e(t, "Standalone SphereSLAM analyzer failed")
                reportFailure(StandaloneFailureClassifier.fromThrowable(t))
                reportTrackingState(StandaloneTrackingState.FATAL)
                onFrameTracked(null)
                onFatalError(t)
            }
        } finally {
            image.close()
        }
    }

    private fun standaloneFrame(pose: PhotosphereTrackingSession.TrackedPose): SphereSlamStandaloneFrame =
        SphereSlamStandaloneFrame(
            viewMatrix = pose.cameraFromMap,
            projMatrix = pose.projection,
            frameIntrinsics = pose.intrinsics,
            frameAspect = pose.intrinsics.width.toFloat() / pose.intrinsics.height.toFloat(),
            frameHeightPixels = pose.intrinsics.height,
            timestampNs = pose.timestampNs,
            pageNo = pose.pageNo,
            reprojectionError = pose.reprojectionError,
            inlierCount = pose.inlierCount,
            unitsPerPixel = pose.unitsPerPixel,
            observationAgeMs = pose.observationAgeMs,
            matchDurationMs = pose.matchDurationMs,
            source = when (pose.source) {
                PhotosphereTrackingSession.PoseSource.KPM -> SphereSlamStandalonePoseSource.KPM
                PhotosphereTrackingSession.PoseSource.IMU_BRIDGE -> SphereSlamStandalonePoseSource.IMU_BRIDGE
            },
            matchMethod = pose.matchMethod.takeIf {
                pose.source == PhotosphereTrackingSession.PoseSource.KPM
            },
        )

    // Failures and state changes from the session and from this adapter (intrinsics, host-side
    // exceptions, the initial state on start) share one de-duplicated stream, each logged once here;
    // the session's own copies of these two diagnostic lines are dropped (isStateOrFailureLine).
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

    private fun isStateOrFailureLine(text: String): Boolean =
        text.startsWith("SphereSLAM standalone state=") || text.startsWith("SphereSLAM standalone failure=")

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
        // Compared field-by-field first so the steady state (unchanged calibration) allocates no
        // key object per frame.
        lastDiagnosticKey?.let { last ->
            val unchanged =
                last.rawWidth == rawWidth && last.rawHeight == rawHeight &&
                    last.cropLeft == cropLeft && last.cropTop == cropTop &&
                    last.cropWidth == cropWidth && last.cropHeight == cropHeight &&
                    last.rotationDegrees == rotationDegrees &&
                    last.displayWidth == intrinsics.width &&
                    last.displayHeight == intrinsics.height &&
                    last.fx == intrinsics.fx && last.fy == intrinsics.fy &&
                    last.cx == intrinsics.cx && last.cy == intrinsics.cy
            if (unchanged) return
        }
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

    /**
     * Phase 2 of the spherical-coverage map: estimate monocular depth on the live keyframe and stash
     * it in the native engine for depth-calibrated radial map-point placement. Entirely optional and
     * fail-soft: with no [depthEstimator] (the classic path), or when the model is unavailable,
     * nothing is stashed and the native map builds from the wall plane alone.
     *
     * On this (analysis) thread it only decides ([depthCadence]) and copies a downsampled luma grid;
     * the inference runs on [depthWorker]'s thread and a request is dropped — not queued — while a
     * previous one is still in flight. The result is applied by [applyFinishedDepth] on this thread.
     */
    private fun maybePushDepth(frame: RotatedLuma) {
        val estimator = depthEstimator ?: return
        val worker = depthWorker ?: return
        if (slamManager == null) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (worker.isBusy || !depthCadence.isDue(now)) return

        val frameWidth = frame.width
        val frameHeight = frame.height
        // Copy only what MiDaS consumes (it squashes its input to 256x256): a small box-averaged grid
        // instead of the full frame, which also keeps the worker-side bitmap tiny.
        val grid = DepthLumaDownsampler.downsample(frame.bytes, frameWidth, frameHeight) ?: return
        val accepted = worker.trySubmit(
            task = { estimateDepth(estimator, grid, frameWidth, frameHeight) },
            onError = { t -> Timber.w(t, "Standalone depth inference failed") },
        )
        if (accepted) depthCadence.markRun(now)
    }

    /** Depth worker thread. Null = nothing to apply (model unavailable). */
    private fun estimateDepth(
        estimator: com.hereliesaz.sphereslam.models.DepthEstimator,
        grid: DepthLumaDownsampler.Grid,
        frameWidth: Int,
        frameHeight: Int,
    ): DepthOutcome? {
        if (!estimator.isLoaded && !estimator.load()) return null
        // The grid is this job's own copy (never a pooled frame), fed straight in as luma: the
        // estimator resamples it to the model input with grey in all three channels — no ARGB
        // Bitmap round trip. Depth here is downscaled and only calibrates a radius, not a texture.
        val depth = estimator.estimateLuma(grid.bytes, grid.width, grid.height)
        // A failed inference is an outcome too: it clears any stale stashed depth so placement falls
        // back to the wall plane. The map is registered to the FULL tracked frame, not the grid.
        return DepthOutcome(
            data = depth?.data,
            width = depth?.width ?: 0,
            height = depth?.height ?: 0,
            frameWidth = frameWidth,
            frameHeight = frameHeight,
        )
    }

    /** Analysis thread: push a finished depth result (if any) into the native engine. */
    private fun applyFinishedDepth() {
        val outcome = depthWorker?.takeResult() ?: return
        val slam = slamManager ?: return
        val data = outcome.data
        if (data == null) {
            slam.setLatestDepthMap(null, 0, 0, 0, 0)
        } else {
            slam.setLatestDepthMap(data, outcome.width, outcome.height, outcome.frameWidth, outcome.frameHeight)
        }
    }

    /** A finished depth inference; [data] null means inference failed (clear the stash). */
    private class DepthOutcome(
        val data: FloatArray?,
        val width: Int,
        val height: Int,
        val frameWidth: Int,
        val frameHeight: Int,
    )

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
            // clear a prior project's precision layer without touching the base photosphere.
            slam.clearWallFingerprint()
            slam.clearWallFeatureMap()
            slam.overlayMarkCenterLocal = null
            slam.captureAnchorCam = null
            return
        }

        val mapFromFingerprint = mobileGsMapFromFingerprint
        if (
            mapFromFingerprint == null ||
            mapFromFingerprint.size != 16 ||
            mapFromFingerprint.any { !it.isFinite() }
        ) {
            slam.clearWallFingerprint()
            slam.clearWallFeatureMap()
            slam.overlayMarkCenterLocal = null
            slam.captureAnchorCam = null
            onDiagnostic(
                "SphereSLAM MobileGS seed refused: missing finite photosphere map_from_fingerprint"
            )
            return
        }

        slam.restoreWallFingerprintMetric(
            descriptorsData = fp.descriptorsData,
            rows = fp.descriptorsRows,
            cols = fp.descriptorsCols,
            type = fp.descriptorsType,
            points3d = fp.points3d.toFloatArray(),
            anchorMatrix = mapFromFingerprint,
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
                sameTransform(map.anchor, mapFromFingerprint)
        if (mapFrameOk) {
            val compatibleMap = requireNotNull(map)
            slam.restoreWallFeatureMap(compatibleMap)
            onDiagnostic(
                "SphereSLAM MobileGS map restored backend=standalone-kpm frame=photosphere-map " +
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
                        " anchorIdentity=" + isIdentityAnchor(map.anchor),
                )
            }
        }
        onDiagnostic(
            "SphereSLAM MobileGS seed backend=standalone-kpm frame=photosphere-map " +
                "version=" + mobileGsFingerprintFrameVersion +
                " rows=" + fp.descriptorsRows + " type=" + fp.descriptorsType,
        )
    }

    private fun feedMobileGsFrame(
        frame: RotatedLuma,
        timestampNs: Long,
        projection: FloatArray?,
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
        if (acceptedView != null && projection != null) {
            slam.updateCamera(acceptedView, projection, timestampNs)
        }
        slam.feedLumaFrame(directFrame(frame.bytes), frame.width, frame.height, timestampNs)
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
     * Both inputs here intentionally remain in the precision fingerprint frame:
     * `reloc[0..15]` is CV `camera_from_fingerprint`, while [kpmView] is GL
     * `camera_from_fingerprint`. [RigidMath.cvViewToGl] converts the former into the latter so
     * they can corroborate each other. Only AFTER this local precision fusion is the result rebased
     * through explicit `map_from_fingerprint` into the base SphereSLAM map.
     *
     * Both are camera-from-fingerprint VIEW matrices, so agreement and blending use SphereSLAM's
     * [PoseBlend], which compares and interpolates camera centres (`-Rᵀt`). (PoseFusion's
     * model-matrix helpers compared/lerped the translation column, which for a view mixes position
     * with orientation and swung the blended camera centre off the straight path.)
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
        val relocViewGl = RigidMath.cvViewToGl(reloc.copyOfRange(0, 16))
        if (!relocViewGl.all { it.isFinite() } || relocViewGl[15] != 1f) return kpmView
        // Trust the reloc only when it already corroborates the current KPM pose.
        if (PoseBlend.diverged(kpmView, relocViewGl)) return kpmView
        return PoseBlend.blend(kpmView, relocViewGl, RELOC_ALPHA)
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

    /** A converted session reference, reused while [reference] is the same object. */
    private fun libraryReference(
        reference: SphereSlamStandaloneReferenceImage?,
    ): PhotosphereTrackingSession.ReferenceImage? {
        if (reference == null) return null
        if (reference === convertedReferenceSource) return convertedReference
        val converted = PhotosphereTrackingSession.ReferenceImage(
            luma = reference.luma,
            width = reference.width,
            height = reference.height,
            referenceWidthUnits = reference.referenceWidthMeters,
            physicallyMetric = reference.physicallyMetric,
        )
        convertedReferenceSource = reference
        convertedReference = converted
        return converted
    }

    private fun libraryAtlasPage(page: SphereSlamStandaloneAtlasReferenceImage) =
        PhotosphereTrackingSession.AtlasPage(
            pageNo = page.pageNo,
            luma = page.luma,
            width = page.width,
            height = page.height,
            referenceWidthUnits = page.referenceWidthMeters,
            physicallyMetric = page.physicallyMetric,
            canonicalFromPage = page.canonicalFromPage,
        )

    private fun FloatArray?.validTransformOrNull(): FloatArray? =
        this?.takeIf { it.size == 16 && it.all(Float::isFinite) }

    private fun lumaBitmap(bytes: ByteArray, width: Int, height: Int): Bitmap {
        val pixels = IntArray(width * height)
        for (i in pixels.indices) {
            val v = bytes[i].toInt() and 0xFF
            pixels[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            it.setPixels(pixels, 0, width, 0, 0, width, height)
        }
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

    private fun resolveSensorOrientation(context: Context, cameraId: String): Int = runCatching {
        val manager = context.getSystemService(CameraManager::class.java)
        val raw = manager.getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: DEFAULT_SENSOR_ORIENTATION_DEG
        // SENSOR_ORIENTATION is a multiple of 90 by contract; DeviceCameraRotation requires that.
        if (raw % 90 == 0) raw else DEFAULT_SENSOR_ORIENTATION_DEG
    }.getOrDefault(DEFAULT_SENSOR_ORIENTATION_DEG)

    /** Diagnostic only: whether a persisted map anchor is the legacy centred-page identity frame. */
    private fun isIdentityAnchor(anchor: FloatArray, epsilon: Float = 1e-5f): Boolean =
        sameTransform(anchor, RigidMath.identity(), epsilon)

    private fun sameTransform(
        a: FloatArray?,
        b: FloatArray?,
        epsilon: Float = 1e-5f,
    ): Boolean {
        if (a == null || b == null || a.size != 16 || b.size != 16) return false
        return a.indices.all { i ->
            a[i].isFinite() && b[i].isFinite() &&
                kotlin.math.abs(a[i] - b[i]) <= epsilon
        }
    }

    // Synchronized like analyze()/updatePrecisionLayer(): it tears down the same session and buffers,
    // so it must not interleave with a frame or a precision-layer swap that reaches it from another
    // thread (the overlay posts it to the analysis executor, but nothing else enforces that).
    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        slamManager?.setTrackingPoseValid(false)
        ownAttitudeSource?.stop()
        session.displayPosePredictor?.clear()
        session.displayPosePredictor = null
        lastRelocSeq = 0f
        session.close()
        lastDiagnosticKey = null
        lastFailureReason = null
        lastReportedTrackingState = null
        frameBuffer = null
        // Shuts the depth thread down; an inference already running finishes and is discarded.
        depthWorker?.close()
        depthCadence.reset()
    }

    private companion object {
        /** Typical rear-camera mounting, used only when Camera2 cannot report SENSOR_ORIENTATION. */
        const val DEFAULT_SENSOR_ORIENTATION_DEG = 90

        /** Photosphere keyframe cadence; visual mapping runs for the entire standalone session. */
        const val PHOTOSPHERE_KEYFRAME_INTERVAL_MS = 250L

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
