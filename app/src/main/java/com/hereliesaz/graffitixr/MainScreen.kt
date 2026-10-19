// FILE: app/src/main/java/com/hereliesaz/graffitixr/MainScreen.kt
package com.hereliesaz.graffitixr

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.opengl.GLSurfaceView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Color.Companion.Transparent
import com.hereliesaz.graffitixr.common.model.Layer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.hereliesaz.graffitixr.design.R as DesignR
import com.hereliesaz.graffitixr.feature.editor.createColorMatrix
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.hereliesaz.graffitixr.common.model.ArUiState
import com.hereliesaz.graffitixr.common.model.EditorMode
import com.hereliesaz.graffitixr.common.model.EditorUiState
import com.hereliesaz.graffitixr.common.model.ModeAdjustment
import com.hereliesaz.graffitixr.common.model.FeedbackEvent
import android.widget.Toast
import com.hereliesaz.graffitixr.feature.ar.ArViewModel
import com.hereliesaz.graffitixr.feature.ar.CameraPreview
import com.hereliesaz.graffitixr.feature.ar.takePictureAsBitmap
import com.hereliesaz.graffitixr.feature.ar.rendering.ArRenderer
import com.hereliesaz.graffitixr.feature.editor.EditorViewModel
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import com.hereliesaz.graffitixr.design.detectSmartOverlayGestures
import android.graphics.Bitmap as AndroidBitmap
import androidx.core.graphics.createBitmap

@Composable
fun MainScreen(
    uiState: EditorUiState,
    arUiState: ArUiState,
    isTouchLocked: Boolean,
    isCameraActive: Boolean,
    isWaitingForTap: Boolean,
    mainUiState: MainUiState,
    mainViewModel: MainViewModel,
    editorViewModel: EditorViewModel,
    arViewModel: ArViewModel,
    slamManager: SlamManager,
    hasCameraPermission: Boolean,
    cameraController: androidx.camera.view.LifecycleCameraController,
    isExporting: Boolean = false,
    // First-run DETECT stage: enables the renderer's tap-free auto-anchor so the doodle builder
    // has a pose to work against while it looks for the marks the user drew. The scribble itself is
    // NOT projected — it was drawn on the wall in the preceding step, so the overlay shows the user's
    // artwork throughout. Defaulted off — no effect on the normal path.
    doodleDetectActive: Boolean = false,
    // Hand-off of the standalone overlay's interactive UI to the host's onscreen layer (and of the
    // rail's Target action to the overlay). Null: the overlay draws its UI inline.
    standaloneUiHost: com.hereliesaz.graffitixr.feature.ar.StandaloneOverlayUiHost? = null,
) {
    val activeLayer = uiState.design
    val isImageLocked = activeLayer?.isImageLocked ?: false
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // Last isCaptureRequested pushed to the renderer (edge detection; see the AndroidView update).
    val captureRequestApplied = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    val rendererRef = remember { mutableStateOf<ArRenderer?>(null) }
    // ARCore mode publishes metres/pixel through ArRenderer. Standalone SphereSLAM has no
    // ArRenderer, so its CameraX/KPM path publishes the same wall-unit/pixel quantity here.
    var standaloneArUnitsPerPixel by remember { mutableFloatStateOf(0f) }

    val bgColor = if (uiState.editorMode == EditorMode.AR || uiState.editorMode == EditorMode.OVERLAY) Transparent else uiState.canvasBackground
    Box(modifier = Modifier.fillMaxSize().background(bgColor)) {

        // Surface one-off AR failures (camera unavailable, session-init failure) as a toast
        // so the user isn't left on a silent frozen preview.
        LaunchedEffect(Unit) {
            arViewModel.feedback.collect { event ->
                val message = when (event) {
                    is FeedbackEvent.Error -> event.message
                    is FeedbackEvent.Toast -> event.message
                    else -> null
                }
                if (message != null) Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        }

        DisposableEffect(lifecycleOwner) {
            if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                arViewModel.onActivityResumed()
            }

            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> arViewModel.onActivityResumed()
                    Lifecycle.Event.ON_PAUSE -> arViewModel.onActivityPaused()
                    Lifecycle.Event.ON_STOP -> arViewModel.onActivityStopped()
                    else -> {}
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
            }
        }

        // The same AR controls drive two different coordinate frames. Keep their persisted
        // adjustments separate and swap which one EditorViewModel exposes to gestures/undo when
        // backend ownership changes.
        LaunchedEffect(
            uiState.projectId,
            uiState.editorMode,
            arUiState.isArCoreAvailabilityResolved,
            arUiState.isArCoreAvailable,
        ) {
            editorViewModel.setStandaloneArBackendActive(
                uiState.editorMode == EditorMode.AR &&
                    arUiState.isArCoreAvailabilityResolved &&
                    !arUiState.isArCoreAvailable,
            )
        }

        if (hasCameraPermission && isCameraActive && uiState.editorMode != EditorMode.TRACE) {
            val selectedBackendHasDoodle: Boolean? = when {
            !arUiState.isArCoreAvailabilityResolved -> null
            arUiState.isArCoreAvailable -> mainUiState.hasArCoreDoodle
            !arUiState.isSphereSlamAvailabilityResolved -> null
            arUiState.isSphereSlamAvailable -> mainUiState.hasSphereSlamTarget
            else -> null
        }
        LaunchedEffect(
            uiState.editorMode,
            selectedBackendHasDoodle,
            mainUiState.isCapturingDoodle,
            mainUiState.isConfirmingDoodle,
        ) {
            if (
                uiState.editorMode == EditorMode.AR &&
                selectedBackendHasDoodle == false &&
                !mainUiState.isCapturingDoodle &&
                !mainUiState.isConfirmingDoodle
            ) {
                mainViewModel.startDoodleCapture()
            }
        }

        when (uiState.editorMode) {
                EditorMode.AR -> {
                    if (!arUiState.isArCoreAvailabilityResolved) {
                        // Do not start an ARCore Session until capability resolution completes.
                        // CameraX is safe on every supported install and becomes the standalone
                        // SphereSLAM camera if ARCore resolves unavailable.
                        CameraPreview(
                            controller = cameraController,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else if (
                        !arUiState.isArCoreAvailable &&
                        !arUiState.isSphereSlamAvailabilityResolved
                    ) {
                        // ARCore is known unavailable, but don't claim the fallback until the native
                        // KPM smoke probe completes. CameraX is safe to keep warm while this resolves.
                        CameraPreview(
                            controller = cameraController,
                            modifier = Modifier.fillMaxSize(),
                        )
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.TopCenter,
                        ) {
                            androidx.compose.material3.Text(
                                text = "Preparing SphereSLAM…",
                                color = Color.White,
                                modifier = Modifier
                                    .padding(top = 32.dp)
                                    .background(
                                        Color.Black.copy(alpha = 0.55f),
                                        androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
                                    )
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                    } else if (
                        !arUiState.isArCoreAvailable &&
                        !arUiState.isSphereSlamAvailable
                    ) {
                        // This is a packaging/runtime capability failure, not "ARCore unsupported".
                        // Keep AR reachable so the failure is explicit and diagnosable rather than
                        // silently redirecting the artist into a different editor mode.
                        CameraPreview(
                            controller = cameraController,
                            modifier = Modifier.fillMaxSize(),
                        )
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            androidx.compose.material3.Text(
                                text = "SphereSLAM tracking isn't available in this build.",
                                color = Color.White,
                                modifier = Modifier
                                    .background(
                                        Color.Black.copy(alpha = 0.75f),
                                        androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                                    )
                                    .padding(20.dp),
                            )
                        }
                    } else if (!arUiState.isArCoreAvailable) {
                        // ARCore is optional. Keep the user in AR mode and let CameraX +
                        // SphereSLAM/KPM own wall tracking instead of redirecting to Overlay mode.
                        LaunchedEffect(arUiState.isFlashlightOn) {
                            cameraController.enableTorch(arUiState.isFlashlightOn)
                        }
                        // Camera attitude sampling starts with CameraX + the photosphere
                        // (SPHERESLAM_SPHERE_MAP.md §5.1). The analyzer records no photosphere
                        // keyframe without it, so a target capture could never bind to the map.
                        // Released while paused so the compass does not run in the background.
                        DisposableEffect(lifecycleOwner) {
                            if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                                arViewModel.startStandaloneCameraAttitude()
                            }
                            val attitudeObserver = LifecycleEventObserver { _, event ->
                                when (event) {
                                    Lifecycle.Event.ON_RESUME -> arViewModel.startStandaloneCameraAttitude()
                                    Lifecycle.Event.ON_PAUSE -> arViewModel.stopStandaloneCameraAttitude()
                                    else -> {}
                                }
                            }
                            lifecycleOwner.lifecycle.addObserver(attitudeObserver)
                            onDispose {
                                lifecycleOwner.lifecycle.removeObserver(attitudeObserver)
                                arViewModel.stopStandaloneCameraAttitude()
                            }
                        }
                        CameraPreview(
                            controller = cameraController,
                            modifier = Modifier.fillMaxSize(),
                        )

                        LaunchedEffect(
                            arUiState.isCaptureRequested,
                            arUiState.sphereSlamRuntimeStatus.mode,
                        ) {
                            if (
                                arUiState.isCaptureRequested &&
                                arUiState.sphereSlamRuntimeStatus.mode ==
                                    com.hereliesaz.graffitixr.common.model.SphereSlamRuntimeMode.STANDALONE
                            ) {
                                runCatching {
                                    cameraController.takePictureAsBitmap(context)
                                }.onSuccess { bitmap ->
                                    arViewModel.onStandaloneTargetCaptured(bitmap)
                                }.onFailure { error ->
                                    arViewModel.onCaptureRequestHandled()
                                    arViewModel.appendDiag(
                                        "SphereSLAM shared target capture failed: " +
                                            (error.message ?: error.javaClass.simpleName)
                                    )
                                    Toast.makeText(
                                        context,
                                        "Couldn't capture the target — try again.",
                                        Toast.LENGTH_LONG,
                                    ).show()
                                }
                            }
                        }

                        val standaloneDesign = uiState.design?.takeIf {
                            it.isVisible && it.bitmap != null
                        }
                        // EditorViewModel exposes the SphereSLAM-specific adjustment while this
                        // backend owns AR; ARCore's independent adjustment remains persisted offscreen.
                        val standaloneAdj = uiState.modeAdjustments[EditorMode.AR]
                        var standaloneTexture by remember { mutableStateOf<AndroidBitmap?>(null) }

                        // Match ARCore mode's texture treatment: tone fields are baked into the
                        // texture, while pan/scale/rotation stay geometric in the renderer.
                        LaunchedEffect(
                            standaloneDesign,
                            standaloneAdj?.brightness,
                            standaloneAdj?.contrast,
                            standaloneAdj?.saturation,
                            standaloneAdj?.opacity,
                            standaloneAdj?.isInverted,
                        ) {
                            standaloneTexture = if (standaloneDesign == null) {
                                null
                            } else {
                                withContext(Dispatchers.Default) {
                                    compositeDesignForAr(standaloneDesign, standaloneAdj)
                                }
                            }
                        }


                        // Painting progress is about WHAT is being painted, not display tone. Use
                        // the untoned design composite just like ARCore mode does. The standalone
                        // doodle value is part of the key so a newly accepted wall page
                        // re-registers the same design against the new MobileGS wall frame.
                        LaunchedEffect(
                            standaloneDesign,
                            arUiState.sphereSlamFingerprint,
                            arUiState.sphereSlamReferenceUri,
                        ) {
                            if (
                                standaloneDesign != null &&
                                arUiState.sphereSlamFingerprint != null
                            ) {
                                val guide = withContext(Dispatchers.Default) {
                                    compositeDesignForAr(standaloneDesign)
                                }
                                val guideKey =
                                    (standaloneDesign.uri?.toString() ?: "memory") + "|" +
                                        (arUiState.sphereSlamReferenceUri?.toString() ?: "no-page")
                                arViewModel.updateStandalonePaintingGuide(guide, guideKey)
                            }
                        }

                        val sweepCoverageValue by arViewModel.sphereCoverageFraction.collectAsState()
                        com.hereliesaz.graffitixr.feature.ar.SphereSlamStandaloneOverlay(
                            uiHost = standaloneUiHost,
                            isImperialUnits = arUiState.isImperialUnits,
                            cameraController = cameraController,
                            designBitmap = standaloneTexture,
                            slamManager = slamManager,
                            mobileGsDoodle = arUiState.sphereSlamFingerprint,
                            mobileGsDoodleFrameVersion =
                                arUiState.sphereSlamFingerprintFrameVersion,
                            mobileGsMapFromDoodle =
                                arUiState.sphereSlamMapFromFingerprint,
                            mobileGsWallFeatureMap = arUiState.sphereSlamWallFeatureMap,
                            mobileGsWallFeatureMapFrameVersion =
                                arUiState.sphereSlamWallFeatureMapFrameVersion,
                            persistedReferenceUri = arUiState.sphereSlamReferenceUri,
                            persistedReferenceWidthMeters = arUiState.sphereSlamReferenceWidthMeters,
                            persistedReferencePhysicallyMetric =
                                arUiState.sphereSlamReferencePhysicallyMetric,
                            persistedAtlasPages = arUiState.sphereSlamAtlasPages,
                            coopPeerSpatialFrame = arUiState.coopPeerSpatialFrame,
                            coopPeerDoodle = arUiState.coopPeerDoodle,
                            sharedTargetCapture = true,
                            // A co-op guest keeps the session's spatial frame: no re-targeting
                            // (ArViewModel.onScreenTap ignores its taps).
                            onNewTargetRequested = if (
                                arUiState.coopRole ==
                                com.hereliesaz.graffitixr.common.model.CoopRole.GUEST
                            ) {
                                null
                            } else {
                                {
                                    // Same reset as the rail's Target action, then the shared flow.
                                    arViewModel.clearCaptureForRetry()
                                    arViewModel.cancelMeasure()
                                    mainViewModel.startDoodleCapture()
                                }
                            },
                            onReferenceCaptured = { bitmap, widthMeters, physicallyMetric ->
                                arViewModel.saveSphereSlamReference(
                                    bitmap,
                                    widthMeters,
                                    physicallyMetric,
                                )
                            },
                            onPersistedReferenceInvalid = { uri ->
                                // An unreadable replacement rolls back to the previous target.
                                if (!mainViewModel.onStandaloneReferenceRejected(uri)) {
                                    arViewModel.clearSphereSlamReferenceIfMatches(uri)
                                }
                            },
                            onPersistedReferenceRegistered = { uri ->
                                mainViewModel.onStandaloneReferenceRegistered(uri)
                            },
                            onPersistedReferenceRejected = { uri ->
                                mainViewModel.onStandaloneReferenceRejected(uri)
                            },
                            onPersistedAtlasPageInvalid = { pageNo, uri ->
                                arViewModel.clearSphereSlamAtlasPageIfMatches(pageNo, uri)
                            },
                            onAtlasPageCaptured = {
                                    bitmap,
                                    pageNo,
                                    referenceWidthUnits,
                                    physicallyMetric,
                                    canonicalFromPage,
                                ->
                                arViewModel.saveSphereSlamAtlasPage(
                                    bitmap = bitmap,
                                    pageNo = pageNo,
                                    referenceWidthUnits = referenceWidthUnits,
                                    physicallyMetric = physicallyMetric,
                                    canonicalFromPage = canonicalFromPage,
                                )
                            },
                            adjustment = standaloneAdj,
                            onUnitsPerPixel = { standaloneArUnitsPerPixel = it },
                            onTrackingTick = { arViewModel.onStandaloneTrackingTick(it) },
                            onReferenceRegistrationChanged = {
                                arViewModel.onStandaloneReferenceRegistrationChanged(it)
                            },
                            onDiagnostic = { text -> arViewModel.appendDiag(text) },
                            onKeyframeOrientation = { timestampNs, quaternion ->
                                arViewModel.recordStandaloneKeyframeOrientation(timestampNs, quaternion)
                            },
                            onPhotosphereKeyframe = { keyframe ->
                                arViewModel.onStandalonePhotosphereKeyframe(keyframe)
                            },
                            onMapFromFingerprintSolved = { mapFromFingerprint ->
                                arViewModel.onStandaloneMapFromFingerprintSolved(mapFromFingerprint)
                            },
                            depthEstimator = arViewModel.standaloneDepthEstimatorOrNull(),
                            sweepCoverage = sweepCoverageValue,
                            coverageCurrentRegions = {
                                arViewModel.sphereCurrentRegions.value
                            },
                            coverageRegionsNeedingUpdate = {
                                arViewModel.sphereRegionsNeedingUpdate.value
                            },
                            coverageRegionsInProgress = {
                                arViewModel.sphereRegionsInProgress.value
                            },
                            coverageGlowAppearance = arUiState.coverageGlow,
                            cameraAttitude = { cameraFromDevice ->
                                arViewModel.latestCameraAttitude(cameraFromDevice)
                            },
                            // Only with a rotation-vector sensor: without one these stay empty, and
                            // null lets the analyzer fall back to its own game-rotation sensor (frame
                            // attitude + gyro bridge) with no predictor, keeping one sensor per path.
                            attitudeExtrapolator = arViewModel.standaloneAttitudeExtrapolator
                                .takeIf { arViewModel.isStandaloneAttitudeSensorAvailable },
                            attitudeSample = if (arViewModel.isStandaloneAttitudeSensorAvailable) {
                                arViewModel::latestStandaloneAttitudeSample
                            } else {
                                null
                            },
                            cameraTargetFps = arUiState.cameraTargetFps,
                            adaptiveRateEnabled = arUiState.adaptiveRateEnabled,
                            batteryTier = arUiState.batteryTier,
                            perceptionSystemThrottle = arUiState.perceptionSystemThrottle,
                            anticipationSettings = arUiState.anticipation,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        var glView by remember { mutableStateOf<GLSurfaceView?>(null) }
    
                        DisposableEffect(uiState.editorMode) {
                            // Release the CameraX (editor/overlay) camera BEFORE ARCore opens the
                            // device. Otherwise the two camera clients race for the device: ARCore
                            // opening evicts CameraX (ERROR_CAMERA_DEVICE) and CameraX's in-flight
                            // capture-session open then throws an uncaught camera SecurityException
                            // ("Attempt to use camera from a different process than original client"),
                            // hard-crashing the app on AR entry.
                            runCatching { cameraController.unbind() }
                            arViewModel.setArMode(true, context)
                            onDispose {
                                // Fully close the session (not just pause) when leaving AR — e.g. into
                                // the library to load a project, then back via Trace. A paused-but-open
                                // session gets resumed on re-entry instead of rebuilt, which on many
                                // devices comes back as a black/uninitialised camera. exitArMode() tears
                                // it down so the next AR entry creates a fresh session.
                                arViewModel.exitArMode()
                                // Reset in-flight capture state so stale isWaitingForTap doesn't
                                // block gestures on AR re-entry.
                                mainViewModel.cancelTapMode()
                            }
                        }
    
                        // Method-aware layer defaults: on AR entry and whenever the mural method
                        // changes, the matching representation defaults on and the other two off.
                        // Keyed on the method, so a user who manually enables another layer keeps it
                        // until the method changes again.
    
                        DisposableEffect(lifecycleOwner, glView) {
                            if (glView == null) return@DisposableEffect onDispose {}
    
                            if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                                glView?.onResume()
                            }
    
                            val observer = LifecycleEventObserver { _, event ->
                                when (event) {
                                    Lifecycle.Event.ON_RESUME -> glView?.onResume()
                                    Lifecycle.Event.ON_PAUSE -> glView?.onPause()
                                    else -> {}
                                }
                            }
                            lifecycleOwner.lifecycle.addObserver(observer)
                            onDispose {
                                lifecycleOwner.lifecycle.removeObserver(observer)
                            }
                        }
    
                        LaunchedEffect(arUiState.isFlashlightOn, rendererRef.value) {
                            rendererRef.value?.updateFlashlight(arUiState.isFlashlightOn)
                        }

                        // The phone's north-referenced attitude, for MobileGS to turn a lost frame
                        // square-on to the wall. The same sensor the standalone path samples; released
                        // while paused so the compass does not run in the background.
                        DisposableEffect(lifecycleOwner) {
                            if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                                arViewModel.startStandaloneCameraAttitude()
                            }
                            val attitudeObserver = LifecycleEventObserver { _, event ->
                                when (event) {
                                    Lifecycle.Event.ON_RESUME -> arViewModel.startStandaloneCameraAttitude()
                                    Lifecycle.Event.ON_PAUSE -> arViewModel.stopStandaloneCameraAttitude()
                                    else -> {}
                                }
                            }
                            lifecycleOwner.lifecycle.addObserver(attitudeObserver)
                            onDispose {
                                lifecycleOwner.lifecycle.removeObserver(attitudeObserver)
                                arViewModel.stopStandaloneCameraAttitude()
                            }
                        }
                        LaunchedEffect(rendererRef.value) {
                            rendererRef.value?.relocAttitude =
                                if (arViewModel.isStandaloneAttitudeSensorAvailable) {
                                    { arViewModel.latestStandaloneAttitudeSample() }
                                } else {
                                    null
                                }
                        }
    
                        // Dead-camera watchdog. On some devices (e.g. Samsung SM-A236U) the camera HAL
                        // never feeds ARCore — the session opens but no frame ever lands (frame.timestamp
                        // stays 0). Left alone, ARCore's internal camera pipe keeps thrashing the device
                        // through ERROR_CAMERA_DEVICE/reopen cycles, which can end in an uncatchable
                        // teardown crash. If no frame has arrived after the timeout, leave AR: switching
                        // editorMode off AR disposes this branch, whose onDispose calls exitArMode() for a
                        // clean teardown. Keyed on the renderer so it (re)starts with each AR session and
                        // is cancelled the moment we leave AR.
                        LaunchedEffect(rendererRef.value) {
                            val r = rendererRef.value ?: return@LaunchedEffect
                            var startMs = android.os.SystemClock.elapsedRealtime()
                            while (true) {
                                kotlinx.coroutines.delay(1000)
                                // Only time spent with a session attached counts: AR entry (probe,
                                // camera gate) legitimately runs for seconds with no session.
                                startMs = com.hereliesaz.graffitixr.feature.ar.ArCameraHealth.watchStartMs(
                                    startMs,
                                    android.os.SystemClock.elapsedRealtime(),
                                    com.hereliesaz.graffitixr.feature.ar.isArSessionAttached(r),
                                )
                                // Read via a :feature:ar helper that returns a Long — :app can't access
                                // ARCore's Frame type (it's an implementation dep of :feature:ar).
                                val ts = com.hereliesaz.graffitixr.feature.ar.lastArFrameTimestampNs(r)
                                if (ts > 0L) break // camera is streaming — healthy, stop watching
                                val elapsed = android.os.SystemClock.elapsedRealtime() - startMs
                                if (com.hereliesaz.graffitixr.feature.ar.ArCameraHealth.isCameraDead(elapsed, ts)) {
                                    // Tell AR the HAL never streamed: the next AR entry this process drops
                                    // to ARCore's safest default camera config (skips the fps-variant swap),
                                    // a best-effort recovery for budget HALs (e.g. Galaxy A26) that open a
                                    // config but never feed it.
                                    arViewModel.onCameraStreamStalled()
                                    Toast.makeText(
                                        context,
                                        context.getString(DesignR.string.camera_stalled_error),
                                        Toast.LENGTH_LONG
                                    ).show()
                                    editorViewModel.setEditorMode(EditorMode.DESIGN)
                                    break
                                }
                            }
                        }
    
                        val visibleDesign = uiState.design?.takeIf { it.isVisible && it.bitmap != null }
    
                        // Push the AR whole-design adjustment (set via the rail's "Layer" item) to the
                        // renderer, which applies it as an in-plane move/scale/rotate of the overlay along
                        // the wall plane. Offsets are stored in world meters (converted at the gesture site).
                        val arOverlayAdj = uiState.modeAdjustments[EditorMode.AR]
                        LaunchedEffect(arOverlayAdj, rendererRef.value) {
                            rendererRef.value?.let { r ->
                                r.overlayPanX = arOverlayAdj?.offsetX ?: 0f
                                r.overlayPanY = arOverlayAdj?.offsetY ?: 0f
                                r.overlayScale = arOverlayAdj?.scale ?: 1f
                                // Model stores rotationZ CW+ (Compose/y-down convention). The AR
                                // renderer rotates around a camera-facing wall-local +Z, which is
                                // CCW+ under the right-hand rule — negate here so a CW slider drag
                                // and a CW gesture both rotate the overlay CW on the wall.
                                r.overlayRotationDeg = -(arOverlayAdj?.rotation ?: 0f)
                                r.overlayRotationX = arOverlayAdj?.rotationX ?: 0f
                                r.overlayRotationY = arOverlayAdj?.rotationY ?: 0f
                            }
                        }
    
                        // Tone/opacity/invert live on modeAdjustments[AR], not on the renderer, so a
                        // slider change has to re-composite the wall texture. Include the tone-only
                        // fields in the key list; the geometric fields (offset/scale/rotation) do NOT
                        // affect the bitmap contents (the renderer applies them on the quad), so
                        // omitting them avoids a full re-composite on every pan/zoom gesture.
                        // remember() keyed on the primitive tone fields so the list + 5 boxes are only
                        // allocated when a slider actually moves — MainScreen recomposes at tracking
                        // rate (~15fps), so a per-frame allocation storm here shows up in GC.
                        val arToneKey = remember(
                            arOverlayAdj?.brightness, arOverlayAdj?.contrast, arOverlayAdj?.saturation,
                            arOverlayAdj?.opacity, arOverlayAdj?.isInverted,
                        ) {
                            arOverlayAdj?.let {
                                listOf(it.brightness, it.contrast, it.saturation, it.opacity, it.isInverted)
                            }
                        }
                        LaunchedEffect(visibleDesign, arUiState.isAnchorEstablished, arToneKey) {
                            if (!arUiState.isAnchorEstablished || visibleDesign == null) {
                                rendererRef.value?.updateOverlayBitmap(null)
                                return@LaunchedEffect
                            }
    
                            val composite = withContext(Dispatchers.Default) {
                                compositeDesignForAr(visibleDesign, arOverlayAdj)
                            }
                            rendererRef.value?.updateOverlayBitmap(composite)
                        }
    
                        // Split from the effect above, and NOT keyed on the tone: brightness, contrast,
                        // saturation, opacity and invert are how the artist wants the overlay to LOOK on
                        // screen, not what the artwork is. Registering the toned composite as the
                        // validator meant nudging the opacity slider re-ran the detector and re-registered
                        // the design, which resets painting progress — cheap before Phase 4, when the
                        // published value was the current frame's ratio and reappeared on the next reloc
                        // tick, and expensive after it, when progress accumulates over confirmed features
                        // and has to climb back one feature at a time. A tone slider must not cost the
                        // artist their progress bar.
                        //
                        // Composites a second time rather than sharing the bitmap above, because the two
                        // now legitimately differ. Only on a layer change, which is rare — the recompose
                        // storm this file worries about elsewhere is driven by the tone key, and that no
                        // longer reaches here.
                        // Auto-fit (see ArViewModel.designFits): applied as an ordinary, undoable AR edit.
                        LaunchedEffect(Unit) {
                            arViewModel.designFits.collect { fit ->
                                editorViewModel.applyArFit(fit.dx, fit.dy, fit.dThetaRad, fit.scale)
                            }
                        }
                        LaunchedEffect(visibleDesign, arUiState.isAnchorEstablished) {
                            if (!arUiState.isAnchorEstablished || visibleDesign == null) {
                                return@LaunchedEffect
                            }
                            val guide = withContext(Dispatchers.Default) {
                                compositeDesignForAr(visibleDesign)
                            }
                            arViewModel.updatePaintingGuide(guide, visibleDesign.uri?.toString())
                        }
    
                        AndroidView(
                            factory = { ctx ->
                                val renderer = ArRenderer(
                                    context = ctx,
                                    slamManager = slamManager,
                                    onDoodleCaptured = { bmp, cw, ch, depth, dw, dh, stride, intr, viewMat, rot, tapDist, wallPlane, environment ->
                                        arViewModel.onDoodleCaptured(
                                            bmp, depth,
                                            cw, ch,
                                            dw, dh, stride,
                                            intr, viewMat, rot, tapDist, wallPlane, environment
                                        )
                                    },
                                    onTrackingUpdated = { isTracking, mappedPoints, isDepthSupported, yaw, distanceMeters, relDir, isHardwareStereo, centerDepth ->
                                        arViewModel.setTrackingState(
                                            isTracking, mappedPoints, isDepthSupported, yaw, distanceMeters, relDir,
                                            isHardwareStereo, centerDepth
                                        )
                                    },
                                    onLightUpdated = { level ->
                                        arViewModel.updateLightLevel(level)
                                        slamManager.updateLightLevel(level)
                                    },
                                    onDiag = { text ->
                                        arViewModel.appendDiag(text)
                                    },
                                    onCaptureFailed = { e ->
                                        arViewModel.onCaptureFailed(e)
                                    },
                                    onAnchorEstablished = {
                                        arViewModel.onPrimaryAnchorEstablished()
                                    },
                                    onPlaneDetected = {
                                        arViewModel.onFirstPlaneDetected()
                                    },
                                    onDoodleLocked = {
                                        arViewModel.onDoodleLocked()
                                    },
                                    onFlashlightUnavailable = {
                                        arViewModel.onFlashlightUnavailable()
                                    },
                                    onDesignFootprintChanged = { footprint ->
                                        arViewModel.onDesignFootprintChanged(footprint)
                                    }
                                )
                                renderer.hideVisualization = isExporting
                                rendererRef.value = renderer
                                arViewModel.attachSessionToRenderer(renderer)
                                val view = GLSurfaceView(ctx).apply {
                                    setEGLContextClientVersion(3)
                                    // Keep the artwork/perception GL objects across background →
                                    // foreground where the driver allows it. Not guaranteed (the
                                    // context can still be lost), so ArRenderer.onSurfaceCreated
                                    // also rebuilds every GL handle and re-uploads the artwork.
                                    preserveEGLContextOnPause = true
                                    setZOrderMediaOverlay(true)
                                    holder.setFormat(PixelFormat.TRANSLUCENT)
                                    setRenderer(renderer)
                                    renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                                }
                                glView = view
                                view
                            },
                            update = { view ->
                                rendererRef.value?.let { r ->
                                    r.ambientScanEnabled = arUiState.ambientScanEnabled
                                    // Rising edge only: the GL thread clears the flag when it starts the
                                    // capture, but the UI request stays up until the capture lands, and
                                    // any recomposition in between would otherwise re-arm a second one.
                                    val requested = arUiState.isCaptureRequested
                                    if (requested && !captureRequestApplied.get()) r.captureRequested = true
                                    if (!requested) r.captureRequested = false
                                    captureRequestApplied.set(requested)
                                    r.isCapturingDoodle = mainUiState.isCapturingDoodle
                                    r.hideVisualization = isExporting
                                    r.scanPhase = arUiState.scanPhase
                                    r.doodleLockActive = doodleDetectActive
                                    // Independent in-world perception layers (Settings; feature points default off).
                                    r.showFeaturePoints = uiState.showFeaturePoints
                                    r.showPlaneGrids = uiState.showPlaneGrids
                                    r.showPoints = uiState.showPoints
                                    // Perception throttle: system triggers (OR-ed in the VM) + the lag
                                    // trigger's enable flag (evaluated renderer-side).
                                    r.systemThrottle = arUiState.perceptionSystemThrottle
                                    r.lagThrottleEnabled = arUiState.throttleOnLag
                                    // Adaptive idle rate: policy ceilings from the VM + the live
                                    // interaction flag (so a gesture instantly forces full rate).
                                    r.adaptiveRateEnabled = arUiState.adaptiveRateEnabled
                                    r.anticipationSettings = arUiState.anticipation
                                    r.idleRateCeilingFps = arUiState.idleRateCeilingFps
                                    r.activeRateCeilingFps = arUiState.activeRateCeilingFps
                                    r.gestureInProgress = uiState.gestureInProgress
                                }
                            },
                            onRelease = { view ->
                                // The AR view is leaving composition (mode switch / teardown).
                                // Free GL objects on the GL thread while the context is still
                                // alive, then run non-GL teardown (cancels the renderer's
                                // coroutine scope and detaches the session).
                                rendererRef.value?.let { r ->
                                    r.isDestroying = true
                                    view.queueEvent { r.releaseGlResources() }
                                    r.destroy()
                                }
                                rendererRef.value = null
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                EditorMode.OVERLAY -> {
                    LaunchedEffect(arUiState.isFlashlightOn) {
                        cameraController.enableTorch(arUiState.isFlashlightOn)
                    }

                    CameraPreview(
                        controller = cameraController,
                        modifier = Modifier.fillMaxSize()
                    )

                }

                EditorMode.MOCKUP, EditorMode.TRACE -> {}
                // DESIGN needs no camera preview here; the canvas (layers, gestures, drawing) is
                // drawn full-screen by the shared background rendering below, so it fills the
                // screen behind the rail as a background component.
                EditorMode.DESIGN -> {}
            }
        }

        // Camera modes: which rotation axis the last double-tap selected. Shown for every non-Design
        // mode (AR/Overlay/Mockup/Trace) where the axis cycle applies; Design has its own indicator.
        // visible drives RotationAxisFeedback's own enter/exit + auto-dismiss (onFeedbackShown).
        if (uiState.editorMode != EditorMode.DESIGN) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                com.hereliesaz.graffitixr.design.components.RotationAxisFeedback(
                    axis = uiState.activeRotationAxis,
                    visible = uiState.showRotationAxisFeedback,
                    onFeedbackShown = { editorViewModel.onFeedbackShown() },
                    modifier = Modifier.padding(top = 48.dp)
                )
            }
        }

        // A locked mode's transform gestures and Reset are silently ignored by the reducer (see
        // EditorUiState.showLockedFeedback's doc) — previously the ONLY cue was a color highlight
        // on the "Lock" rail sub-item, which can be on a collapsed accordion host. Surface it once,
        // clearing the flag immediately so it doesn't repeat on every subsequent blocked gesture.
        LaunchedEffect(uiState.showLockedFeedback) {
            if (uiState.showLockedFeedback) {
                android.widget.Toast.makeText(
                    context,
                    "Locked — tap Lock again to move this",
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
                editorViewModel.onLockedFeedbackShown()
            }
        }

        // Isolate/Outline falling back to the unchanged input (see EditorUiState.
        // effectFailureMessage's doc) used to leave the rail's "done" highlight lit with no other
        // sign the effect didn't apply.
        LaunchedEffect(uiState.effectFailureMessage) {
            val message = uiState.effectFailureMessage
            if (message != null) {
                android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
                editorViewModel.onEffectFailureMessageShown()
            }
        }
        uiState.backgroundBitmap?.takeIf { uiState.editorMode == EditorMode.MOCKUP }
            ?.let { bmp ->
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = stringResource(DesignR.string.desc_bg_mockup),
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }


        // Overlay ▸ Gyro: tripod stabilisation. Off (and released: sensor listener unregistered)
        // whenever we are not in Overlay with the camera showing, so returning to
        // Overlay never silently re-arms it against a different reference.
        val overlayCameraShowing = uiState.editorMode == EditorMode.OVERLAY && isCameraActive && hasCameraPermission
        // Keyed on the toggle too: keyed on visibility alone, a tap while the camera was already
        // hidden (no permission) armed Gyro with no effect, and it then started on its own once the
        // permission arrived. Now any armed-while-hidden state is released at once.
        LaunchedEffect(overlayCameraShowing, mainUiState.isOverlayGyroActive) {
            mainViewModel.onOverlayCameraShowingChanged(overlayCameraShowing)
        }
        var overlayViewSize by remember { mutableStateOf(IntSize.Zero) }
        val gyroHomography = com.hereliesaz.graffitixr.feature.ar.rememberOverlayGyroCompensation(
            active = overlayCameraShowing && mainUiState.isOverlayGyroActive,
            cameraController = cameraController,
            viewWidth = overlayViewSize.width,
            viewHeight = overlayViewSize.height,
            onRelease = { reason ->
                mainViewModel.setOverlayGyroActive(false)
                val message = when (reason) {
                    com.hereliesaz.graffitixr.feature.ar.OverlayGyroRelease.MOVED ->
                        context.getString(DesignR.string.gyro_released_moved)
                    com.hereliesaz.graffitixr.feature.ar.OverlayGyroRelease.SENSOR_UNAVAILABLE ->
                        context.getString(DesignR.string.gyro_unavailable)
                }
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            },
        )
        val gyroMatrix = remember { Matrix() }

        // Overlay draws the edited design over the camera on every device; only AR renders it GL-side.
        if (uiState.editorMode != EditorMode.AR) {
            // Per-mode whole-design adjustment: position/scale/rotate/fade and tone the entire
            // composited design as a unit for this mode (DESIGN mode is the global, unadjusted view).
            val modeAdj = if (uiState.editorMode != EditorMode.DESIGN) {
                uiState.modeAdjustments[uiState.editorMode] ?: ModeAdjustment()
            } else {
                ModeAdjustment()
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { overlayViewSize = it }
                    // Overlay ▸ Gyro's compensation, applied in SCREEN space around everything below
                    // (outside the mode adjustment's graphicsLayer), so the user's edits and Overlay
                    // adjustments are untouched and it simply falls away (null) when Gyro is off.
                    // Read in the draw phase only: per-frame updates redraw, never recompose.
                    .drawWithContent {
                        val h = if (uiState.editorMode == EditorMode.OVERLAY) gyroHomography.value else null
                        if (h == null) {
                            drawContent()
                        } else {
                            gyroMatrix.setValues(h)
                            drawIntoCanvas { it.nativeCanvas.save(); it.nativeCanvas.concat(gyroMatrix) }
                            drawContent()
                            drawIntoCanvas { it.nativeCanvas.restore() }
                        }
                    }
                    .graphicsLayer {
                        translationX = modeAdj.offsetX
                        translationY = modeAdj.offsetY
                        scaleX = modeAdj.scale
                        scaleY = modeAdj.scale
                        // Tilt the whole design about its own width/height (X/Y) and spin about its
                        // normal (Z), so the double-tap axis cycle works in Overlay/Mockup/Trace too.
                        rotationX = modeAdj.rotationX
                        rotationY = modeAdj.rotationY
                        rotationZ = modeAdj.rotation
                        alpha = modeAdj.opacity
                        transformOrigin = TransformOrigin.Center
                        // Fade each draw instead of rendering this whole group into an offscreen
                        // buffer. The default (Auto) goes offscreen as soon as alpha < 1, which
                        // isolates the design's blend mode inside a transparent buffer: Multiply,
                        // Screen etc. then blend against nothing instead of the camera/mockup
                        // behind, so the preview stopped matching ExportManager.composite (which
                        // blends the design straight onto the background with the combined alpha).
                        // There is a single design inside, so per-draw alpha is exact.
                        compositingStrategy =
                            androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha
                    }
            ) {
                uiState.design?.takeIf { it.isVisible }?.let { layer ->
                    androidx.compose.runtime.key(layer.id) {
                        layer.bitmap?.let { displayBmp ->
                            val imageBitmap = remember(displayBmp) { displayBmp.asImageBitmap() }
                            // Memoize the colour filter. createColorMatrix() was rebuilt on EVERY
                            // recomposition for EVERY layer — a per-frame allocation storm that makes
                            // the whole screen lag. Recompute only when the inputs actually change.
                            val colorFilter = remember(
                                layer.saturation, layer.contrast, layer.brightness,
                                layer.colorBalanceR, layer.colorBalanceG, layer.colorBalanceB,
                                layer.isInverted, modeAdj.saturation, modeAdj.contrast,
                                modeAdj.brightness, modeAdj.isInverted
                            ) {
                                ColorFilter.colorMatrix(
                                    createColorMatrix(
                                        saturation = layer.saturation * modeAdj.saturation,
                                        contrast = layer.contrast * modeAdj.contrast,
                                        brightness = layer.brightness + modeAdj.brightness,
                                        colorBalanceR = layer.colorBalanceR,
                                        colorBalanceG = layer.colorBalanceG,
                                        colorBalanceB = layer.colorBalanceB,
                                        isInverted = layer.isInverted != modeAdj.isInverted
                                    )
                                )
                            }
                            // Offscreen compositing forces a full-screen offscreen buffer + extra pass
                            // per layer, every frame. It's only needed to isolate a non-default blend
                            // mode; for normal (SrcOver) layers, Auto avoids that cost.
                            val needsOffscreen =
                                layer.blendMode != androidx.compose.ui.graphics.BlendMode.SrcOver
                            Image(
                                bitmap = imageBitmap,
                                contentDescription = null,
                                colorFilter = colorFilter,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        translationX = layer.offset.x
                                        translationY = layer.offset.y
                                        scaleX = layer.scale
                                        scaleY = layer.scale
                                        rotationX = layer.rotationX
                                        rotationY = layer.rotationY
                                        rotationZ = layer.rotationZ
                                        alpha = layer.opacity
                                        transformOrigin = TransformOrigin.Center
                                        blendMode = layer.blendMode
                                        compositingStrategy = if (needsOffscreen)
                                            androidx.compose.ui.graphics.CompositingStrategy.Offscreen
                                        else
                                            androidx.compose.ui.graphics.CompositingStrategy.Auto
                                    },
                                contentScale = ContentScale.Fit
                            )
                        }
                    }
                }
            }
        }

        val isGuest = arUiState.coopRole == com.hereliesaz.graffitixr.common.model.CoopRole.GUEST
        val isMeasuring = arUiState.measure.active && uiState.editorMode == EditorMode.AR

        if (isCameraActive) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(uiState.design?.id, isImageLocked, isWaitingForTap, isTouchLocked, isGuest, uiState.editorMode, isMeasuring) {
                        // Measure taps are wall readings, not edits, so a guest may measure too —
                        // but not through a touch lock.
                        if (isMeasuring && !isTouchLocked) {
                            detectTapGestures { offset ->
                                arViewModel.onMeasureTap(offset.x / size.width, offset.y / size.height)
                            }
                            return@pointerInput
                        }
                        // Guests edit too since co-op v4: their edits are sent to the host. Target
                        // capture stays host-only (below).

                        // Outside Design there is only ONE layer (the whole design), so transform
                        // gestures always edit the mode adjustment — no "Layer" toggle. This isn't gated
                        // by the active layer's image lock, so double-tap to cycle the rotation axis works
                        // even while the artwork is locked for painting.
                        val editingMode = uiState.editorMode != EditorMode.DESIGN
                        if (isWaitingForTap) {
                            // Not for a guest: a new target would re-anchor only this device, and no
                            // Op carries that, so the peers' spatial frames would split.
                            if (!isGuest) {
                                detectTapGestures { offset ->
                                    val nx = offset.x / size.width
                                    val ny = offset.y / size.height
                                    // The pixel size lets standalone undo the FILL_CENTER preview
                                    // crop; this Box and CameraPreview both fill the same screen.
                                    arViewModel.onScreenTap(nx, ny, size.width, size.height)
                                }
                            }
                        } else if (!isTouchLocked && (editingMode || !isImageLocked)) {
                            detectTapGestures(
                                onDoubleTap = { editorViewModel.onCycleRotationAxis() },
                                // A plain tap no longer creates an AR target. Target creation only
                                // happens while the Target button is selected (isWaitingForTap):
                                // once a target is accepted the user must re-select that button to
                                // make another, and the active layer takes over screen gestures.
                                onTap = { editorViewModel.onDismissPanel() }
                            )
                        }
                    }
                    .pointerInput(uiState.design?.id, isImageLocked, isWaitingForTap, isTouchLocked, isGuest, uiState.editorMode, isMeasuring) {
                        // While measuring, a drag must not also move the artwork it is measuring under.
                        if (isMeasuring) return@pointerInput
                        // Outside Design the whole design is the single layer, so transform gestures
                        // always drive the mode adjustment instead of a per-layer transform.
                        val editingMode = uiState.editorMode != EditorMode.DESIGN
                        // editingMode edits the whole design, so it isn't gated by the active layer's
                        // image lock (the mode has its own Lock via isTransformLocked, in the reducer).
                        if (!isTouchLocked && !isWaitingForTap && (editingMode || (!isImageLocked && activeLayer != null))) {
                            detectSmartOverlayGestures(
                                getValidBounds = { androidx.compose.ui.geometry.Rect(0f, 0f, size.width.toFloat(), size.height.toFloat()) },
                                onGestureStart = { editorViewModel.onGestureStart() },
                                onGestureEnd = { editorViewModel.onGestureEnd() },
                                onGesture = { _, pan, zoom, rotation ->
                                    // Model convention: positive rotationZ is clockwise in
                                    // screen coordinates, matching Compose's graphicsLayer
                                    // (y-down, CW+) and calculateRotation()'s return value.
                                    // Pass the delta through unchanged so both slider and
                                    // gesture yield the same visual direction in Design/Mockup/
                                    // Overlay/Trace. The AR renderer alone rotates around a
                                    // camera-facing +Z (CCW+ in OpenGL/right-handed convention),
                                    // so the sign flip lives there (see the LaunchedEffect that
                                    // pushes overlayRotationDeg to the renderer).
                                    val turn = rotation
                                    if (editingMode) {
                                        // In AR the overlay lives on the wall in meters, so convert
                                        // the screen-pixel drag to in-plane meters. Local +X on the
                                        // wall is camera-right (matches screen +X), local +Y is
                                        // wall-up (opposite screen +Y), so x passes through and y
                                        // is negated so the artwork follows the finger.
                                        val adjustedPan = if (uiState.editorMode == EditorMode.AR) {
                                            val mpp = rendererRef.value?.currentMetersPerPixel
                                                ?: standaloneArUnitsPerPixel
                                            androidx.compose.ui.geometry.Offset(pan.x * mpp, -pan.y * mpp)
                                        } else pan
                                        editorViewModel.onModeTransformGesture(uiState.editorMode, adjustedPan, zoom, turn)
                                    } else {
                                        editorViewModel.onTransformGesture(pan, zoom, turn)
                                    }
                                }
                            )
                        }
                    }
            ) {}
        }

        if (uiState.isLoading && !isExporting) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(56.dp),
                    color = Color(0xFFFFAA00),
                    strokeWidth = 4.dp
                )
            }
        }

    }
}

/**
 * Composites the visible layers into the AR wall texture.
 *
 * [modeAdj] is the whole-design ModeAdjustment for AR (from `uiState.modeAdjustments[EditorMode.AR]`).
 * Its geometric fields (offset/scale/rotation) are applied by the renderer on the quad, so this
 * function only consumes its tone fields — brightness / contrast / saturation / opacity / isInverted
 * — and folds them into the per-layer ColorMatrix and paint alpha exactly the way the Design/Overlay/
 * Mockup/Trace paths do it (see the `graphicsLayer{alpha}` + per-Image ColorFilter block above).
 * Passing null means "no whole-design tone" (Design mode baseline).
 */
internal fun compositeDesignForAr(design: Layer?, modeAdj: ModeAdjustment? = null): AndroidBitmap {
    val layers = listOfNotNull(design)
    if (layers.isEmpty()) return createBitmap(1, 1, AndroidBitmap.Config.ARGB_8888)

    var minX = Float.MAX_VALUE
    var minY = Float.MAX_VALUE
    // -Float.MAX_VALUE, not Float.MIN_VALUE (the smallest *positive* float): a layer panned into
    // negative screen coordinates would otherwise never update max, undersizing the composite.
    var maxX = -Float.MAX_VALUE
    var maxY = -Float.MAX_VALUE

    for (layer in layers) {
        val bmp = layer.bitmap ?: continue
        val halfW = bmp.width / 2f * layer.scale
        val halfH = bmp.height / 2f * layer.scale
        // The layer is drawn rotated about its center, so the composite must be sized to the ROTATED
        // bounding box. Using the unrotated half-extents here undersized the canvas and clipped the
        // corners of any rotated layer.
        val rad = Math.toRadians(layer.rotationZ.toDouble())
        val cos = kotlin.math.abs(kotlin.math.cos(rad)).toFloat()
        val sin = kotlin.math.abs(kotlin.math.sin(rad)).toFloat()
        val rotHalfW = halfW * cos + halfH * sin
        val rotHalfH = halfW * sin + halfH * cos
        val left = layer.offset.x - rotHalfW
        val top = layer.offset.y - rotHalfH
        val right = layer.offset.x + rotHalfW
        val bottom = layer.offset.y + rotHalfH

        if (left < minX) minX = left
        if (top < minY) minY = top
        if (right > maxX) maxX = right
        if (bottom > maxY) maxY = bottom
    }

    val rawWidth = (maxX - minX).coerceAtLeast(1f)
    val rawHeight = (maxY - minY).coerceAtLeast(1f)

    // Cap the composite size: a zoomed-in (large layer.scale) or far-panned layer can otherwise
    // request a multi-hundred-MB ARGB_8888 bitmap and OOM. Downscale proportionally past the cap so
    // the result still fits as an overlay texture; downscale == 1 leaves the common case untouched.
    val maxDim = 4096f
    val downscale = minOf(1f, maxDim / rawWidth, maxDim / rawHeight)
    val width = (rawWidth * downscale).toInt().coerceAtLeast(1)
    val height = (rawHeight * downscale).toInt().coerceAtLeast(1)

    val result = createBitmap(width, height, AndroidBitmap.Config.ARGB_8888)
    val canvas = Canvas(result)
    // Layer matrices are built in raw composite coordinates; this maps them into the capped bitmap.
    canvas.scale(downscale, downscale)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    // Whole-design tone/opacity. Defaults leave the layer's own values untouched (multiply-by-1,
    // add-0, XOR-false) so a null modeAdj degrades cleanly to the original per-layer behaviour.
    val modeSaturation = modeAdj?.saturation ?: 1f
    val modeContrast = modeAdj?.contrast ?: 1f
    val modeBrightness = modeAdj?.brightness ?: 0f
    val modeOpacity = modeAdj?.opacity ?: 1f
    val modeInvert = modeAdj?.isInverted ?: false

    for (layer in layers) {
        val bmp = layer.bitmap ?: continue
        // This composite runs on Dispatchers.Default; the bitmap could be recycled on the main
        // thread (design replaced, mode transition) after we captured our reference but before
        // the draw. Skip a recycled bitmap instead of crashing with
        // "Canvas: trying to use a recycled bitmap".
        if (bmp.isRecycled) continue
        // Whole-design opacity multiplies the per-layer alpha, matching Design's outer
        // `graphicsLayer { alpha = modeAdj.opacity }` wrapping the per-Image `alpha = layer.opacity`.
        val combinedAlpha = (layer.opacity * modeOpacity).coerceIn(0f, 1f)
        paint.alpha = (combinedAlpha * 255).toInt()
        val cm = createColorMatrix(
            // Design mode composes per-layer × whole-design for sat/contrast (multiplicative),
            // brightness (additive), invert (XOR). Mirror that exactly, so the wall texture matches
            // what the user sees in Design mode.
            saturation = layer.saturation * modeSaturation,
            contrast = layer.contrast * modeContrast,
            brightness = layer.brightness + modeBrightness,
            colorBalanceR = layer.colorBalanceR,
            colorBalanceG = layer.colorBalanceG,
            colorBalanceB = layer.colorBalanceB,
            isInverted = layer.isInverted != modeInvert
        )
        paint.colorFilter = android.graphics.ColorMatrixColorFilter(
            android.graphics.ColorMatrix(cm.values)
        )
        val matrix = Matrix()
        matrix.postTranslate(-bmp.width / 2f, -bmp.height / 2f)
        matrix.postScale(layer.scale, layer.scale)
        matrix.postRotate(layer.rotationZ)
        matrix.postTranslate(layer.offset.x - minX, layer.offset.y - minY)
        canvas.drawBitmap(bmp, matrix, paint)
    }
    return result
}
