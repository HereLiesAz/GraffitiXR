package com.hereliesaz.graffitixr.feature.ar

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.view.LifecycleCameraController
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.viewinterop.AndroidView
import com.hereliesaz.graffitixr.common.model.Fingerprint
import com.hereliesaz.graffitixr.common.model.WallFeatureMap
import com.hereliesaz.graffitixr.common.model.ModeAdjustment
import com.hereliesaz.graffitixr.common.model.SphereSlamAtlasPage
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import com.hereliesaz.graffitixr.common.util.PerspectiveProcessor
import com.hereliesaz.graffitixr.design.theme.rememberAppStrings
import com.hereliesaz.graffitixr.feature.ar.rendering.HomographyOverlayRenderer
import com.hereliesaz.sphereslam.CoverageGlowProjection
import com.hereliesaz.sphereslam.SphereCoverage
import com.hereliesaz.sphereslam.SphereSlamStandaloneSession
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val SPHERESLAM_DEFAULT_UNWARP_POINTS = listOf(
    Offset(0.25f, 0.25f),
    Offset(0.75f, 0.25f),
    Offset(0.75f, 0.75f),
    Offset(0.25f, 0.75f),
)


/**
 * Draws the inverse of the usual point-glow: a translucent wash covers the whole preview and only
 * photosphere tiles that are current are cut transparent. Missing and stale tiles therefore glow by
 * default, exactly matching PhotosphereMap.needsUpdate semantics.
 */
private class SphereTileGlowMaskView(context: android.content.Context) : View(context) {
    private data class MaskState(
        val currentDirections: List<SphereCoverage.Direction> = emptyList(),
        val cameraHeadingDeg: Float = 0f,
        val cameraElevationDeg: Float = 0f,
        val horizontalFovDeg: Float = 60f,
        val verticalFovDeg: Float = 45f,
    )

    private val state = AtomicReference(MaskState())
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(36, 255, 255, 255)
        style = Paint.Style.FILL
    }
    private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private val clearRect = RectF()

    init {
        // CLEAR compositing is deterministic on the supported API range with a software layer.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        setWillNotDraw(false)
    }

    fun update(
        currentDirections: List<SphereCoverage.Direction>,
        cameraHeadingDeg: Float? = null,
        cameraElevationDeg: Float? = null,
        horizontalFovDeg: Float? = null,
        verticalFovDeg: Float? = null,
    ) {
        val previous = state.get()
        state.set(
            previous.copy(
                currentDirections = currentDirections.toList(),
                cameraHeadingDeg = cameraHeadingDeg ?: previous.cameraHeadingDeg,
                cameraElevationDeg = cameraElevationDeg ?: previous.cameraElevationDeg,
                horizontalFovDeg = horizontalFovDeg ?: previous.horizontalFovDeg,
                verticalFovDeg = verticalFovDeg ?: previous.verticalFovDeg,
            )
        )
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        val snapshot = state.get()
        val layer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), glowPaint)

        if (snapshot.currentDirections.isNotEmpty()) {
            val marks = CoverageGlowProjection.project(
                directions = snapshot.currentDirections,
                cameraHeadingDeg = snapshot.cameraHeadingDeg,
                cameraElevationDeg = snapshot.cameraElevationDeg,
                horizontalFovDeg = snapshot.horizontalFovDeg,
                verticalFovDeg = snapshot.verticalFovDeg,
            )

            // PhotosphereMap is configured with SphereCoverage's 12-sector default and three
            // elevation bands. Convert that angular tile footprint to the current camera frustum.
            val tileWidthDeg =
                (SphereCoverage.DEFAULT_VIEWABLE_HALF_ANGLE_DEG * 2f) /
                    SphereCoverage.DEFAULT_SECTORS.toFloat()
            val tileHeightDeg =
                (SphereCoverage.DEFAULT_VIEWABLE_ELEVATION_HALF_ANGLE_DEG * 2f) / 3f
            val halfWidthPx =
                width * 0.5f *
                    (kotlin.math.tan(Math.toRadians(tileWidthDeg / 2.0)).toFloat() /
                        kotlin.math.tan(Math.toRadians(snapshot.horizontalFovDeg / 2.0)).toFloat())
            val halfHeightPx =
                height * 0.5f *
                    (kotlin.math.tan(Math.toRadians(tileHeightDeg / 2.0)).toFloat() /
                        kotlin.math.tan(Math.toRadians(snapshot.verticalFovDeg / 2.0)).toFloat())

            for (mark in marks) {
                if (!mark.onScreen) continue
                val cx = (mark.ndcX + 1f) * 0.5f * width
                val cy = (1f - mark.ndcY) * 0.5f * height
                clearRect.set(
                    cx - halfWidthPx,
                    cy - halfHeightPx,
                    cx + halfWidthPx,
                    cy + halfHeightPx,
                )
                canvas.drawRect(clearRect, clearPaint)
            }
        }

        canvas.restoreToCount(layer)
    }
}

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
    onPersistedReferenceInvalid: (Uri) -> Unit = {},
    onPersistedAtlasPageInvalid: (Int, Uri) -> Unit = { _, _ -> },
    onAtlasPageCaptured: (Bitmap, Int, Float, Boolean, FloatArray) -> Unit =
        { _, _, _, _, _ -> },
    adjustment: ModeAdjustment? = null,
    onUnitsPerPixel: (Float) -> Unit = {},
    onTrackingTick: (Boolean) -> Unit = {},
    onReferenceRegistrationChanged: (Boolean) -> Unit = {},
    onDiagnostic: (String) -> Unit = {},
    /**
     * Per-keyframe device orientation `(timestampNs, quaternion[x,y,z,w])` sampled at each atlas-growth
     * keyframe — Phase 1b of the spherical-coverage map (`docs/SPHERESLAM_SPHERE_MAP.md`). Storage only;
     * the view model gates recording on the feature-map flag and persists the log. Fired on the camera
     * worker thread, so the handler must be thread-safe.
     */
    onKeyframeOrientation: (Long, FloatArray) -> Unit = { _, _ -> },
    /**
     * Phase 2: optional monocular depth source. Non-null (feature-map flag on) opts this session into
     * depth-calibrated radial map-point placement; null is the classic wall-plane path. Owned by the
     * view model — the analyzer uses it but never closes it.
     */
    depthEstimator: com.hereliesaz.graffitixr.feature.ar.depth.DepthEstimator? = null,
    /**
     * Phase 3 guided-sweep coverage over the wall's viewable arc, in `[0, 1]`, or null to hide the
     * hint (classic path / flag off). Shown while locked and still incomplete, to nudge the artist to
     * keep pivoting until the surrounding map is dense enough for fast re-lock. Guides, never gates
     * (§9.1).
     */
    sweepCoverage: Float? = null,
    /**
     * Still-unscanned coverage directions for the "map more here" glow, read per tracked frame.
     * Empty (the default, or once coverage is complete) draws nothing. Fed from the view model's
     * [com.hereliesaz.sphereslam.SphereCoverage.thinDirections].
     */
    coverageGlowDirections: () -> List<com.hereliesaz.sphereslam.SphereCoverage.Direction> = { emptyList() },
    /** Current tiles are the ONLY regions cut out of the otherwise full-screen glow. */
    coverageCurrentDirections: () -> List<com.hereliesaz.sphereslam.SphereCoverage.Direction> = { emptyList() },
    /** Latest camera attitude `(headingDeg, elevationDeg)` for projecting the glow, or null. */
    cameraAttitude: () -> Pair<Float, Float>? = { null },
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
        if (referenceBitmap != null) return@LaunchedEffect
        val restored = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
            }.getOrNull()
        }
        if (restored != null) {
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
    if (reference == null && !peerOnlyTracking) {
        if (sharedTargetCapture) return
        val pending = pendingReferenceBitmap
        if (pending != null) {
            val measuredWidth = StandaloneReferenceScale.parseMeters(referenceWidthInput)
            Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
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
            return
        }

        val raw = rawCaptureBitmap
        if (raw == null) {
            Box(modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
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
    val candidateUncommitted = referenceNeedsPersistence || awaitingReferencePersistence
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
    val glRenderer = remember(context) { HomographyOverlayRenderer(context) }
    // Full-view freshness mask: glow everywhere, then punch out only current photosphere tiles.
    val coverageGlowMaskView = remember(context) { SphereTileGlowMaskView(context) }

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
        )
        context.getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText("GraffitiXR SphereSLAM diagnostics", dump))
        onDiagnostic("SphereSLAM standalone diagnostic dump copied")
    }

    fun consumeTrackedFrame(frame: SphereSlamStandaloneFrame?) {
        // Renderer state is atomic/volatile and intentionally updated directly from the analysis
        // worker. Compose state belongs to Main and is posted there separately.
        if (frame == null) {
            glRenderer.clearPose()
        } else {
            glRenderer.updatePose(frame.viewMatrix, frame.projMatrix, frame.frameAspect)
            // The glow is the complement of tile freshness: the entire view glows, and only
            // photosphere tiles whose needsUpdate=false state is current are punched clear.
            val attitude = cameraAttitude()
            val currentDirections = coverageCurrentDirections()
            coverageGlowDirections() // keep the update-side signal hot/read alongside its complement
            if (attitude != null) {
                val px = frame.projMatrix[0]
                val py = frame.projMatrix[5]
                if (px > 1e-4f && py > 1e-4f) {
                    val hFovDeg = Math.toDegrees(2.0 * kotlin.math.atan(1.0 / px)).toFloat()
                    val vFovDeg = Math.toDegrees(2.0 * kotlin.math.atan(1.0 / py)).toFloat()
                    coverageGlowMaskView.update(
                        currentDirections = currentDirections,
                        cameraHeadingDeg = attitude.first,
                        cameraElevationDeg = attitude.second,
                        horizontalFovDeg = hFovDeg,
                        verticalFovDeg = vFovDeg,
                    )
                }
            } else {
                coverageGlowMaskView.update(currentDirections = emptyList())
            }
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
        postUi {
            if (peerOnlyTracking) {
                trackingState =
                    if (frame != null) StandaloneTrackingState.LOCKED
                    else StandaloneTrackingState.REACQUIRING
            }
            if (frame != null) {
                matchDiagnostics = StandaloneMatchDiagnostics(
                    pageNo = frame.pageNo,
                    inliers = frame.inlierCount,
                    reprojectionError = frame.reprojectionError,
                    observationAgeMs = frame.observationAgeMs,
                    matchDurationMs = frame.matchDurationMs,
                    source = frame.source,
                )
            }
            onUnitsPerPixel(screenUnitsPerPixel)
            onTrackingTick(frame != null)
        }
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

    DisposableEffect(
        cameraController,
        cameraId,
        referenceImage,
        runtimeMobileGsFingerprint,
        mobileGsFingerprintFrameVersion,
        atlasReferenceImages,
        peerOnlyTracking,
        coopPeerSpatialFrame,
        coopPeerFingerprint,
    ) {
        // Re-arm the UI-post gate: a prior disposal set this true, and this fresh analyzer's posts
        // must be delivered again.
        disposed.set(false)
        val id = cameraId
        val restoredAtlas = atlasReferenceImages
        if (id == null || restoredAtlas == null) {
            onDispose {}
        } else {
            val executor = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "sphereslam-standalone-camera").apply { isDaemon = true }
            }
            if (peerOnlyTracking) {
                val peerSpatial = requireNotNull(coopPeerSpatialFrame)
                val peerFingerprint = requireNotNull(coopPeerFingerprint)
                val analyzer = CoopPeerFingerprintAnalyzer(
                    context = context,
                    cameraId = id,
                    slam = slamManager,
                    peerFingerprint = peerFingerprint,
                    spatialFrame = peerSpatial,
                    onFrameTracked = ::consumeTrackedFrame,
                    onDiagnostic = { text -> postUi { onDiagnostic(text) } },
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
            } else {
                val analyzer = SphereSlamStandaloneTrackingAnalyzer(
                context = context,
                cameraId = id,
                referenceImage = requireNotNull(referenceImage),
                atlasReferenceImages = restoredAtlas,
                slamManager = slamManager,
                mobileGsFingerprint = runtimeMobileGsFingerprint,
                mobileGsFingerprintFrameVersion = mobileGsFingerprintFrameVersion,
                mobileGsWallFeatureMap = initialMobileGsWallFeatureMap,
                mobileGsWallFeatureMapFrameVersion = initialMobileGsWallFeatureMapFrameVersion,
                onReferenceReady = { registered: SphereSlamStandaloneSession.Reference ->
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
                        }
                    }
                },
                onAtlasPageAdded = { candidate ->
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
                    postUi { onDiagnostic(text) }
                },
                onCalibrationChanged = { calibration ->
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
                onKeyframeOrientation = onKeyframeOrientation,
                depthEstimator = depthEstimator,
                onFatalError = { error ->
                    postUi {
                        if (error is StandaloneReferenceTooWeakException) {
                            val old = previousReferenceBitmap
                            if (old != null) {
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
                                onDiagnostic(
                                    "SphereSLAM replacement target rejected: " +
                                        "features=${error.featureCount} " +
                                        "minimum=${error.minimumFeatureCount}; " +
                                        "restored previous validated target",
                                )
                            } else {
                                fatalMessage =
                                    "That target has too little trackable detail " +
                                        "(${error.featureCount} KPM features; need " +
                                        "${error.minimumFeatureCount}). Recapture a richer wall patch."
                            }
                        } else {
                            val event = StandaloneFailureClassifier.fromThrowable(error)
                            currentFailure = event
                            fatalMessage = event.userMessage
                        }
                    }
                },
            )
            analyzer.start()
            cameraController.setImageAnalysisAnalyzer(executor, analyzer)

            onDispose {
                // Drop worker-posted UI callbacks before the remembered Handler can outlive this
                // standalone overlay composition. removeCallbacksAndMessages only clears ALREADY-queued
                // posts; disposed guards against a still-running analyze() posting AFTER this purge.
                disposed.set(true)
                cameraController.clearImageAnalysisAnalyzer()
                mainHandler.removeCallbacksAndMessages(null)
                executor.execute { analyzer.close() }
                executor.shutdown()
                glRenderer.clearPose()
            }
            }
        }
    }

    AndroidView(
        factory = { ctx ->
            GLSurfaceView(ctx).apply {
                setEGLContextClientVersion(3)
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

    // Full-screen freshness mask, above the design overlay and camera preview. It starts fully
    // glowing and clears only the screen regions occupied by current (needsUpdate=false) tiles.
    AndroidView(
        factory = { coverageGlowMaskView },
        modifier = Modifier.fillMaxSize(),
    )

    if (!referenceReady || trackingState == StandaloneTrackingState.INITIALIZING) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = currentFailure
                        ?.takeIf { it.severity != StandaloneFailureSeverity.FATAL }
                        ?.userMessage
                        ?: if (!referenceReady) "Preparing wall tracker…" else "Finding wall target…",
                    color = Color.White,
                    modifier = Modifier
                        .padding(top = 32.dp)
                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(24.dp))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
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
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
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
                if (trackingState == StandaloneTrackingState.LOST) {
                    Button(
                        onClick = { resetCapture() },
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
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
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
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(16.dp))
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(message, color = Color.White)
                Button(
                    onClick = { resetCapture() },
                    modifier = Modifier.padding(top = 12.dp),
                ) {
                    Text("Recapture Target")
                }
                TextButton(onClick = { copyDiagnostics() }) {
                    Text("Copy Diagnostics")
                }
            }
        }
    }
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
