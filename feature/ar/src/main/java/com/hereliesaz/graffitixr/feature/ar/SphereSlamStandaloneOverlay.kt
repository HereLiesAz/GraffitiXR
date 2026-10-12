package com.hereliesaz.graffitixr.feature.ar

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.view.LifecycleCameraController
import com.hereliesaz.graffitixr.common.model.CameraTargetFps
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.viewinterop.AndroidView
import com.hereliesaz.graffitixr.common.model.CoverageGlowAppearance
import com.hereliesaz.graffitixr.common.model.CoverageGlowStyle
import com.hereliesaz.graffitixr.common.model.Fingerprint
import com.hereliesaz.graffitixr.common.model.WallFeatureMap
import com.hereliesaz.graffitixr.common.model.ModeAdjustment
import com.hereliesaz.graffitixr.common.model.SphereSlamAtlasPage
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import com.hereliesaz.graffitixr.common.util.PerspectiveProcessor
import com.hereliesaz.graffitixr.design.theme.rememberAppStrings
import com.hereliesaz.sphereslam.camera.CameraFillCenter
import com.hereliesaz.graffitixr.feature.ar.rendering.HomographyOverlayRenderer
import com.hereliesaz.sphereslam.PhotosphereKeyframe
import com.hereliesaz.sphereslam.PhotosphereMap
import com.hereliesaz.sphereslam.SphereCoverage
import com.hereliesaz.sphereslam.attitude.AttitudeExtrapolator
import com.hereliesaz.sphereslam.attitude.AttitudeSample
import com.hereliesaz.sphereslam.attitude.DeviceCameraRotation
import com.hereliesaz.sphereslam.attitude.DisplayPosePredictor
import com.hereliesaz.sphereslam.overlay.CoverageGlowRenderer
import com.hereliesaz.sphereslam.reloc.PhotosphereTrackingSession
import com.hereliesaz.sphereslam.camera.CameraIntrinsics
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/** Prefix of PhotosphereTrackingSession's periodic per-window KPM metrics/timing diagnostic. */
private const val KPM_METRICS_PREFIX = "SphereSLAM standalone KPM "

private val SPHERESLAM_DEFAULT_UNWARP_POINTS = listOf(
    Offset(0.25f, 0.25f),
    Offset(0.75f, 0.25f),
    Offset(0.75f, 0.75f),
    Offset(0.25f, 0.75f),
)


/**
 * Drives the inverse of the usual point-glow: a translucent wash covers the whole preview and only
 * photosphere tiles that are current are cut transparent. Missing and stale tiles therefore glow by
 * default, exactly matching PhotosphereMap.needsUpdate semantics.
 *
 * The wash is SphereSLAM's `:overlay` [CoverageGlowRenderer] in [CoverageGlowRenderer.FillMode.COMPLEMENT]
 * with the current tiles' full angular extents ([com.hereliesaz.sphereslam.PhotosphereMap.currentRegions])
 * as the holes. [HomographyOverlayRenderer] draws it embedded in the artwork GL surface, before the
 * design quad. That is the one place that is both above the camera preview and beneath the artwork:
 * the artwork surface is `setZOrderMediaOverlay`, which sits behind the app window, so a window-level
 * View (an earlier implementation) washed over the artwork as well. A separate top-ordered
 * SurfaceView was worse still — it sat above all app UI.
 *
 * Since SphereSLAM 0.24.3 nothing is projected here: the regions are handed over only when the map
 * changes (per keyframe) and the renderer re-projects them on every draw with the full camera
 * rotation from the attitude extrapolator (roll-correct, defined at the poles) through the preview's
 * `FILL_CENTER` lens. This holds the user's glow settings ([CoverageGlowAppearance]) and the latest
 * regions; [applyAppearance] restyles it live. The style decides the fill mode and which regions feed
 * it (current tiles as holes for the wash and as the edge for the outline styles, tiles needing an
 * update as the fill for "missing tiles only"), and colour/intensity/edge/mode/geometry are handed to
 * the GL thread together as one [HomographyOverlayRenderer.CoverageGlowConfig].
 *
 * Thread-safe: keyframe callbacks (camera worker) and Settings changes (main) publish under one
 * lock, in order, so a geometry built for the old style can never land after the new style's.
 */
private class SphereTileGlowMask(
    private val renderer: HomographyOverlayRenderer,
) {
    // All guarded by `this`.
    private var appearance = CoverageGlowAppearance.DEFAULT
    private var currentRegions: List<SphereCoverage.TileRegion> = emptyList()
    private var regionsNeedingUpdate: List<SphereCoverage.TileRegion> = emptyList()
    private var regionsInProgress: List<PhotosphereMap.RegionProgress> = emptyList()
    private var outlineWidthPx = CoverageGlowStyleMapping.outlineWidthPxFor(1f)

    /** New map state (per keyframe / on entry). Any thread; a no-op when nothing changed. */
    fun updateRegions(
        current: List<SphereCoverage.TileRegion>,
        needingUpdate: List<SphereCoverage.TileRegion>,
        inProgress: List<PhotosphereMap.RegionProgress>,
    ) {
        synchronized(this) {
            if (
                current == currentRegions &&
                needingUpdate == regionsNeedingUpdate &&
                inProgress == regionsInProgress
            ) {
                return
            }
            currentRegions = current.toList()
            regionsNeedingUpdate = needingUpdate.toList()
            regionsInProgress = inProgress.toList()
            publish()
        }
    }

    /** Restyle the live glow (Settings changes apply immediately, no AR restart). Any thread. */
    fun applyAppearance(newAppearance: CoverageGlowAppearance) {
        synchronized(this) {
            appearance = newAppearance
            publish()
        }
    }

    /** Display density (px per dp) for the edge-glow width. Any thread. */
    fun setDensity(density: Float) {
        synchronized(this) {
            val width = CoverageGlowStyleMapping.outlineWidthPxFor(density)
            if (width == outlineWidthPx) return
            outlineWidthPx = width
            publish()
        }
    }

    /** Caller holds `this`. Only the GL thread touches the glow renderer; it applies this atomically. */
    private fun publish() {
        val style = CoverageGlowStyleMapping.effectiveStyle(appearance.style)
        val regions = CoverageGlowStyleMapping.regionsFor(
            style = style,
            currentRegions = currentRegions,
            regionsNeedingUpdate = regionsNeedingUpdate,
        )
        renderer.setCoverageGlowConfig(
            HomographyOverlayRenderer.CoverageGlowConfig(
                rgb = appearance.rgb(),
                alpha = appearance.safeIntensity,
                outlineAlpha = CoverageGlowStyleMapping.outlineAlphaFor(appearance.intensity),
                outlineWidthPx = outlineWidthPx,
                fillMode = CoverageGlowStyleMapping.fillModeFor(style),
                // Null = the whole view (missing-tiles style before the photosphere is anchored).
                geometry = regions?.let { HomographyOverlayRenderer.CoverageGlowGeometry.Regions(it) }
                    ?: HomographyOverlayRenderer.CoverageGlowGeometry.Triangles(
                        CoverageGlowStyleMapping.FULL_VIEW_TRIANGLES,
                    ),
                progress = regionsInProgress,
            )
        )
    }

    companion object {
        /**
         * The embedded glow renderer in the default appearance (hot pink 14% wash with holes) until the
         * first [applyAppearance] reaches the GL thread.
         */
        fun newRenderer(): CoverageGlowRenderer {
            val default = CoverageGlowAppearance.DEFAULT
            return CoverageGlowRenderer(
                hazeColor = default.rgb(),
                hazeAlpha = default.safeIntensity,
            ).apply { fillMode = CoverageGlowStyleMapping.fillModeFor(default.style) }
        }
    }
}

/** Current rotation of the default display in degrees (0/90/180/270); 0 when unavailable. */
private fun defaultDisplayRotationDegrees(context: android.content.Context): Int = runCatching {
    val display = context.getSystemService(android.hardware.display.DisplayManager::class.java)
        ?.getDisplay(android.view.Display.DEFAULT_DISPLAY)
    DeviceCameraRotation.surfaceRotationToDegrees(display?.rotation ?: android.view.Surface.ROTATION_0)
}.getOrDefault(0)

internal fun shouldStartStandaloneAnalyzer(
    cameraIdPresent: Boolean,
    atlasLoaded: Boolean,
): Boolean =
    cameraIdPresent && atlasLoaded

internal fun shouldUseCoopPeerFingerprint(
    spatialFrame: com.hereliesaz.graffitixr.common.model.CoopSpatialFrame?,
    peerFingerprint: ByteArray?,
): Boolean =
    spatialFrame?.hostBackend ==
        com.hereliesaz.graffitixr.common.model.CoopTrackingBackend.ARCORE &&
        spatialFrame.fingerprintAvailable &&
        peerFingerprint?.isNotEmpty() == true

/**
 * First functional no-ARCore AR path.
 *
 * Layer this composable over [CameraPreview]. The artist captures and rectifies a textured wall
 * patch, then CameraX frames are fed to calibrated SphereSLAM/KPM. A successful match directly
 * supplies the wall-relative OpenGL view matrix used to render the design. Short visual dropouts are
 * bridged with the device's fused gyro orientation; longer loss clears the overlay and shows an
 * explicit reacquisition state.
 *
 * After rectification the artist can enter the measured width represented by the target. That
 * produces physically metric KPM translation and design scale. The artist may deliberately skip
 * measurement; that fallback keeps a normalized 1.0-unit reference and must not be presented as a
 * real-world distance.
 */
@OptIn(ExperimentalCamera2Interop::class)
@Composable
fun SphereSlamStandaloneOverlay(
    cameraController: LifecycleCameraController,
    designBitmap: Bitmap?,
    slamManager: SlamManager,
    mobileGsFingerprint: Fingerprint? = null,
    mobileGsFingerprintFrameVersion: Int =
        com.hereliesaz.graffitixr.common.model.SPHERE_SLAM_FINGERPRINT_FRAME_VERSION,
    /** Explicit GL map_from_fingerprint bridge into the base SphereSLAM photosphere. */
    mobileGsMapFromFingerprint: List<Float> = emptyList(),
    mobileGsWallFeatureMap: WallFeatureMap? = null,
    mobileGsWallFeatureMapFrameVersion: Int =
        com.hereliesaz.graffitixr.common.model.SPHERE_SLAM_FINGERPRINT_FRAME_VERSION,
    persistedReferenceUri: Uri? = null,
    persistedReferenceWidthMeters: Float = 1f,
    persistedReferencePhysicallyMetric: Boolean = false,
    persistedAtlasPages: List<SphereSlamAtlasPage> = emptyList(),
    /** Protocol-v3 host wall frame when this device is a standalone co-op guest. */
    coopPeerSpatialFrame: com.hereliesaz.graffitixr.common.model.CoopSpatialFrame? = null,
    /** Host MobileGS fingerprint; required for ARCore-host -> standalone-guest alignment. */
    coopPeerFingerprint: ByteArray? = null,
    onReferenceCaptured: (Bitmap, Float, Boolean) -> Unit = { _, _, _ -> },
    /** Normal app path: TargetCreationUi above both AR backends owns capture/review/fingerprint UX. */
    sharedTargetCapture: Boolean = false,
    /**
     * With [sharedTargetCapture]: New Target / Recapture open the host's TargetCreationUi through
     * this. The current target keeps tracking until the replacement is persisted (a cancelled capture
     * changes nothing). Null hides those buttons (e.g. a co-op guest, who must not re-anchor).
     * Without [sharedTargetCapture] the buttons use this overlay's own capture flow.
     */
    onNewTargetRequested: (() -> Unit)? = null,
    onPersistedReferenceInvalid: (Uri) -> Unit = {},
    /** Native KPM registered the page loaded from this persisted URI. */
    onPersistedReferenceRegistered: (Uri) -> Unit = {},
    /**
     * Native KPM rejected the page loaded from this persisted URI as too weak. Return true when the
     * host restores the previous target's persisted state (a replacement it had not yet confirmed):
     * the overlay then waits for that URI and swaps back to it instead of restoring in memory.
     */
    onPersistedReferenceRejected: (Uri) -> Boolean = { false },
    onPersistedAtlasPageInvalid: (Int, Uri) -> Unit = { _, _ -> },
    onAtlasPageCaptured: (Bitmap, Int, Float, Boolean, FloatArray) -> Unit =
        { _, _, _, _, _ -> },
    adjustment: ModeAdjustment? = null,
    onUnitsPerPixel: (Float) -> Unit = {},
    onTrackingTick: (Boolean) -> Unit = {},
    onReferenceRegistrationChanged: (Boolean) -> Unit = {},
    onDiagnostic: (String) -> Unit = {},
    /**
     * Per-keyframe device orientation `(timestampNs, quaternion[x,y,z,w])` of the frame that proposed
     * each atlas-growth page — Phase 1b of the spherical-coverage map (`docs/SPHERESLAM_SPHERE_MAP.md`).
     * The quaternion is body→world: East-North-Up (`TYPE_ROTATION_VECTOR`, north-referenced) when the
     * host attitude ([attitudeSample]) is wired, otherwise the analyzer's own game rotation vector,
     * whose yaw origin is arbitrary per session (only relative orientations are meaningful then). The
     * timestamp is that frame's capture time (`elapsedRealtimeNanos` base), not when the growth was
     * adopted. Storage only; the view model gates recording on the feature-map flag and persists the
     * log. Fired on the camera worker thread, so the handler must be thread-safe.
     */
    onKeyframeOrientation: (Long, FloatArray) -> Unit = { _, _ -> },
    /**
     * Base SphereSLAM photosphere keyframes. These begin before any fingerprint exists and continue
     * independently of the fingerprint/teleological layer.
     */
    onPhotosphereKeyframe: (PhotosphereKeyframe) -> Unit = {},
    /**
     * Roll of the tracked image for a body->camera rotation (see
     * `ArViewModel.latestCameraRollDegrees`), stamped on photosphere keyframes; null when unknown.
     */
    cameraRollDegrees: (cameraFromDevice: FloatArray) -> Float? = { null },
    /** Same-frame base-map to precision-target transform, solved only after a real KPM lock. */
    onMapFromFingerprintSolved: (FloatArray) -> Unit = {},
    /**
     * Phase 2: optional monocular depth source. Non-null (feature-map flag on) opts this session into
     * depth-calibrated radial map-point placement; null is the classic wall-plane path. Owned by the
     * view model — the analyzer uses it but never closes it.
     */
    depthEstimator: com.hereliesaz.sphereslam.models.DepthEstimator? = null,
    /**
     * Phase 3 guided-sweep coverage over the wall's viewable arc, in `[0, 1]`, or null to hide the
     * hint (classic path / flag off). Shown while locked and still incomplete, to nudge the artist to
     * keep pivoting until the surrounding map is dense enough for fast re-lock. Guides, never gates
     * (§9.1).
     */
    sweepCoverage: Float? = null,
    /**
     * Current tiles' angular extents — the ONLY regions cut out of the otherwise full-screen glow
     * (`PhotosphereMap.currentRegions`). Read on entry and after each photosphere keyframe.
     */
    coverageCurrentRegions: () -> List<SphereCoverage.TileRegion> = { emptyList() },
    /**
     * Missing/stale tiles' angular extents (`PhotosphereMap.regionsNeedingUpdate`) — the glow fill
     * for [CoverageGlowStyle.MISSING_TILES].
     */
    coverageRegionsNeedingUpdate: () -> List<SphereCoverage.TileRegion> = { emptyList() },
    /**
     * Partly scanned tiles and their progress (`PhotosphereMap.regionsInProgress`): each is drawn more
     * opaque as it fills, then opens once scanned. Read with the other region lists.
     */
    coverageRegionsInProgress: () -> List<PhotosphereMap.RegionProgress> = { emptyList() },
    /** The user's coverage-glow colour/intensity/style; a change restyles the live glow at once. */
    coverageGlowAppearance: CoverageGlowAppearance = CoverageGlowAppearance.DEFAULT,
    /** Latest camera attitude `(headingDeg, elevationDeg)` stamped on photosphere keyframes, or null. */
    cameraAttitude: () -> Pair<Float, Float>? = { null },
    /**
     * The host's `CameraAttitudeProvider.attitudeExtrapolator` (`TYPE_ROTATION_VECTOR`, sampled at
     * `SENSOR_DELAY_GAME`): drives the display-rate glow rotation and the artwork's per-draw pose
     * prediction, and gives the tracking session each frame's capture-time attitude. Null (pass null
     * on a device without that sensor): the glow stays an unprojected wash and the artwork moves at
     * analyzer rate.
     */
    attitudeExtrapolator: AttitudeExtrapolator? = null,
    /**
     * The same provider's `latestSample()`, the tracking session's `Frame.attitude` fallback when a
     * frame's capture-time attitude is unavailable, so the pose predictor reads one sensor. Null (the
     * host passes null without the sensor): the analyzer samples its own game-rotation sensor for
     * `Frame.attitude` (base photosphere + gyro bridge) and no pose prediction runs.
     */
    attitudeSample: (() -> AttitudeSample?)? = null,
    /**
     * Settings → Camera target FPS ([CameraTargetFps]) with the adaptive-rate battery cap inputs.
     * Standalone pins the camera's AE target FPS range from these (Device default leaves the
     * device's own range unpinned); a change applies immediately.
     */
    cameraTargetFps: Int = CameraTargetFps.DEVICE_DEFAULT,
    adaptiveRateEnabled: Boolean = false,
    batteryTier: Int = 0,
    /**
     * Thermal / power-save / low-battery-warning throttle (ArUiState.perceptionSystemThrottle). With
     * [batteryTier] it sets the analyzers' [DeviceStress]; with [adaptiveRateEnabled] it sets their
     * adaptive cadence ([AnalysisFrameGate]). Pushed on every composition, so a change applies at once.
     */
    perceptionSystemThrottle: Boolean = false,
    /**
     * The host's onscreen hand-off ([StandaloneOverlayUiHost]): the status, Copy Diagnostics,
     * Recapture / New Target, sweep hint and fatal dialog are published there so the host draws them
     * in its onscreen layer (AzNavRail safe zones, above the rail's touch layer). Null: drawn
     * inline here.
     */
    uiHost: StandaloneOverlayUiHost? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val strings = rememberAppStrings()
    val scope = rememberCoroutineScope()
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val surfaceSize = remember { AtomicReference(IntSize.Zero) }

    // True once the analyzer DisposableEffect has disposed. An analyze() already running when the
    // analyzer is cleared can still post to mainHandler AFTER onDispose's removeCallbacksAndMessages
    // purge; those late posts would run on a torn-down composition. Every worker→UI post goes through
    // postUi, which drops the work when disposed. Reset to false when the effect re-arms (re-entry).
    val disposed = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    val standaloneAnalyzerRef =
        remember { AtomicReference<SphereSlamStandaloneTrackingAnalyzer?>(null) }
    // Latest periodic session metrics line (written on the camera worker, read by the dump).
    val latestKpmMetrics = remember { AtomicReference<String?>(null) }
    fun postUi(block: () -> Unit) {
        mainHandler.post { if (!disposed.get()) block() }
    }

    var rawCaptureBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var pendingReferenceBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var pendingReferenceWarning by remember { mutableStateOf<String?>(null) }
    var referenceNeedsPersistence by remember { mutableStateOf(false) }
    var previousReferenceBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var previousReferenceWidthMeters by remember { mutableStateOf(1f) }
    var previousReferencePhysicallyMetric by remember { mutableStateOf(false) }
    var referenceWidthInput by remember { mutableStateOf("") }
    var unwarpPoints by remember { mutableStateOf(SPHERESLAM_DEFAULT_UNWARP_POINTS) }
    var referenceBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var activeReferenceWidthMeters by remember { mutableStateOf(persistedReferenceWidthMeters) }
    var activeReferencePhysicallyMetric by remember {
        mutableStateOf(persistedReferencePhysicallyMetric)
    }
    var referenceReady by remember { mutableStateOf(false) }
    // A freshly captured page is native-registered BEFORE its versioned PNG/project metadata commit
    // completes. Keep Host disabled across that gap; only the persisted URI transition for this
    // candidate proves native geometry and durable project bytes now name the same page.
    var awaitingReferencePersistence by remember { mutableStateOf(false) }
    var persistenceBaselineUri by remember { mutableStateOf<Uri?>(null) }
    var referenceWidthUnits by remember { mutableStateOf(0f) }
    var referenceHeightUnits by remember { mutableStateOf(0f) }
    var designBaseHalfExtents by remember {
        mutableStateOf<StandaloneDesignHalfExtents?>(null)
    }
    var trackingState by remember {
        mutableStateOf(StandaloneTrackingState.INITIALIZING)
    }
    var fatalMessage by remember { mutableStateOf<String?>(null) }
    // The current analyzer reported a fatal error. Its SphereSLAM session is terminal (it ignores
    // every later frame and never resets), so tracking resumes only through a NEW analyzer:
    // bumping analyzerRestartGeneration (a key of the analyzer effect) recreates it.
    var analyzerFatal by remember { mutableStateOf(false) }
    var analyzerRestartGeneration by remember { mutableStateOf(0) }
    var currentFailure by remember { mutableStateOf<StandaloneFailureEvent?>(null) }
    var calibrationDiagnostics by remember {
        mutableStateOf<StandaloneCalibrationDiagnostics?>(null)
    }
    var matchDiagnostics by remember {
        mutableStateOf<StandaloneMatchDiagnostics?>(null)
    }

    fun applyFailure(event: StandaloneFailureEvent, emitDiagnostic: Boolean = true) {
        currentFailure = event
        if (emitDiagnostic) {
            onDiagnostic(
                "SphereSLAM standalone failure=${event.reason} severity=${event.severity}" +
                    if (event.diagnostic.isBlank()) "" else " ${event.diagnostic}",
            )
        }
        if (event.severity == StandaloneFailureSeverity.FATAL) {
            fatalMessage = event.userMessage
        }
    }

    fun resetCapture() {
        onReferenceRegistrationChanged(false)
        awaitingReferencePersistence = false
        persistenceBaselineUri = null
        // Only a page that completed native KPM registration is a safe rollback candidate.
        // Keeping an unvalidated bitmap here can create an infinite weak-target restore loop.
        if (referenceReady) {
            referenceBitmap?.let {
                previousReferenceBitmap = it
                previousReferenceWidthMeters = activeReferenceWidthMeters
                previousReferencePhysicallyMetric = activeReferencePhysicallyMetric
            }
        }
        // A fatal analyzer would silently ignore the recaptured target: start a fresh one (base
        // mapping resumes at once; the new target arrives through updatePrecisionLayer).
        if (shouldRestartAnalyzerOnRecapture(analyzerFatal)) {
            analyzerFatal = false
            analyzerRestartGeneration++
        }
        rawCaptureBitmap = null
        pendingReferenceBitmap = null
        pendingReferenceWarning = null
        referenceNeedsPersistence = false
        referenceWidthInput = ""
        referenceBitmap = null
        unwarpPoints = SPHERESLAM_DEFAULT_UNWARP_POINTS
        referenceReady = false
        trackingState = StandaloneTrackingState.INITIALIZING
        currentFailure = null
        calibrationDiagnostics = null
        matchDiagnostics = null
        fatalMessage = null
    }

    val latestOnNewTargetRequested by rememberUpdatedState(onNewTargetRequested)
    val newTargetAvailable = !sharedTargetCapture || onNewTargetRequested != null

    /** New Target / Recapture: the host's shared capture flow, or this overlay's own. */
    fun requestNewTarget() {
        if (sharedTargetCapture) latestOnNewTargetRequested?.invoke() else resetCapture()
    }

    // The persisted page the current reference was loaded from (shared capture swaps on a new one).
    var loadedReferenceUri by remember { mutableStateOf<Uri?>(null) }

    LaunchedEffect(persistedReferenceUri, awaitingReferencePersistence) {
        if (
            awaitingReferencePersistence &&
            persistedReferenceUri != null &&
            persistedReferenceUri != persistenceBaselineUri
        ) {
            awaitingReferencePersistence = false
            persistenceBaselineUri = null
            // If this commit introduced a MobileGS seed, the analyzer must first restart with that
            // NEW seed and only its onReferenceReady callback may unlock Host. If no seed exists,
            // KPM-only standalone sharing is valid and the page registration already succeeded.
            if (mobileGsFingerprint == null) {
                onReferenceRegistrationChanged(true)
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { onReferenceRegistrationChanged(false) }
    }

    LaunchedEffect(persistedReferenceUri) {
        val uri = persistedReferenceUri ?: return@LaunchedEffect
        // Shared capture keeps the current target until its replacement is persisted, then swaps to
        // it here. Inline capture persists the page this overlay already holds: nothing to load.
        val replacing = sharedTargetCapture && referenceBitmap != null && uri != loadedReferenceUri
        if (referenceBitmap != null && !replacing) return@LaunchedEffect
        val restored = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
            }.getOrNull()
        }
        if (restored != null) {
            // Snapshots a registered page as the rollback for a too-weak replacement, restarts a
            // fatal analyzer, clears the old target's state.
            if (replacing) resetCapture()
            loadedReferenceUri = uri
            activeReferenceWidthMeters = persistedReferenceWidthMeters
            activeReferencePhysicallyMetric = persistedReferencePhysicallyMetric
            referenceNeedsPersistence = false
            if (currentFailure?.reason == StandaloneFailureReason.PERSISTED_TARGET_CORRUPT) {
                currentFailure = null
            }
            referenceBitmap = restored
        } else {
            applyFailure(
                StandaloneFailureClassifier.event(
                    StandaloneFailureReason.PERSISTED_TARGET_CORRUPT,
                    "reference=${uri.lastPathSegment.orEmpty()}",
                ),
            )
            onPersistedReferenceInvalid(uri)
        }
    }

    fun acceptReference(bitmap: Bitmap, widthMeters: Float, physicallyMetric: Boolean) {
        onReferenceRegistrationChanged(false)
        activeReferenceWidthMeters = widthMeters
        activeReferencePhysicallyMetric = physicallyMetric
        pendingReferenceBitmap = null
        referenceWidthInput = ""
        referenceNeedsPersistence = true
        referenceBitmap = bitmap
    }

    val reference = referenceBitmap
    // An active ARCore-hosted co-op session is authoritative even if the imported host archive
    // happens to retain an OLD standalone page from some earlier capture. Tracking that stale page
    // would align the guest to the wrong wall. Peer geometry therefore wins solely from the live
    // protocol-v3 session contract; local/persisted page state is irrelevant while it is active.
    val peerOnlyTracking =
        shouldUseCoopPeerFingerprint(coopPeerSpatialFrame, coopPeerFingerprint)
    if (reference == null && !peerOnlyTracking && !sharedTargetCapture) {
        val pending = pendingReferenceBitmap
        if (pending != null) {
            val measuredWidth = StandaloneReferenceScale.parseMeters(referenceWidthInput)
            PublishOrDraw(uiHost) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        modifier = Modifier
                            .background(Color.Black.copy(alpha = 0.82f), RoundedCornerShape(16.dp))
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            "Measure the real width between the left and right edges of the target.",
                            color = Color.White,
                        )
                        OutlinedTextField(
                            value = referenceWidthInput,
                            onValueChange = { referenceWidthInput = it.take(12) },
                            label = { Text("Target width (meters)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            isError = referenceWidthInput.isNotBlank() && measuredWidth == null,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                        if (referenceWidthInput.isNotBlank() && measuredWidth == null) {
                            Text(
                                "Enter a width from 0.05 m to 100 m.",
                                color = Color.White,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                        pendingReferenceWarning?.let { warning ->
                            Text(
                                warning,
                                color = Color.White,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                        Button(
                            onClick = { acceptReference(pending, measuredWidth!!, true) },
                            enabled = measuredWidth != null,
                            modifier = Modifier.padding(top = 16.dp),
                        ) {
                            Text("Use Measured Width")
                        }
                        TextButton(
                            onClick = { acceptReference(pending, 1f, false) },
                            modifier = Modifier.padding(top = 4.dp),
                        ) {
                            Text("Continue Without Physical Scale")
                        }
                        TextButton(
                            onClick = {
                                pendingReferenceBitmap = null
                                referenceWidthInput = ""
                            },
                        ) {
                            Text("Recapture")
                        }
                    }
                }
            }
            return
        }

        val raw = rawCaptureBitmap
        if (raw == null) {
            PublishOrDraw(uiHost) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        fatalMessage?.let { message ->
                            Text(
                                text = message,
                                color = Color.White,
                                modifier = Modifier
                                    .padding(bottom = 12.dp)
                                    .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(12.dp))
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                        Button(
                            onClick = {
                                fatalMessage = null
                                scope.launch {
                                    val result = runCatching {
                                        cameraController.takePictureAsBitmap(context)
                                    }
                                    rawCaptureBitmap = result.getOrElse {
                                        fatalMessage = "Couldn't capture the wall target — try again."
                                        null
                                    }
                                }
                            },
                            modifier = Modifier.padding(bottom = 32.dp),
                        ) {
                            Text("Capture Wall Target")
                        }
                    }
                }
            }
        } else {
            UnwarpScreen(
                bitmap = raw,
                points = unwarpPoints,
                onUpdatePoints = { unwarpPoints = it },
                onConfirm = { points ->
                    scope.launch(Dispatchers.Default) {
                        val pixelPoints = points.map { Offset(it.x * raw.width, it.y * raw.height) }
                        val unwarped = PerspectiveProcessor.unwarpImage(raw, pixelPoints)
                        withContext(Dispatchers.Main) {
                            if (unwarped == null) {
                                fatalMessage = "Couldn't rectify that target — mark four clear corners."
                                rawCaptureBitmap = null
                            } else {
                                val report = StandaloneTargetQuality.analyze(
                                    luma = bitmapToLuma(unwarped),
                                    width = unwarped.width,
                                    height = unwarped.height,
                                )
                                val blockingMessage = StandaloneTargetQuality.blockingMessage(report)
                                if (blockingMessage != null) {
                                    fatalMessage = blockingMessage
                                    rawCaptureBitmap = null
                                } else {
                                    pendingReferenceWarning =
                                        StandaloneTargetQuality.warningMessage(report)
                                    pendingReferenceBitmap = unwarped
                                    rawCaptureBitmap = null
                                }
                            }
                        }
                    }
                },
                onCancel = {
                    rawCaptureBitmap = null
                    unwarpPoints = SPHERESLAM_DEFAULT_UNWARP_POINTS
                },
                strings = strings,
            )
        }
        return
    }

    val referenceImage = remember(reference) {
        reference?.let {
            SphereSlamStandaloneReferenceImage(
                luma = bitmapToLuma(it),
                width = it.width,
                height = it.height,
                referenceWidthMeters = activeReferenceWidthMeters,
                physicallyMetric = activeReferencePhysicallyMetric,
            )
        }
    }
    // A fresh candidate page exists before its project commit clears the OLD page's seed/map/atlas.
    // Never let those old coordinates cross into the candidate analyzer. Once the new versioned URI
    // lands, awaitingReferencePersistence flips false and we snapshot the freshly committed state.
    // Shared capture: the project already names a new page (its seed/map/atlas with it) while this
    // overlay still holds the previous page until the loader swaps. Same rule: no new coordinates on
    // the old page.
    val swapPending = sharedTargetCapture && reference != null &&
        persistedReferenceUri != null && persistedReferenceUri != loadedReferenceUri
    val candidateUncommitted = referenceNeedsPersistence || awaitingReferencePersistence || swapPending
    val runtimeMobileGsFingerprint =
        if (candidateUncommitted) null else mobileGsFingerprint
    // Snapshot persisted native state once per canonical page. Autosaves/grown-page commits publish
    // new project objects while THIS analyzer already owns the fresher live state; keying ordinary
    // autosaves into the effect would tear down tracking every few seconds.
    val initialMobileGsWallFeatureMap = remember(reference, candidateUncommitted) {
        if (candidateUncommitted) null else mobileGsWallFeatureMap
    }
    val initialMobileGsWallFeatureMapFrameVersion =
        remember(reference, candidateUncommitted) { mobileGsWallFeatureMapFrameVersion }
    val initialPersistedAtlasPages = remember(reference, candidateUncommitted) {
        if (candidateUncommitted) emptyList() else persistedAtlasPages
    }
    // Pages the live session grew for this canonical page. The persisted snapshot above is fixed per
    // page (re-keying it on every commit would tear tracking down), so without this a later
    // updatePrecisionLayer or analyzer restart would hand the session a page set missing them, and
    // SphereSLAM rebuilds the matcher from exactly the set it is given: every grown page dropped.
    val grownAtlasPages = remember(reference, candidateUncommitted) {
        java.util.concurrent.CopyOnWriteArrayList<SphereSlamStandaloneAtlasReferenceImage>()
    }
    val atlasReferenceImages by produceState<List<SphereSlamStandaloneAtlasReferenceImage>?>(
        initialValue = null,
        initialPersistedAtlasPages,
    ) {
        val expectedFrame =
            com.hereliesaz.graffitixr.common.model.SPHERE_SLAM_FINGERPRINT_FRAME_VERSION
        val loaded = withContext(Dispatchers.IO) {
            val valid = mutableListOf<SphereSlamStandaloneAtlasReferenceImage>()
            val invalid = mutableListOf<Pair<Int, Uri>>()
            initialPersistedAtlasPages.sortedBy { it.pageNo }.forEach { page ->
                if (page.frameVersion != expectedFrame) {
                    invalid += page.pageNo to page.referenceUri
                    return@forEach
                }
                val bitmap = runCatching {
                    context.contentResolver.openInputStream(page.referenceUri)
                        ?.use(BitmapFactory::decodeStream)
                }.getOrNull()
                if (bitmap == null) {
                    invalid += page.pageNo to page.referenceUri
                } else {
                    valid += SphereSlamStandaloneAtlasReferenceImage(
                        pageNo = page.pageNo,
                        luma = bitmapToLuma(bitmap),
                        width = bitmap.width,
                        height = bitmap.height,
                        referenceWidthMeters = page.referenceWidthMeters,
                        physicallyMetric = page.physicallyMetric,
                        canonicalFromPage = page.canonicalFromPage.toFloatArray(),
                    )
                }
            }
            valid to invalid
        }
        loaded.second.forEach { (pageNo, uri) ->
            onPersistedAtlasPageInvalid(pageNo, uri)
            onDiagnostic(
                "SphereSLAM atlas page refused page=" + pageNo +
                    " reason=missing-corrupt-or-frame-version",
            )
        }
        value = loaded.first
    }
    // The coverage wash is embedded in the artwork renderer (drawn before the design).
    val coverageGlow = remember(context) { SphereTileGlowMask.newRenderer() }
    val glRenderer = remember(context) { HomographyOverlayRenderer(context, coverageGlow) }
    LaunchedEffect(
        glRenderer,
        peerOnlyTracking,
        coopPeerSpatialFrame,
        mobileGsMapFromFingerprint,
    ) {
        val mapAnchor =
            if (peerOnlyTracking && coopPeerSpatialFrame != null) {
                // Peer analyzer already emits camera_from_hostWall, so its wall model is identity.
                floatArrayOf(
                    1f, 0f, 0f, 0f,
                    0f, 1f, 0f, 0f,
                    0f, 0f, 1f, 0f,
                    0f, 0f, 0f, 1f,
                )
            } else {
                mobileGsMapFromFingerprint
                    .takeIf { it.size == 16 && it.all(Float::isFinite) }
                    ?.toFloatArray()
            }
        glRenderer.setMapFromFingerprint(mapAnchor)
    }
    // Full-view freshness mask: glow everywhere, then punch out only current photosphere tiles.
    // Seeded immediately from the map as it stands (all stale on a fresh map, so the whole view glows
    // before the first keyframe); the renderer re-projects it every draw.
    val coverageGlowMask = remember(glRenderer) {
        SphereTileGlowMask(glRenderer).also {
            it.applyAppearance(coverageGlowAppearance)
            it.updateRegions(
                coverageCurrentRegions(),
                coverageRegionsNeedingUpdate(),
                coverageRegionsInProgress(),
            )
        }
    }
    // Settings apply immediately: restyle the live glow on every change, no AR restart.
    LaunchedEffect(coverageGlowMask, coverageGlowAppearance) {
        coverageGlowMask.applyAppearance(coverageGlowAppearance)
    }
    val density = LocalDensity.current.density
    LaunchedEffect(coverageGlowMask, density) { coverageGlowMask.setDensity(density) }

    // Display-rate attitude: the glow's rotation source and the artwork's per-draw pose prediction
    // read the same TYPE_ROTATION_VECTOR extrapolator the tracking session's Frame.attitude comes
    // from. The predictor is wired only when that holds (attitudeSample supplied).
    val displayPosePredictor = remember { DisplayPosePredictor() }
    val posePredictionWired = attitudeExtrapolator != null && attitudeSample != null
    LaunchedEffect(glRenderer, attitudeExtrapolator, posePredictionWired) {
        glRenderer.setAttitude(
            extrapolator = attitudeExtrapolator,
            predictor = displayPosePredictor.takeIf { posePredictionWired },
        )
    }
    // The glow's camera frame follows the display rotation (the activity handles orientation
    // changes itself, so a 180° flip arrives here, not as a recreation).
    DisposableEffect(glRenderer, context) {
        glRenderer.setDisplayRotation(defaultDisplayRotationDegrees(context))
        val displayManager = context.getSystemService(android.hardware.display.DisplayManager::class.java)
        val listener = object : android.hardware.display.DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) {
                if (displayId == android.view.Display.DEFAULT_DISPLAY) {
                    glRenderer.setDisplayRotation(defaultDisplayRotationDegrees(context))
                }
            }
        }
        displayManager?.registerDisplayListener(listener, mainHandler)
        onDispose {
            displayManager?.unregisterDisplayListener(listener)
            displayPosePredictor.clear()
        }
    }

    LaunchedEffect(glRenderer, designBitmap) {
        if (designBitmap == null) {
            glRenderer.clearDesignBitmap()
        } else {
            glRenderer.updateDesignBitmap(designBitmap)
        }
    }

    LaunchedEffect(
        glRenderer,
        adjustment?.offsetX,
        adjustment?.offsetY,
        adjustment?.scale,
        adjustment?.rotation,
        adjustment?.rotationX,
        adjustment?.rotationY,
    ) {
        glRenderer.setTransform(
            panX = adjustment?.offsetX ?: 0f,
            panY = adjustment?.offsetY ?: 0f,
            scale = adjustment?.scale ?: 1f,
            // Stored model convention is CW+ in screen space; GL wall-local +Z is CCW+.
            rotationZDeg = -(adjustment?.rotation ?: 0f),
            rotationXDeg = adjustment?.rotationX ?: 0f,
            rotationYDeg = adjustment?.rotationY ?: 0f,
        )
    }

    LaunchedEffect(
        glRenderer,
        designBitmap,
        referenceWidthUnits,
        referenceHeightUnits,
        peerOnlyTracking,
        coopPeerSpatialFrame,
    ) {
        val peerWidth = coopPeerSpatialFrame?.referenceWidthUnits
        val fit =
            if (
                peerOnlyTracking &&
                peerWidth != null &&
                peerWidth > 0f &&
                designBitmap != null
            ) {
                // ARCore publishes referenceWidthUnits as the host design's persisted metric width.
                // Use it exactly: applying the normal page-fit 80% margin here would make an
                // ARCore-hosted mural shrink on a standalone guest despite correct pose alignment.
                val halfW = peerWidth * 0.5f
                StandaloneDesignHalfExtents(
                    halfWidth = halfW,
                    halfHeight = halfW * designBitmap.height.toFloat() / designBitmap.width.toFloat(),
                )
            } else {
                fitStandaloneDesignHalfExtents(
                    pageWidthUnits = referenceWidthUnits,
                    pageHeightUnits = referenceHeightUnits,
                    designWidthPx = designBitmap?.width,
                    designHeightPx = designBitmap?.height,
                )
            }
        designBaseHalfExtents = fit
        if (fit != null) glRenderer.setExtent(fit.halfWidth, fit.halfHeight)
    }

    LaunchedEffect(peerOnlyTracking, coopPeerSpatialFrame) {
        if (peerOnlyTracking) {
            val frame = requireNotNull(coopPeerSpatialFrame)
            activeReferenceWidthMeters = frame.referenceWidthUnits
            activeReferencePhysicallyMetric =
                frame.scale == com.hereliesaz.graffitixr.common.model.CoopSpatialScale.METRIC
            referenceWidthUnits = frame.referenceWidthUnits
            referenceHeightUnits = frame.referenceWidthUnits
            referenceReady = true
            trackingState = StandaloneTrackingState.REACQUIRING
        }
    }

    // Push the SAME wall-local rigid placement/extents used by the standalone renderer into
    // MobileGS. This turns corroboration from global descriptor search into the spatially gated
    // path and gives self-grow an unambiguous fingerprint-frame Φ when that experiment is enabled.
    LaunchedEffect(
        slamManager,
        runtimeMobileGsFingerprint,
        designBitmap,
        designBaseHalfExtents,
        adjustment?.offsetX,
        adjustment?.offsetY,
        adjustment?.scale,
        adjustment?.rotation,
    ) {
        val placement = if (runtimeMobileGsFingerprint == null || designBitmap == null) {
            null
        } else {
            standaloneDesignPlacement(
                base = designBaseHalfExtents,
                panX = adjustment?.offsetX ?: 0f,
                panY = adjustment?.offsetY ?: 0f,
                scale = adjustment?.scale ?: 1f,
                storedClockwiseRotationDeg = adjustment?.rotation ?: 0f,
            )
        }
        slamManager.setDesignPlacement(
            placement?.fingerprintFromDesign,
            placement?.halfWidth ?: 0f,
            placement?.halfHeight ?: 0f,
        )
    }

    DisposableEffect(slamManager) {
        onDispose {
            // MobileGS is process-global; never let this project's placement leak into another mode
            // or project after the standalone composition leaves.
            slamManager.setDesignPlacement(null, 0f, 0f)
        }
    }

    val cameraId by produceState<String?>(initialValue = null, cameraController, reference) {
        val startedMs = android.os.SystemClock.elapsedRealtime()
        var unavailableReported = false
        while (value == null) {
            value = runCatching {
                cameraController.cameraInfo?.let { Camera2CameraInfo.from(it).cameraId }
            }.getOrNull()
            if (value == null) {
                if (
                    !unavailableReported &&
                    android.os.SystemClock.elapsedRealtime() - startedMs >= 5_000L
                ) {
                    unavailableReported = true
                    applyFailure(
                        StandaloneFailureClassifier.event(
                            StandaloneFailureReason.CAMERA_UNAVAILABLE,
                            "cameraInfo remained unavailable for 5000ms",
                        ),
                    )
                }
                delay(50)
            }
        }
        if (unavailableReported && currentFailure?.reason == StandaloneFailureReason.CAMERA_UNAVAILABLE) {
            currentFailure = null
        }
    }

    fun copyDiagnostics() {
        val sphereMapEnabled = depthEstimator != null || sweepCoverage != null
        val relocCounts = slamManager.getMapRelocCounts()
        val dump = standaloneDiagnosticDump(
            calibration = calibrationDiagnostics,
            trackingState = trackingState,
            match = matchDiagnostics,
            physicallyMetric = activeReferencePhysicallyMetric,
            referenceWidthUnits = activeReferenceWidthMeters,
            failure = currentFailure,
            sphereMap = SphereMapDiagnostics(
                enabled = sphereMapEnabled,
                pointCount = slamManager.getMapPointCount(),
                revision = slamManager.getWallFeatureMapRevision(),
                relocVisible = relocCounts.getOrElse(0) { -1 },
                relocCorr = relocCounts.getOrElse(1) { -1 },
                sweepCoverage = sweepCoverage ?: 0f,
            ),
            kpmMetrics = latestKpmMetrics.get(),
        )
        context.getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText("GraffitiXR SphereSLAM diagnostics", dump))
        onDiagnostic("SphereSLAM standalone diagnostic dump copied")
    }

    // Remembered once, so it reads the current composition's values through these.
    val currentPeerOnlyTracking by rememberUpdatedState(peerOnlyTracking)
    val currentOnUnitsPerPixel by rememberUpdatedState(onUnitsPerPixel)
    val currentOnTrackingTick by rememberUpdatedState(onTrackingTick)
    val trackedFrameUi = remember {
        LatestValueUiPost<TrackedFrameUi>(post = { drain -> postUi(drain) }) { update ->
            if (currentPeerOnlyTracking) {
                trackingState =
                    if (update.tracking) StandaloneTrackingState.LOCKED
                    else StandaloneTrackingState.REACQUIRING
            }
            // A lost frame keeps the last match's diagnostics, as before.
            update.match?.let { matchDiagnostics = it }
            currentOnUnitsPerPixel(update.screenUnitsPerPixel)
            currentOnTrackingTick(update.tracking)
        }
    }

    fun consumeTrackedFrame(frame: SphereSlamStandaloneFrame?) {
        // Renderer state is atomic/volatile and intentionally updated directly from the analysis
        // worker. Compose state belongs to Main and is posted there separately.
        if (frame == null) {
            glRenderer.clearPose()
        } else {
            glRenderer.updatePose(frame.viewMatrix, frame.frameIntrinsics)
            // The glow is no longer projected per tracked frame: the renderer re-projects the
            // regions every draw from the attitude extrapolator. Only keep its lens source current
            // (a value-compared volatile write; the co-op peer path has no calibration callback).
            glRenderer.setGlowFrameIntrinsics(frame.frameIntrinsics)
        }
        val screenUnitsPerPixel = frame?.let {
            val size = surfaceSize.get()
            standaloneScreenUnitsPerPixel(
                frameUnitsPerPixel = it.unitsPerPixel,
                frameHeightPixels = it.frameHeightPixels,
                frameAspect = it.frameAspect,
                surfaceWidthPixels = size.width,
                surfaceHeightPixels = size.height,
            )
        } ?: 0f
        // Coalesced: at most one UI update outstanding, carrying the newest frame's values (a slow
        // main thread must not replay a backlog of stale per-frame ticks).
        trackedFrameUi.offer(
            TrackedFrameUi(
                tracking = frame != null,
                match = frame?.let {
                    StandaloneMatchDiagnostics(
                        pageNo = it.pageNo,
                        inliers = it.inlierCount,
                        reprojectionError = it.reprojectionError,
                        observationAgeMs = it.observationAgeMs,
                        matchDurationMs = it.matchDurationMs,
                        source = it.source,
                        matchMethod = it.matchMethod?.name,
                    )
                },
                screenUnitsPerPixel = screenUnitsPerPixel,
            ),
        )
    }

    // CameraController implements pinch-to-camera-zoom itself. That would change the effective
    // intrinsics behind KPM while GraffitiXR's own pinch gesture is supposed to scale the artwork.
    // Keep the standalone camera calibrated at 1x for this composition only, and restore the shared
    // controller state when leaving standalone AR.
    DisposableEffect(cameraController) {
        val previousSelector = cameraController.cameraSelector
        cameraController.cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
        onDispose {
            cameraController.cameraSelector = previousSelector
        }
    }

    DisposableEffect(cameraController, cameraId) {
        val id = cameraId
        if (id == null) {
            onDispose {}
        } else {
            val previousPinchToZoom = cameraController.isPinchToZoomEnabled
            val previousZoomRatio = cameraController.zoomState.value?.zoomRatio ?: 1f
            cameraController.setPinchToZoomEnabled(false)
            cameraController.cameraControl?.setZoomRatio(1f)

            onDispose {
                cameraController.setPinchToZoomEnabled(previousPinchToZoom)
                cameraController.cameraControl?.setZoomRatio(previousZoomRatio)
            }
        }
    }

    // Tracking capture profile while standalone AR is composed: fixed AE target FPS range (so dim
    // light cannot halve the analysis rate), EIS off (no preview latency / preview-vs-analysis
    // desync), OIS kept. Keyed on the effective rate so a Settings change (or the adaptive battery
    // cap) applies immediately; the shared controller's defaults are restored on exit.
    // See StandaloneCameraTuning.kt.
    val frameGate = remember { AnalysisFrameGate() }
    SideEffect {
        frameGate.stress = deviceStress(perceptionSystemThrottle, batteryTier)
        frameGate.adaptiveCadence = adaptiveRateEnabled
    }

    val standaloneTargetFps = standaloneEffectiveCameraTargetFps(
        targetFps = cameraTargetFps,
        adaptiveRateEnabled = adaptiveRateEnabled,
        batteryTier = batteryTier,
    )
    DisposableEffect(cameraController, cameraId, standaloneTargetFps) {
        if (cameraId == null) {
            onDispose {}
        } else {
            val applied = applyStandaloneTrackingCaptureOptions(cameraController, standaloneTargetFps)
            onDiagnostic(
                "SphereSLAM standalone camera: setting=${CameraTargetFps.label(standaloneTargetFps)} " +
                    "aeRange=" + (applied?.aeRange?.let { "[${it[0]},${it[1]}]" } ?: "unchanged") +
                    " eis=off ois=on lens=" + (applied?.distortion ?: "unchanged"),
            )
            onDispose { applyDefaultCaptureOptions(cameraController) }
        }
    }

    // Keyed on the atlas being loaded (not the list: growth must not tear the analyzer down) — when the
    // camera id resolves first, the analyzer starts once the atlas decode lands.
    val atlasLoaded = atlasReferenceImages != null
    DisposableEffect(
        cameraController,
        cameraId,
        atlasLoaded,
        peerOnlyTracking,
        coopPeerSpatialFrame,
        coopPeerFingerprint,
        analyzerRestartGeneration,
    ) {
        // Re-arm the UI-post gate: a prior disposal set this true, and this fresh analyzer's posts
        // must be delivered again. The coalescer forgets a drain the purge dropped, or it would wait on
        // it for good.
        disposed.set(false)
        trackedFrameUi.reset()
        frameGate.reset()
        val id = cameraId
        val restoredAtlas = atlasReferenceImages
        val standaloneReference = referenceImage
        // SphereSLAM is the base runtime. Start its analyzer as soon as CameraX + atlas state exist,
        // even before any fingerprint/reference page has been created.
        if (
            !shouldStartStandaloneAnalyzer(
                cameraIdPresent = id != null,
                atlasLoaded = restoredAtlas != null,
            ) ||
            id == null ||
            restoredAtlas == null
        ) {
            onDispose {}
        } else {
            // Stable non-null locals keep Kotlin's smart casts valid throughout DisposableEffect.
            val activeCameraId: String = id
            val activeAtlas: List<SphereSlamStandaloneAtlasReferenceImage> =
                withGrownAtlasPages(restoredAtlas, grownAtlasPages)
            val executor = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "sphereslam-standalone-camera").apply { isDaemon = true }
            }
            if (peerOnlyTracking) {
                val peerSpatial = coopPeerSpatialFrame
                val peerFingerprint = coopPeerFingerprint
                if (peerSpatial == null || peerFingerprint == null || peerFingerprint.isEmpty()) {
                    executor.shutdown()
                    onDispose {}
                } else {
                    val analyzer = CoopPeerFingerprintAnalyzer(
                        context = context,
                        cameraId = activeCameraId,
                        slam = slamManager,
                        peerFingerprint = peerFingerprint,
                        spatialFrame = peerSpatial,
                        onFrameTracked = ::consumeTrackedFrame,
                        onDiagnostic = { text -> postUi { onDiagnostic(text) } },
                        frameGate = frameGate,
                    )
                    cameraController.setImageAnalysisAnalyzer(executor, analyzer)
                    onDispose {
                        disposed.set(true)
                        cameraController.clearImageAnalysisAnalyzer()
                        mainHandler.removeCallbacksAndMessages(null)
                        executor.execute { analyzer.close() }
                        executor.shutdown()
                        glRenderer.clearPose()
                    }
                }
            } else {
                val analyzer = SphereSlamStandaloneTrackingAnalyzer(
                context = context,
                cameraId = activeCameraId,
                referenceImage = standaloneReference,
                atlasReferenceImages = activeAtlas,
                slamManager = slamManager,
                mobileGsFingerprint = runtimeMobileGsFingerprint,
                mobileGsFingerprintFrameVersion = mobileGsFingerprintFrameVersion,
                mobileGsMapFromFingerprint =
                    mobileGsMapFromFingerprint.takeIf { it.size == 16 }?.toFloatArray(),
                mobileGsWallFeatureMap = initialMobileGsWallFeatureMap,
                mobileGsWallFeatureMapFrameVersion = initialMobileGsWallFeatureMapFrameVersion,
                onReferenceReady = { registered: PhotosphereTrackingSession.RegisteredPage ->
                    val g = registered.geometry
                    postUi {
                        referenceWidthUnits = g.widthMeters
                        referenceHeightUnits = g.heightMeters
                        referenceReady = true
                        if (referenceNeedsPersistence) {
                            awaitingReferencePersistence = true
                            persistenceBaselineUri = persistedReferenceUri
                            referenceBitmap?.let { accepted ->
                                onReferenceCaptured(
                                    accepted,
                                    activeReferenceWidthMeters,
                                    activeReferencePhysicallyMetric,
                                )
                            }
                            referenceNeedsPersistence = false
                            previousReferenceBitmap = null
                        } else {
                            // Restored page: durable metadata already points at this exact candidate,
                            // so successful addReference/configureMobileGs is the final readiness gate.
                            onReferenceRegistrationChanged(true)
                            loadedReferenceUri?.let(onPersistedReferenceRegistered)
                        }
                    }
                },
                onAtlasPageAdded = { candidate ->
                    runCatching {
                        SphereSlamStandaloneAtlasReferenceImage(
                            pageNo = candidate.pageNo,
                            luma = candidate.luma,
                            width = candidate.bitmap.width,
                            height = candidate.bitmap.height,
                            referenceWidthMeters = candidate.referenceWidthUnits,
                            physicallyMetric = candidate.physicallyMetric,
                            canonicalFromPage = candidate.canonicalFromPage.copyOf(),
                        )
                    }.onSuccess { grownAtlasPages += it }
                        .onFailure { Timber.w(it, "Grown atlas page %d not retained", candidate.pageNo) }
                    postUi {
                        onAtlasPageCaptured(
                            candidate.bitmap,
                            candidate.pageNo,
                            candidate.referenceWidthUnits,
                            candidate.physicallyMetric,
                            candidate.canonicalFromPage.copyOf(),
                        )
                    }
                },
                onDiagnostic = { text ->
                    // The session's periodic `KPM ... method= timing{...}` line, kept for the
                    // diagnostics dump (it is also logged like every other line).
                    if (text.startsWith(KPM_METRICS_PREFIX)) latestKpmMetrics.set(text)
                    postUi { onDiagnostic(text) }
                },
                onCalibrationChanged = { calibration ->
                    // The glow lens follows the analysis frame from the first frame, tracked or not.
                    glRenderer.setGlowFrameIntrinsics(
                        CameraIntrinsics(
                            fx = calibration.fx,
                            fy = calibration.fy,
                            cx = calibration.cx,
                            cy = calibration.cy,
                            width = calibration.displayWidth,
                            height = calibration.displayHeight,
                        ),
                    )
                    postUi { calibrationDiagnostics = calibration }
                },
                onFailure = { event ->
                    postUi { applyFailure(event, emitDiagnostic = false) }
                },
                onTrackingStateChanged = { state ->
                    postUi {
                        trackingState = state
                        if (
                            state == StandaloneTrackingState.LOCKED &&
                            currentFailure?.severity == StandaloneFailureSeverity.TRANSIENT
                        ) {
                            currentFailure = null
                        }
                    }
                },
                onFrameTracked = ::consumeTrackedFrame,
                cameraAttitude = cameraAttitude,
                attitudeSample = attitudeSample.takeIf { posePredictionWired },
                attitudeExtrapolator = attitudeExtrapolator.takeIf { posePredictionWired },
                displayPosePredictor = displayPosePredictor.takeIf { posePredictionWired },
                cameraRollDegrees = cameraRollDegrees,
                onMapFromFingerprintSolved = onMapFromFingerprintSolved,
                onPhotosphereKeyframe = { keyframe ->
                    // Base-map update first so the current-tile read below observes the newly captured
                    // tile. This path works before a fingerprint/KPM pose exists.
                    onPhotosphereKeyframe(keyframe)
                    val k = keyframe.intrinsics
                    val keyframeIntrinsics = CameraIntrinsics(
                        fx = k[0],
                        fy = k[1],
                        cx = k[2],
                        cy = k[3],
                        width = keyframe.width,
                        height = keyframe.height,
                    )
                    // Keyframes are the full display-upright analysis frame (the glow lens source);
                    // the map just changed, so hand the glow its new tiles. Projection happens per
                    // draw on the GL thread.
                    glRenderer.setGlowFrameIntrinsics(keyframeIntrinsics)
                    coverageGlowMask.updateRegions(
                        coverageCurrentRegions(),
                        coverageRegionsNeedingUpdate(),
                        coverageRegionsInProgress(),
                    )
                },
                onKeyframeOrientation = onKeyframeOrientation,
                depthEstimator = depthEstimator,
                frameGate = frameGate,
                onFatalError = { error ->
                    postUi {
                        analyzerFatal = true
                        val tooWeak = error as? StandaloneReferenceTooWeakException
                        // A persisted page the host committed before KPM saw it (a replacement): the
                        // host restores the previous target durably, and the reference loader swaps
                        // back to it (restarting this fatal analyzer) when its URI returns.
                        val rejectedUri = loadedReferenceUri.takeIf { tooWeak != null && !referenceNeedsPersistence }
                        if (rejectedUri != null && onPersistedReferenceRejected(rejectedUri)) {
                            referenceReady = false
                            trackingState = StandaloneTrackingState.INITIALIZING
                            currentFailure = null
                            fatalMessage = null
                            onDiagnostic(
                                "SphereSLAM replacement target rejected: " +
                                    "features=${tooWeak?.featureCount} minimum=${tooWeak?.minimumFeatureCount}; " +
                                    "restoring the previous target",
                            )
                            return@postUi
                        }
                        val old = previousReferenceBitmap
                        when (
                            standaloneFatalRecovery(
                                referenceTooWeak = tooWeak != null,
                                hasPreviousValidatedReference = old != null,
                            )
                        ) {
                            StandaloneFatalRecovery.RESTORE_PREVIOUS_AND_RESTART -> {
                                activeReferenceWidthMeters = previousReferenceWidthMeters
                                activeReferencePhysicallyMetric =
                                    previousReferencePhysicallyMetric
                                referenceNeedsPersistence = false
                                previousReferenceBitmap = null
                                referenceReady = false
                                trackingState = StandaloneTrackingState.INITIALIZING
                                currentFailure = null
                                fatalMessage = null
                                referenceBitmap = old
                                // The session that rejected the weak page is terminal: recreate
                                // the analyzer so it tracks the restored page. The base photosphere
                                // tiles/keyframes live in the view model (heading/elevation keyed),
                                // so they survive; the new session re-seeds its rotation-only map
                                // frame from its first attitude and re-solves map_from_fingerprint
                                // on its first lock — exactly as on any pause/resume restart.
                                analyzerFatal = false
                                analyzerRestartGeneration++
                                onDiagnostic(
                                    "SphereSLAM replacement target rejected: " +
                                        "features=${tooWeak?.featureCount} " +
                                        "minimum=${tooWeak?.minimumFeatureCount}; " +
                                        "restored previous validated target, restarting analyzer",
                                )
                            }
                            StandaloneFatalRecovery.REPORT_TOO_WEAK -> {
                                fatalMessage =
                                    "That target has too little trackable detail " +
                                        "(${tooWeak?.featureCount} KPM features; need " +
                                        "${tooWeak?.minimumFeatureCount}). Recapture a richer wall patch."
                            }
                            StandaloneFatalRecovery.REPORT_FAILURE -> {
                                val event = StandaloneFailureClassifier.fromThrowable(error)
                                currentFailure = event
                                fatalMessage = event.userMessage
                            }
                        }
                    }
                },
            )
            analyzer.start()
            standaloneAnalyzerRef.set(analyzer)
            cameraController.setImageAnalysisAnalyzer(executor, analyzer)

            onDispose {
                // Drop worker-posted UI callbacks before the remembered Handler can outlive this
                // standalone overlay composition. removeCallbacksAndMessages only clears ALREADY-queued
                // posts; disposed guards against a still-running analyze() posting AFTER this purge.
                disposed.set(true)
                standaloneAnalyzerRef.compareAndSet(analyzer, null)
                cameraController.clearImageAnalysisAnalyzer()
                mainHandler.removeCallbacksAndMessages(null)
                executor.execute { analyzer.close() }
                executor.shutdown()
                glRenderer.clearPose()
            }
            }
        }
    }

    LaunchedEffect(
        referenceImage,
        runtimeMobileGsFingerprint,
        mobileGsFingerprintFrameVersion,
        mobileGsMapFromFingerprint,
        atlasReferenceImages,
        initialMobileGsWallFeatureMap,
        initialMobileGsWallFeatureMapFrameVersion,
        peerOnlyTracking,
    ) {
        if (!peerOnlyTracking) {
            val loadedAtlas = atlasReferenceImages ?: return@LaunchedEffect
            standaloneAnalyzerRef.get()?.updatePrecisionLayer(
                reference = referenceImage,
                atlasPages = withGrownAtlasPages(loadedAtlas, grownAtlasPages),
                fingerprint = runtimeMobileGsFingerprint,
                fingerprintFrameVersion = mobileGsFingerprintFrameVersion,
                mapFromFingerprint =
                    mobileGsMapFromFingerprint.takeIf { it.size == 16 }?.toFloatArray(),
                wallFeatureMap = initialMobileGsWallFeatureMap,
                wallFeatureMapFrameVersion = initialMobileGsWallFeatureMapFrameVersion,
            )
        }
    }
    AndroidView(
        factory = { ctx ->
            GLSurfaceView(ctx).apply {
                setEGLContextClientVersion(3)
                // RGBA8888 with an alpha channel, before setRenderer. GLSurfaceView's default
                // chooser picks RGB888 with NO alpha; eglCreateWindowSurface then sets the buffer
                // format to RGBX, overriding the TRANSLUCENT holder format below. This media-overlay
                // surface sits ABOVE CameraPreview's SurfaceView, so its transparent glClear
                // composited as opaque black and hid the whole camera feed (dark grey once the
                // 14% glow mask, which glRenderer draws first, washed over it). ARCore mode can get
                // away with the default because its renderer draws the camera background itself;
                // CameraX here cannot.
                setEGLConfigChooser(8, 8, 8, 8, 16, 0)
                setZOrderMediaOverlay(true)
                holder.setFormat(PixelFormat.TRANSLUCENT)
                setRenderer(glRenderer)
                renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            }
        },
        onRelease = { view -> view.queueEvent { glRenderer.release() } },
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { surfaceSize.set(it) },
    )

    // Status, actions and the sweep hint: interactive UI, so the host draws it in its onscreen layer
    // (StandaloneOverlayUiHost); inline only without a host.
    val panel: @Composable () -> Unit = {
        if (!referenceReady || trackingState == StandaloneTrackingState.INITIALIZING) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = standaloneInitializingStatusText(
                            analyzerFatal = analyzerFatal,
                            fatalMessage = fatalMessage,
                            currentFailure = currentFailure,
                            referenceReady = referenceReady,
                            hasReference = referenceImage != null,
                        ),
                        color = Color.White,
                        modifier = Modifier
                            .padding(top = 32.dp)
                            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(24.dp))
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    // A target that never registers (another wall, a stale page) must not trap the artist:
                    // a new one is always one tap away.
                    if (referenceImage != null && newTargetAvailable) {
                        Button(
                            onClick = { requestNewTarget() },
                            modifier = Modifier.padding(top = 12.dp),
                        ) {
                            Text("New Target")
                        }
                    }
                    if (currentFailure != null) {
                        TextButton(onClick = { copyDiagnostics() }) {
                            Text("Copy Diagnostics")
                        }
                    }
                }
            }
        } else if (
            trackingState == StandaloneTrackingState.IMU_BRIDGE ||
            trackingState == StandaloneTrackingState.REACQUIRING ||
            trackingState == StandaloneTrackingState.LOST
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = currentFailure
                            ?.takeIf { it.severity != StandaloneFailureSeverity.FATAL }
                            ?.userMessage
                            ?: if (trackingState == StandaloneTrackingState.LOST) {
                                "Wall target lost"
                            } else {
                                strings.ar.reacquiringTarget
                            },
                        color = Color.White,
                        modifier = Modifier
                            .padding(top = 32.dp)
                            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(24.dp))
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    if (trackingState == StandaloneTrackingState.LOST && newTargetAvailable) {
                        Button(
                            onClick = { requestNewTarget() },
                            modifier = Modifier.padding(top = 12.dp),
                        ) {
                            Text("Recapture Target")
                        }
                    }
                    TextButton(onClick = { copyDiagnostics() }) {
                        Text("Copy Diagnostics")
                    }
                }
            }
        }

        // Phase 3: guided-sweep coverage hint. Only while actively tracking and still incomplete — it
        // disappears once the viewable arc is covered, and never blocks interaction.
        val coverage = sweepCoverage
        if (
            coverage != null &&
            coverage < 1f &&
            referenceReady &&
            trackingState == StandaloneTrackingState.LOCKED &&
            fatalMessage == null
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .padding(bottom = 40.dp)
                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(24.dp))
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                ) {
                    Text(
                        text = "Turn slowly to map the space — ${(coverage * 100f).toInt()}%",
                        color = Color.White,
                    )
                    Box(
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .width(180.dp)
                            .height(3.dp)
                            .background(Color.White.copy(alpha = 0.25f)),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(coverage.coerceIn(0f, 1f))
                                .background(Color.White),
                        )
                    }
                }
            }
        }

        fatalMessage?.let { message ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(16.dp))
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(message, color = Color.White)
                    if (newTargetAvailable) {
                        Button(
                            onClick = { requestNewTarget() },
                            modifier = Modifier.padding(top = 12.dp),
                        ) {
                            Text("Recapture Target")
                        }
                    }
                    TextButton(onClick = { copyDiagnostics() }) {
                        Text("Copy Diagnostics")
                    }
                }
            }
        }
    }
    PublishOrDraw(uiHost, panel)
}

private fun bitmapToLuma(bitmap: Bitmap): ByteArray {
    val width = bitmap.width
    val height = bitmap.height
    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
    return ByteArray(pixels.size) { i ->
        val c = pixels[i]
        val r = (c shr 16) and 0xff
        val g = (c shr 8) and 0xff
        val b = c and 0xff
        ((77 * r + 150 * g + 29 * b) shr 8).toByte()
    }
}

/**
 * The analyzer's complete atlas: the pages persisted when this canonical page was loaded plus those
 * the live session grew since (a persisted page wins a page-number clash).
 */
internal fun withGrownAtlasPages(
    persisted: List<SphereSlamStandaloneAtlasReferenceImage>,
    grown: List<SphereSlamStandaloneAtlasReferenceImage>,
): List<SphereSlamStandaloneAtlasReferenceImage> {
    if (grown.isEmpty()) return persisted
    val known = persisted.mapTo(HashSet()) { it.pageNo }
    return persisted + grown.filter { known.add(it.pageNo) }
}

/**
 * Interactive UI goes to the host's onscreen layer ([StandaloneOverlayUiHost]) when there is one —
 * AzNavRail safe zones, above the rail's touch handling — and is drawn inline otherwise. One call site
 * is composed at a time; leaving it clears the host.
 */
@Composable
private fun PublishOrDraw(host: StandaloneOverlayUiHost?, panel: @Composable () -> Unit) {
    if (host == null) {
        panel()
        return
    }
    val current by rememberUpdatedState(panel)
    DisposableEffect(host) {
        host.content = { current() }
        onDispose { host.content = null }
    }
}

/** One tracked frame's UI-thread values ([LatestValueUiPost]: only the newest is applied). */
private class TrackedFrameUi(
    val tracking: Boolean,
    val match: StandaloneMatchDiagnostics?,
    val screenUnitsPerPixel: Float,
)
