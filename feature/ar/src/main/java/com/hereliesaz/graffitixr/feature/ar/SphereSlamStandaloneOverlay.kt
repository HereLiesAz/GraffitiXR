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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import com.hereliesaz.graffitixr.common.model.ModeAdjustment
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import com.hereliesaz.graffitixr.common.util.PerspectiveProcessor
import com.hereliesaz.graffitixr.design.theme.rememberAppStrings
import com.hereliesaz.graffitixr.feature.ar.rendering.HomographyOverlayRenderer
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
    persistedReferenceUri: Uri? = null,
    persistedReferenceWidthMeters: Float = 1f,
    persistedReferencePhysicallyMetric: Boolean = false,
    onReferenceCaptured: (Bitmap, Float, Boolean) -> Unit = { _, _, _ -> },
    onPersistedReferenceInvalid: (Uri) -> Unit = {},
    adjustment: ModeAdjustment? = null,
    onUnitsPerPixel: (Float) -> Unit = {},
    onDiagnostic: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val strings = rememberAppStrings()
    val scope = rememberCoroutineScope()
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val surfaceSize = remember { AtomicReference(IntSize.Zero) }

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
    var referenceWidthUnits by remember { mutableStateOf(0f) }
    var referenceHeightUnits by remember { mutableStateOf(0f) }
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
        activeReferenceWidthMeters = widthMeters
        activeReferencePhysicallyMetric = physicallyMetric
        pendingReferenceBitmap = null
        referenceWidthInput = ""
        referenceNeedsPersistence = true
        referenceBitmap = bitmap
    }

    val reference = referenceBitmap
    if (reference == null) {
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
        SphereSlamStandaloneReferenceImage(
            luma = bitmapToLuma(reference),
            width = reference.width,
            height = reference.height,
            referenceWidthMeters = activeReferenceWidthMeters,
            physicallyMetric = activeReferencePhysicallyMetric,
        )
    }
    val glRenderer = remember(context) { HomographyOverlayRenderer(context) }

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

    LaunchedEffect(glRenderer, designBitmap, referenceWidthUnits, referenceHeightUnits) {
        val fit = fitStandaloneDesignHalfExtents(
            pageWidthUnits = referenceWidthUnits,
            pageHeightUnits = referenceHeightUnits,
            designWidthPx = designBitmap?.width,
            designHeightPx = designBitmap?.height,
        ) ?: return@LaunchedEffect
        glRenderer.setExtent(fit.halfWidth, fit.halfHeight)
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
        val dump = standaloneDiagnosticDump(
            calibration = calibrationDiagnostics,
            trackingState = trackingState,
            match = matchDiagnostics,
            physicallyMetric = activeReferencePhysicallyMetric,
            referenceWidthUnits = activeReferenceWidthMeters,
            failure = currentFailure,
        )
        context.getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText("GraffitiXR SphereSLAM diagnostics", dump))
        onDiagnostic("SphereSLAM standalone diagnostic dump copied")
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

    DisposableEffect(cameraController, cameraId, referenceImage, mobileGsFingerprint) {
        val id = cameraId
        if (id == null) {
            onDispose {}
        } else {
            val executor = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "sphereslam-standalone-camera").apply { isDaemon = true }
            }
            val analyzer = SphereSlamStandaloneTrackingAnalyzer(
                context = context,
                cameraId = id,
                referenceImage = referenceImage,
                slamManager = slamManager,
                mobileGsFingerprint = mobileGsFingerprint,
                onReferenceReady = { registered: SphereSlamStandaloneSession.Reference ->
                    val g = registered.geometry
                    mainHandler.post {
                        referenceWidthUnits = g.widthMeters
                        referenceHeightUnits = g.heightMeters
                        referenceReady = true
                        if (referenceNeedsPersistence) {
                            referenceBitmap?.let { accepted ->
                                onReferenceCaptured(
                                    accepted,
                                    activeReferenceWidthMeters,
                                    activeReferencePhysicallyMetric,
                                )
                            }
                            referenceNeedsPersistence = false
                            previousReferenceBitmap = null
                        }
                    }
                },
                onDiagnostic = { text ->
                    mainHandler.post { onDiagnostic(text) }
                },
                onCalibrationChanged = { calibration ->
                    mainHandler.post { calibrationDiagnostics = calibration }
                },
                onFailure = { event ->
                    mainHandler.post { applyFailure(event, emitDiagnostic = false) }
                },
                onTrackingStateChanged = { state ->
                    mainHandler.post {
                        trackingState = state
                        if (
                            state == StandaloneTrackingState.LOCKED &&
                            currentFailure?.severity == StandaloneFailureSeverity.TRANSIENT
                        ) {
                            currentFailure = null
                        }
                    }
                },
                onFrameTracked = { frame ->
                    // Renderer state is atomic/volatile and intentionally updated directly from the
                    // analysis worker. Compose state belongs to Main and is posted there separately.
                    if (frame == null) {
                        glRenderer.clearPose()
                    } else {
                        glRenderer.updatePose(frame.viewMatrix, frame.projMatrix, frame.frameAspect)
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
                    mainHandler.post {
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
                    }
                },
                onFatalError = { error ->
                    mainHandler.post {
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
                cameraController.clearImageAnalysisAnalyzer()
                // Drop worker-posted UI callbacks before the remembered Handler can outlive this
                // standalone overlay composition. Native teardown is serialized behind any in-flight
                // analyze() call on the same executor.
                mainHandler.removeCallbacksAndMessages(null)
                executor.execute { analyzer.close() }
                executor.shutdown()
                glRenderer.clearPose()
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
