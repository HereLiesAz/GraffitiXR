// FILE: app/src/main/java/com/hereliesaz/graffitixr/MainActivity.kt
package com.hereliesaz.graffitixr

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import android.content.ClipData
import android.content.ClipboardManager as AndroidClipboardManager
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.google.android.gms.common.GoogleApiAvailability
import com.hereliesaz.aznavrail.*
import com.hereliesaz.aznavrail.model.*
import com.hereliesaz.graffitixr.common.model.ArScanMode
import com.hereliesaz.graffitixr.common.model.CaptureStep
import com.hereliesaz.graffitixr.common.model.ScanPhase
import com.hereliesaz.graffitixr.common.model.EditorMode
import com.hereliesaz.graffitixr.common.model.EditorUiState
import com.hereliesaz.graffitixr.common.model.Tool
import com.hereliesaz.graffitixr.common.model.ArUiState
import com.hereliesaz.graffitixr.common.security.SecurityProviderManager
import com.hereliesaz.graffitixr.common.security.SecurityProviderState
import com.hereliesaz.graffitixr.common.util.ImageProcessor
import com.hereliesaz.graffitixr.design.components.InfoDialog
import com.hereliesaz.graffitixr.design.components.TouchLockOverlay
import com.hereliesaz.graffitixr.design.components.UnlockInstructionsPopup
import com.hereliesaz.graffitixr.design.theme.Cyan
import com.hereliesaz.graffitixr.design.theme.GraffitiXRTheme
import com.hereliesaz.graffitixr.design.theme.HotPink
import com.hereliesaz.graffitixr.design.theme.NeonGreen
import com.hereliesaz.graffitixr.design.theme.NavStrings
import com.hereliesaz.graffitixr.feature.ar.ArViewModel
import com.hereliesaz.graffitixr.feature.ar.TargetCreationBackground
import com.hereliesaz.graffitixr.feature.ar.TargetCreationUi
import com.hereliesaz.graffitixr.feature.ar.rememberCameraController
import com.hereliesaz.graffitixr.feature.dashboard.DashboardViewModel
import com.hereliesaz.graffitixr.feature.dashboard.ProjectLibraryScreen
import com.hereliesaz.graffitixr.feature.dashboard.SaveProjectDialog
import com.hereliesaz.graffitixr.feature.dashboard.SettingsScreen
import com.hereliesaz.graffitixr.feature.editor.EditorUi
import com.hereliesaz.graffitixr.feature.editor.EditorViewModel
import com.hereliesaz.graffitixr.feature.editor.util.ImageProcessor as EditorImageProcessor
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var slamManager: SlamManager
    @Inject lateinit var projectRepository: com.hereliesaz.graffitixr.domain.repository.ProjectRepository
    @Inject lateinit var securityProviderManager: SecurityProviderManager

    private val arViewModel: ArViewModel by viewModels()

    var showSaveDialog by mutableStateOf(false)
    var showLibrary by mutableStateOf(true)
    var showSettings by mutableStateOf(false)
    var showHelpDialog by mutableStateOf(false)
    var hasCameraPermission by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { p ->
        hasCameraPermission = p[Manifest.permission.CAMERA] ?: false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        hasCameraPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        securityProviderManager.installAsync(this)
        slamManager.ensureInitialized()

        lifecycleScope.launch {
            securityProviderManager.securityProviderState.collect { state ->
                if (state is SecurityProviderState.RecoverableError) {
                    GoogleApiAvailability.getInstance().getErrorDialog(this@MainActivity, state.errorCode, 9000)?.show()
                }
            }
        }

        setContent {
            GraffitiXRTheme {
                val navController = rememberNavController()

                @Suppress("DEPRECATION")
                val mainViewModel: MainViewModel = hiltViewModel()
                @Suppress("DEPRECATION")
                val editorViewModel: EditorViewModel = hiltViewModel()
                @Suppress("DEPRECATION")
                val dashboardViewModel: DashboardViewModel = hiltViewModel()
                val cameraController = rememberCameraController()

                val editorUiState by editorViewModel.uiState.collectAsState()
                val mainUiState by mainViewModel.uiState.collectAsState()
                val arUiState by arViewModel.uiState.collectAsState()
                val dashboardNavigation by dashboardViewModel.navigationTrigger.collectAsState()

                var isProcessing by remember { mutableStateOf(false) }

                val currentTempCapture = arUiState.tempCaptureBitmap
                val currentCaptureStep = mainUiState.captureStep
                val isWaitingForTap = mainUiState.isWaitingForTap

                LaunchedEffect(currentTempCapture, currentCaptureStep, isWaitingForTap) {
                    if (currentTempCapture != null) {
                        if (currentCaptureStep == CaptureStep.CAPTURE) {
                            mainViewModel.setCaptureStep(CaptureStep.REVIEW)
                        } else if (isWaitingForTap) {
                            mainViewModel.confirmTapCapture()
                        }
                    }
                }

                LaunchedEffect(dashboardNavigation) {
                    dashboardNavigation?.let { destination ->
                        when (destination) {
                            "project_library" -> showLibrary = true
                            "settings" -> showSettings = true
                        }
                        dashboardViewModel.onNavigationConsumed()
                    }
                }

                LaunchedEffect(navController) {
                    navController.currentBackStackEntryFlow.collect { entry ->
                        val route = entry.destination.route
                        if (route != null) {
                            try {
                                val mode = EditorMode.valueOf(route)
                                if (editorUiState.editorMode != mode) editorViewModel.setEditorMode(mode)
                            } catch (_: Exception) { }
                        }
                    }
                }

                // If the anchor is lost while confirmation is pending (e.g. project reload),
                // auto-clear so the hidden rail is never left with no escape path.
                LaunchedEffect(arUiState.isAnchorEstablished) {
                    if (!arUiState.isAnchorEstablished && mainViewModel.uiState.value.planeConfirmationPending) {
                        mainViewModel.confirmPlane()
                    }
                }

                // Back-press escape hatches — defined lowest-priority first (Compose uses LIFO).
                BackHandler(enabled = showLibrary) { showLibrary = false }
                BackHandler(enabled = showSettings) { showSettings = false }
                BackHandler(enabled = mainUiState.planeConfirmationPending && !mainUiState.isInPlaneRealignment) {
                    mainViewModel.confirmPlane()
                }
                BackHandler(enabled = mainUiState.isInPlaneRealignment) {
                    mainViewModel.endPlaneRealignment()
                }
                BackHandler(enabled = mainUiState.isCapturingTarget) {
                    mainViewModel.cancelTapMode()
                    arViewModel.clearTapHighlights()
                }
                BackHandler(enabled = mainUiState.isTouchLocked) {
                    mainViewModel.setTouchLocked(false)
                }

                val isRailVisible = !editorUiState.hideUiForCapture &&
                        !mainUiState.isTouchLocked &&
                        !mainUiState.isCapturingTarget &&
                        !mainUiState.planeConfirmationPending &&
                        !showLibrary &&
                        !showSettings

                LaunchedEffect(Unit) {
                    if (!hasCameraPermission) {
                        permissionLauncher.launch(
                            arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION)
                        )
                    }
                }

                val overlayImagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
                    uri?.let { editorViewModel.onAddLayer(it) }
                }
                val backgroundImagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
                    uri?.let { editorViewModel.setBackgroundImage(it) }
                }

                val navStrings = remember { NavStrings() }
                var showHelp by remember { mutableStateOf(false) }
                var showFontPicker by remember { mutableStateOf(false) }
                var fontPickerLayerId by remember { mutableStateOf<String?>(null) }

                AzHostActivityLayout(navController = navController, initiallyExpanded = false) {

                    azTheme(
                        activeColor = Cyan,
                        defaultShape = AzButtonShape.RECTANGLE,
                        headerIconShape = AzHeaderIconShape.ROUNDED
                    )
                    azConfig(
                        packButtons = true,
                        dockingSide = if (editorUiState.isRightHanded) AzDockingSide.LEFT else AzDockingSide.RIGHT
                    )
                    azAdvanced(
                        helpEnabled = showHelp,
                        onDismissHelp = { showHelp = false }
                    )

                    if (isRailVisible) {
                        configureRailItems(
                            mainViewModel, editorViewModel, arViewModel, dashboardViewModel,
                            overlayImagePicker, backgroundImagePicker, editorUiState, arUiState, navStrings,
                            onShowFontPicker = { layerId -> fontPickerLayerId = layerId; showFontPicker = true }
                        )
                    }

                    background(weight = 0) {
                        MainScreen(
                            uiState = editorUiState,
                            arUiState = arUiState,
                            isTouchLocked = mainUiState.isTouchLocked,
                            isCameraActive = !showLibrary,
                            isWaitingForTap = mainUiState.isWaitingForTap,
                            mainUiState = mainUiState,
                            editorViewModel = editorViewModel,
                            arViewModel = arViewModel,
                            slamManager = slamManager,
                            hasCameraPermission = hasCameraPermission,
                            cameraController = cameraController,
                            onRendererCreated = { _ -> }
                        )

                        if (mainUiState.isCapturingTarget) {
                            TargetCreationBackground(
                                uiState = arUiState,
                                captureStep = mainUiState.captureStep,
                                onInitUnwarpPoints = { arViewModel.setUnwarpPoints(it) }
                            )
                        }
                    }

                    onscreen {
                        var fullSize by remember { mutableStateOf(IntSize.Zero) }

                        Box(Modifier.fillMaxSize().onSizeChanged { fullSize = it }) {
                            AzNavHost(startDestination = EditorMode.MOCKUP.name) {
                                composable(EditorMode.AR.name) { EditorOverlay(editorViewModel, mainUiState) }
                                composable(EditorMode.OVERLAY.name) { EditorOverlay(editorViewModel, mainUiState) }
                                composable(EditorMode.MOCKUP.name) { EditorOverlay(editorViewModel, mainUiState) }
                                composable(EditorMode.TRACE.name) { EditorOverlay(editorViewModel, mainUiState) }
                            }

                            if (mainUiState.isTouchLocked) {
                                var showUnlockInstructions by remember(mainUiState.isTouchLocked) { mutableStateOf(true) }
                                LaunchedEffect(Unit) {
                                    kotlinx.coroutines.delay(3000)
                                    showUnlockInstructions = false
                                }
                                TouchLockOverlay(
                                    isLocked = true,
                                    onUnlockRequested = { mainViewModel.setTouchLocked(false) }
                                )
                                UnlockInstructionsPopup(visible = showUnlockInstructions)
                            }

                            val isScanningPhase = editorUiState.editorMode == EditorMode.AR
                                    && arUiState.arScanMode == ArScanMode.GAUSSIAN_SPLATS
                                    && arUiState.scanPhase != ScanPhase.COMPLETE
                            if (isScanningPhase && !mainUiState.isCapturingTarget && !showLibrary && !showSettings) {
                                ScanCoachingOverlay(
                                    splatCount = arUiState.splatCount,
                                    hint = arUiState.scanHint,
                                    scanPhase = arUiState.scanPhase,
                                    ambientSectorsCovered = arUiState.ambientSectorsCovered,
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .padding(bottom = 96.dp)
                                )
                            }

                            val showDepthWarning = editorUiState.editorMode == EditorMode.AR
                                    && arUiState.arScanMode == ArScanMode.GAUSSIAN_SPLATS
                                    && !arUiState.isDepthApiSupported
                                    && arUiState.splatCount == 0
                            if (showDepthWarning && !showLibrary && !showSettings) {
                                DepthApiUnsupportedBanner(
                                    modifier = Modifier
                                        .align(Alignment.TopCenter)
                                        .padding(top = 16.dp)
                                )
                            }

                            if (mainUiState.isWaitingForTap && !showLibrary && !showSettings) {
                                TapTargetOverlay(
                                    onCancel = {
                                        mainViewModel.cancelTapMode()
                                        arViewModel.clearTapHighlights()
                                    },
                                    modifier = Modifier.align(Alignment.BottomCenter)
                                )
                            }

                            LaunchedEffect(mainUiState.planeConfirmationPending) {
                                arViewModel.setPlaneConfirmationBorder(mainUiState.planeConfirmationPending)
                            }

                            // Hide SLAM/cloud visualization once the anchor is confirmed —
                            // processing continues but the point cloud / splats are not drawn.
                            LaunchedEffect(arUiState.isAnchorEstablished) {
                                arViewModel.setVisualizationHidden(arUiState.isAnchorEstablished)
                            }

                            LaunchedEffect(arUiState.targetPhysicalExtent) {
                                arUiState.targetPhysicalExtent?.let { (w, h) ->
                                    editorViewModel.setAnchorExtent(w, h)
                                }
                            }

                            val showPlaneConfirm = mainUiState.planeConfirmationPending
                                    && !mainUiState.isInPlaneRealignment
                                    && arUiState.isAnchorEstablished
                                    && editorUiState.editorMode == EditorMode.AR
                                    && !showLibrary && !showSettings
                            if (showPlaneConfirm) {
                                PlaneConfirmOverlay(
                                    onConfirm = { mainViewModel.confirmPlane() },
                                    onRedetect = { mainViewModel.beginPlaneRealignment() },
                                    modifier = Modifier.align(Alignment.BottomCenter)
                                )
                            }

                            val showRealignment = mainUiState.isInPlaneRealignment
                                    && editorUiState.editorMode == EditorMode.AR
                                    && !showLibrary && !showSettings
                            if (showRealignment) {
                                PlaneRealignmentOverlay(
                                    onTryThisPlane = {
                                        arViewModel.retriggerPlaneDetection()
                                        mainViewModel.endPlaneRealignment()
                                    },
                                    onCancel = { mainViewModel.endPlaneRealignment() },
                                    modifier = Modifier.align(Alignment.BottomCenter)
                                )
                            }

                            val showProgress = editorUiState.editorMode == EditorMode.AR
                                    && arUiState.isAnchorEstablished
                                    && arUiState.paintingProgress > 0.01f
                                    && !mainUiState.isCapturingTarget
                                    && !showLibrary && !showSettings
                            if (showProgress) {
                                PaintingProgressIndicator(
                                    progress = arUiState.paintingProgress,
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(top = 16.dp, end = 16.dp)
                                )
                            }

                            val distanceM = arUiState.distanceToAnchorMeters
                            if (editorUiState.editorMode == EditorMode.AR
                                && arUiState.isAnchorEstablished
                                && distanceM > 0f
                                && !showLibrary && !showSettings
                            ) {
                                DistanceBadge(
                                    distanceMeters = distanceM,
                                    imperial = arUiState.isImperialUnits,
                                    modifier = Modifier
                                        .align(Alignment.TopStart)
                                        .padding(top = 16.dp, start = 16.dp)
                                )
                            }

                            if (editorUiState.editorMode == EditorMode.AR && editorUiState.showDiagOverlay) {
                                DiagPopup(
                                    diagLog = arUiState.diagLog,
                                    modifier = Modifier.align(Alignment.TopStart)
                                )
                            }

                            if (mainUiState.isCapturingTarget) {
                                TargetCreationUi(
                                    uiState = arUiState,
                                    isRightHanded = editorUiState.isRightHanded,
                                    captureStep = mainUiState.captureStep,
                                    isLoading = isProcessing,
                                    onConfirm = { _, mask ->
                                        arViewModel.setInitialAnchorFromCapture()
                                        mainViewModel.onConfirmTargetCreation(arUiState.tempCaptureBitmap, mask)
                                    },
                                    onRetake = {
                                        mainViewModel.onRetakeCapture()
                                        if (mainUiState.captureOriginatedFromTap) {
                                            arViewModel.clearTapHighlights()
                                        } else {
                                            arViewModel.requestCapture()
                                        }
                                    },
                                    onCancel = {
                                        mainViewModel.onCancelCaptureClicked()
                                    },
                                    onUnwarpConfirm = { points ->
                                        val currentBitmap = arUiState.tempCaptureBitmap
                                        if (currentBitmap != null && points.size == 4) {
                                            isProcessing = true
                                            lifecycleScope.launch(Dispatchers.Default) {
                                                val unwarped = ImageProcessor.unwarpImage(currentBitmap, points)
                                                if (unwarped != null) {
                                                    arViewModel.setTempCapture(unwarped)
                                                }
                                                mainViewModel.setCaptureStep(CaptureStep.MASK)
                                                isProcessing = false
                                            }
                                        } else {
                                            mainViewModel.setCaptureStep(CaptureStep.MASK)
                                        }
                                    },
                                    onMaskConfirmed = { bitmap ->
                                        arViewModel.setTempCapture(bitmap)
                                        mainViewModel.setCaptureStep(CaptureStep.REVIEW)
                                    },
                                    onRequestCapture = { arViewModel.requestCapture() },
                                    onUpdateUnwarpPoints = { arViewModel.setUnwarpPoints(it) },
                                    onSetActiveUnwarpPoint = { arViewModel.setActiveUnwarpPoint(it) },
                                    onSetMagnifierPosition = { arViewModel.setMagnifierPosition(it) },
                                    onUpdateMaskPath = { path -> path?.let { arViewModel.updateMaskPath(it) } }
                                )

                                if (mainUiState.captureStep == CaptureStep.REVIEW && fullSize != IntSize.Zero) {
                                    val latestArViewModel by rememberUpdatedState(arViewModel)
                                    val latestArUiState by rememberUpdatedState(arUiState)

                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .fillMaxHeight(0.8f)
                                            .pointerInput(Unit) {
                                                var dragBmpW = 1
                                                var dragBmpH = 1
                                                detectDragGestures(
                                                    onDragStart = { _ ->
                                                        val bmp = latestArUiState.tempCaptureBitmap
                                                        dragBmpW = bmp?.width ?: 1
                                                        dragBmpH = bmp?.height ?: 1
                                                        latestArViewModel.beginErase()
                                                    },
                                                    onDrag = { change, _ ->
                                                        change.consume()
                                                        val mapped = EditorImageProcessor.mapScreenToBitmap(
                                                            listOf(change.position),
                                                            fullSize.width, fullSize.height,
                                                            dragBmpW, dragBmpH
                                                        )
                                                        if (mapped.isNotEmpty()) {
                                                            val pt = mapped.first()
                                                            latestArViewModel.eraseAtPoint(
                                                                pt.x / dragBmpW.toFloat(),
                                                                pt.y / dragBmpH.toFloat()
                                                            )
                                                        }
                                                    }
                                                )
                                            }
                                    ) {
                                        Text(
                                            text = "Drag over marks to remove them.",
                                            color = HotPink,
                                            modifier = Modifier
                                                .align(Alignment.TopCenter)
                                                .padding(top = 100.dp)
                                                .background(Color(0xBB000000), RoundedCornerShape(8.dp))
                                                .border(1.dp, Color.Cyan, RoundedCornerShape(8.dp))
                                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                        )
                                        Row(
                                            modifier = Modifier
                                                .align(Alignment.BottomCenter)
                                                .padding(bottom = 12.dp),
                                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                            OutlinedButton(
                                                onClick = { arViewModel.undoErase() },
                                                enabled = arUiState.canUndoErase,
                                                colors = ButtonDefaults.outlinedButtonColors(contentColor = HotPink)
                                            ) { Text("Undo") }
                                            OutlinedButton(
                                                onClick = { arViewModel.redoErase() },
                                                enabled = arUiState.canRedoErase,
                                                colors = ButtonDefaults.outlinedButtonColors(contentColor = HotPink)
                                            ) { Text("Redo") }
                                        }
                                    }
                                }
                            }

                            if (showSaveDialog) {
                                SaveProjectDialog(
                                    initialName = editorUiState.projectId ?: "New Project",
                                    onDismissRequest = { showSaveDialog = false },
                                    onSaveRequest = { name ->
                                        editorViewModel.saveProject(name)
                                        showSaveDialog = false
                                    }
                                )
                            }

                            if (showFontPicker) {
                                FontPickerDialog(
                                    onFontSelected = { fontName ->
                                        fontPickerLayerId?.let { editorViewModel.onTextFontChanged(it, fontName) }
                                        showFontPicker = false
                                    },
                                    onDismiss = { showFontPicker = false }
                                )
                            }

                            if (showHelpDialog) {
                                InfoDialog(
                                    title = "GraffitiXR Guide",
                                    content = "Select a tool from the Design menu to edit your layers. To transform (scale, rotate, move) a layer, close the layer's tools. Double tap the screen to cycle between X, Y, and Z rotation axes.",
                                    onDismiss = { showHelpDialog = false }
                                )
                            }

                            if (showLibrary) {
                                val dashboardState by dashboardViewModel.uiState.collectAsState()
                                LaunchedEffect(Unit) { dashboardViewModel.loadAvailableProjects() }
                                ProjectLibraryScreen(
                                    projects = dashboardState.availableProjects,
                                    onLoadProject = {
                                        dashboardViewModel.openProject(it)
                                        showLibrary = false
                                    },
                                    onDeleteProject = { dashboardViewModel.deleteProject(it) },
                                    onNewProject = {
                                        dashboardViewModel.onNewProject(editorUiState.isRightHanded)
                                        showLibrary = false
                                    },
                                    onImportProject = { uri ->
                                        dashboardViewModel.importProject(uri)
                                    },
                                    onClose = { showLibrary = false }
                                )
                            }

                            if (showSettings) {
                                val dashboardUiState by dashboardViewModel.uiState.collectAsState()
                                SettingsScreen(
                                    currentVersion = BuildConfig.VERSION_NAME,
                                    updateStatus = dashboardUiState.updateStatusMessage,
                                    isCheckingForUpdate = dashboardUiState.isCheckingForUpdate,
                                    isRightHanded = editorUiState.isRightHanded,
                                    onHandednessChanged = { editorViewModel.toggleHandedness() },
                                    showDiagOverlay = editorUiState.showDiagOverlay,
                                    onDiagOverlayChanged = { editorViewModel.toggleDiagOverlay() },
                                    arScanMode = arUiState.arScanMode,
                                    onArScanModeChanged = { arViewModel.setArScanMode(it) },
                                    showAnchorBoundary = arUiState.showAnchorBoundary,
                                    onAnchorBoundaryChanged = { arViewModel.setShowAnchorBoundary(it) },
                                    isImperialUnits = arUiState.isImperialUnits,
                                    onImperialUnitsChanged = { arViewModel.setImperialUnits(it) },
                                    onCheckForUpdates = { dashboardViewModel.checkForUpdates(BuildConfig.VERSION_NAME) },
                                    onInstallUpdate = { dashboardViewModel.installUpdate(this@MainActivity) },
                                    onClose = { showSettings = false }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun EditorOverlay(viewModel: EditorViewModel, mainUiState: MainUiState) {
        val uiState by viewModel.uiState.collectAsState()
        EditorUi(
            actions = viewModel,
            uiState = uiState,
            isTouchLocked = mainUiState.isTouchLocked,
            showUnlockInstructions = mainUiState.showUnlockInstructions,
            isCapturingTarget = mainUiState.isCapturingTarget
        )
    }

    override fun onResume() {
        super.onResume()
        hasCameraPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        arViewModel.destroyArSession()
        if (isFinishing) slamManager.destroy()
    }

    private fun AzNavHostScope.configureRailItems(
        mainViewModel: MainViewModel,
        editorViewModel: EditorViewModel,
        arViewModel: ArViewModel,
        dashboardViewModel: DashboardViewModel,
        overlayPicker: androidx.activity.compose.ManagedActivityResultLauncher<PickVisualMediaRequest, android.net.Uri?>,
        backgroundPicker: androidx.activity.compose.ManagedActivityResultLauncher<PickVisualMediaRequest, android.net.Uri?>,
        editorUiState: EditorUiState,
        arUiState: ArUiState,
        navStrings: NavStrings,
        onShowFontPicker: (String) -> Unit = {}
    ) {
        val requestPermissions = {
            permissionLauncher.launch(
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION)
            )
        }

        azRailHostItem(id = "mode_host", text = navStrings.modes, color = Color.White, info = navStrings.modesInfo)
        azRailSubItem(id = "ar", hostId = "mode_host", text = navStrings.arMode, route = EditorMode.AR.name, color = Color.White, shape = AzButtonShape.NONE, info = navStrings.arModeInfo)
        azRailSubItem(id = "overlay", hostId = "mode_host", text = navStrings.overlay, route = EditorMode.OVERLAY.name, color = Color.White, shape = AzButtonShape.NONE, info = navStrings.overlayInfo)
        azRailSubItem(id = "mockup", hostId = "mode_host", text = navStrings.mockup, route = EditorMode.MOCKUP.name, color = Color.White, shape = AzButtonShape.NONE, info = navStrings.mockupInfo)
        azRailSubItem(id = "trace", hostId = "mode_host", text = navStrings.trace, route = EditorMode.TRACE.name, color = Color.White, shape = AzButtonShape.NONE, info = navStrings.traceInfo)

        azDivider()

        val isArMode = editorUiState.editorMode == EditorMode.AR

        if (isArMode) {
            azRailHostItem(id = "target_host", text = navStrings.grid, color = Color.White, info = navStrings.gridInfo)

            azRailSubItem(id = "create", hostId = "target_host", text = navStrings.create, color = Color.White, shape = AzButtonShape.NONE, info = navStrings.createInfo) {
                if (hasCameraPermission) mainViewModel.startTargetCapture() else requestPermissions()
            }

            azDivider()
        }

        val canEdit = if (isArMode)
            arUiState.scanPhase == ScanPhase.COMPLETE || arUiState.isAnchorEstablished
        else true

        if (canEdit) {
            azRailHostItem(id = "design_host", text = navStrings.design, color = Color.White, info = navStrings.designInfo)
            azRailSubItem(id = "add_img", hostId = "design_host", text = "Image", color = Color.White, shape = AzButtonShape.NONE, info = navStrings.openInfo) {
                overlayPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
            azRailSubItem(id = "add_draw", hostId = "design_host", text = "Draw", color = Color.White, shape = AzButtonShape.NONE, info = navStrings.drawInfo) {
                editorViewModel.onAddBlankLayer()
            }
            azRailSubItem(id = "add_text", hostId = "design_host", text = "Text", color = Color.White, shape = AzButtonShape.NONE) {
                editorViewModel.onAddTextLayer()
            }

            if (editorUiState.editorMode == EditorMode.MOCKUP) {
                azRailSubItem(id = "wall", hostId = "design_host", text = navStrings.wall, color = Color.White, shape = AzButtonShape.NONE, info = navStrings.wallInfo) {
                    backgroundPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
            }

            azDivider()
        }

        azRailHostItem(id = "project_host", text = navStrings.project, color = Color.White, info = navStrings.projectInfo)
        azRailSubItem(id = "new", hostId = "project_host", text = navStrings.new, color = Color.White, shape = AzButtonShape.NONE, info = navStrings.newInfo) {
            dashboardViewModel.onNewProject(editorUiState.isRightHanded)
            showLibrary = false
        }
        azRailSubItem(id = "save", hostId = "project_host", text = navStrings.save, color = Color.White, shape = AzButtonShape.NONE, info = navStrings.saveInfo) {
            showSaveDialog = true
        }
        azRailSubItem(id = "load", hostId = "project_host", text = navStrings.load, color = Color.White, shape = AzButtonShape.NONE, info = navStrings.loadInfo) {
            showLibrary = true
        }
        azRailSubItem(id = "export", hostId = "project_host", text = navStrings.export, color = Color.White, shape = AzButtonShape.NONE, info = navStrings.exportInfo) {
            editorViewModel.exportImage()
        }
        azHelpSubItem(id = "help_sub", hostId = "project_host", text = navStrings.help, color = Color.White, shape = AzButtonShape.NONE)
        azRailSubItem(id = "settings", hostId = "project_host", text = navStrings.settings, color = Color.White, shape = AzButtonShape.NONE, info = navStrings.settingsInfo) {
            showSettings = true
        }

        azDivider()

        if (canEdit) {
            editorUiState.layers.reversed().forEach { layer ->
                val activeTool = editorUiState.activeTool

                azRailRelocItem(
                    id = "layer_${layer.id}",
                    hostId = "design_host",
                    text = layer.name,
                    color = Color.White,
                    info = navStrings.layerInfo,
                    nestedRailAlignment = AzNestedRailAlignment.VERTICAL,
                    keepNestedRailOpen = true,
                    onClick = {
                        editorViewModel.onLayerActivated(layer.id)
                        editorViewModel.setActiveTool(Tool.NONE)
                    },
                    onRelocate = { _, _, new -> editorViewModel.onLayerReordered(new.map { it.removePrefix("layer_") }.reversed()) },
                    nestedContent = {
                        val activate = { editorViewModel.onLayerActivated(layer.id) }

                        val addSizeItem: () -> Unit = {
                            azRailItem(
                                id = "size_${layer.id}",
                                text = "Size",
                                color = Color.White,
                                shape = AzButtonShape.RECTANGLE,
                                info = navStrings.sizeInfo,
                                content = AzComposableContent { isEnabled ->
                                    val liveState by editorViewModel.uiState.collectAsState()
                                    var itemRadiusPx by remember { mutableFloatStateOf(100f) }
                                    val density = LocalDensity.current
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .onSizeChanged { size -> itemRadiusPx = size.width / 2f }
                                            .pointerInput(isEnabled) {
                                                if (!isEnabled) return@pointerInput
                                                detectDragGestures { change, dragAmount ->
                                                    change.consume()
                                                    // Vertical drag → size, horizontal drag → feathering
                                                    if (kotlin.math.abs(dragAmount.y) >= kotlin.math.abs(dragAmount.x)) {
                                                        val currentSize = editorViewModel.uiState.value.brushSize
                                                        editorViewModel.setBrushSize(
                                                            (currentSize - dragAmount.y * 0.5f).coerceIn(1f, itemRadiusPx)
                                                        )
                                                    } else {
                                                        val currentFeather = editorViewModel.uiState.value.brushFeathering
                                                        editorViewModel.setBrushFeathering(
                                                            (currentFeather + dragAmount.x * 0.005f).coerceIn(0f, 1f)
                                                        )
                                                    }
                                                }
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        val sizeDp = with(density) {
                                            liveState.brushSize.coerceIn(1f, itemRadiusPx).toDp()
                                        }
                                        val feathering = liveState.brushFeathering
                                        // Solid inner circle = hard core; outer blurred ring = feathering amount
                                        Box(contentAlignment = Alignment.Center) {
                                            if (feathering > 0.05f) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(sizeDp)
                                                        .background(
                                                            NeonGreen.copy(alpha = 0.3f),
                                                            CircleShape
                                                        )
                                                )
                                            }
                                            val hardCoreDp = with(density) {
                                                (liveState.brushSize * (1f - feathering * 0.7f)).coerceIn(2f, itemRadiusPx).toDp()
                                            }
                                            Box(
                                                modifier = Modifier
                                                    .size(hardCoreDp)
                                                    .background(NeonGreen, CircleShape)
                                            )
                                        }
                                    }
                                }
                            )
                        }

                        when {
                            layer.textParams != null -> {
                                val tp = layer.textParams!!
                                azRailItem(id = "font_${layer.id}", text = "Font", color = Color.White, shape = AzButtonShape.RECTANGLE) {
                                    activate()
                                    onShowFontPicker(layer.id)
                                }
                                azRailItem(
                                    id = "size_${layer.id}",
                                    text = "Size",
                                    color = Color.White,
                                    shape = AzButtonShape.RECTANGLE,
                                    content = AzComposableContent { isEnabled ->
                                        val liveState by editorViewModel.uiState.collectAsState()
                                        val currentSizeDp = liveState.layers.find { it.id == layer.id }?.textParams?.fontSizeDp ?: 64f
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .pointerInput(isEnabled) {
                                                    if (!isEnabled) return@pointerInput
                                                    detectDragGestures { change, dragAmount ->
                                                        change.consume()
                                                        val newSize = (currentSizeDp - dragAmount.y * 0.5f).coerceIn(8f, 300f)
                                                        editorViewModel.onTextSizeChanged(layer.id, newSize)
                                                    }
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = "${currentSizeDp.toInt()}",
                                                color = Color.White,
                                                style = androidx.compose.material3.MaterialTheme.typography.labelSmall
                                            )
                                        }
                                    }
                                )
                                azRailItem(
                                    id = "color_${layer.id}",
                                    text = "Color",
                                    color = Color.White,
                                    shape = AzButtonShape.RECTANGLE,
                                    onClick = { activate(); editorViewModel.onColorClicked() },
                                    content = AzComposableContent { isEnabled ->
                                        val liveState by editorViewModel.uiState.collectAsState()
                                        val currentColor = liveState.layers.find { it.id == layer.id }?.textParams?.colorArgb
                                            ?: 0xFFFFFFFF.toInt()
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .pointerInput(isEnabled) {
                                                    if (!isEnabled) return@pointerInput
                                                    detectDragGestures { change, dragAmount ->
                                                        change.consume()
                                                        val hsv = FloatArray(3)
                                                        android.graphics.Color.colorToHSV(currentColor, hsv)
                                                        hsv[2] = (hsv[2] - dragAmount.y * 0.002f).coerceIn(0f, 1f)
                                                        hsv[1] = (hsv[1] + dragAmount.x * 0.002f).coerceIn(0f, 1f)
                                                        editorViewModel.onTextColorChanged(layer.id, android.graphics.Color.HSVToColor(hsv))
                                                    }
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(28.dp)
                                                    .background(Color(currentColor), CircleShape)
                                                    .border(1.dp, Color.White.copy(alpha = 0.5f), CircleShape)
                                            )
                                        }
                                    }
                                )
                                azRailItem(
                                    id = "kern_${layer.id}",
                                    text = "Kern",
                                    color = Color.White,
                                    shape = AzButtonShape.RECTANGLE,
                                    content = AzComposableContent { isEnabled ->
                                        val liveState by editorViewModel.uiState.collectAsState()
                                        val currentKern = liveState.layers.find { it.id == layer.id }?.textParams?.letterSpacingEm ?: 0f
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .pointerInput(isEnabled) {
                                                    if (!isEnabled) return@pointerInput
                                                    detectDragGestures { change, dragAmount ->
                                                        change.consume()
                                                        val newKern = (currentKern + dragAmount.x * 0.002f).coerceIn(-0.2f, 1f)
                                                        editorViewModel.onTextKerningChanged(layer.id, newKern)
                                                    }
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = String.format("%.2f", currentKern),
                                                color = Color.White,
                                                style = androidx.compose.material3.MaterialTheme.typography.labelSmall
                                            )
                                        }
                                    }
                                )
                                azRailItem(id = "bold_${layer.id}", text = "Bold", color = if (tp.isBold) Cyan else Color.White, shape = AzButtonShape.RECTANGLE) {
                                    activate()
                                    editorViewModel.onTextStyleChanged(layer.id, !tp.isBold, tp.isItalic, tp.hasOutline, tp.hasDropShadow)
                                }
                                azRailItem(id = "italic_${layer.id}", text = "Italic", color = if (tp.isItalic) Cyan else Color.White, shape = AzButtonShape.RECTANGLE) {
                                    activate()
                                    editorViewModel.onTextStyleChanged(layer.id, tp.isBold, !tp.isItalic, tp.hasOutline, tp.hasDropShadow)
                                }
                                azRailItem(id = "outline_${layer.id}", text = "Outline", color = if (tp.hasOutline) Cyan else Color.White, shape = AzButtonShape.RECTANGLE) {
                                    activate()
                                    editorViewModel.onTextStyleChanged(layer.id, tp.isBold, tp.isItalic, !tp.hasOutline, tp.hasDropShadow)
                                }
                                azRailItem(id = "shadow_${layer.id}", text = "Shadow", color = if (tp.hasDropShadow) Cyan else Color.White, shape = AzButtonShape.RECTANGLE) {
                                    activate()
                                    editorViewModel.onTextStyleChanged(layer.id, tp.isBold, tp.isItalic, tp.hasOutline, !tp.hasDropShadow)
                                }
                                azRailItem(id = "blend_${layer.id}", text = "Blend", color = Color.White, shape = AzButtonShape.RECTANGLE, onClick = { activate(); editorViewModel.onCycleBlendMode() })
                                azRailItem(id = "adj_${layer.id}", text = "Adjust", color = Color.White, shape = AzButtonShape.RECTANGLE, onClick = { activate(); editorViewModel.onAdjustClicked() })
                            }
                            layer.isSketch -> {
                                azRailItem(id = "blend_${layer.id}", text = "Blend", color = Color.White, shape = AzButtonShape.RECTANGLE, info = navStrings.blendingInfo, onClick = { activate(); editorViewModel.onCycleBlendMode() })
                                azRailItem(id = "adj_${layer.id}", text = "Adjust", color = Color.White, shape = AzButtonShape.RECTANGLE, info = navStrings.adjustInfo, onClick = { activate(); editorViewModel.onAdjustClicked() })

                                azRailItem(
                                    id = "color_${layer.id}",
                                    text = "Color",
                                    color = Color.White,
                                    shape = AzButtonShape.RECTANGLE,
                                    info = navStrings.colorInfo,
                                    onClick = {
                                        activate()
                                        editorViewModel.setActiveTool(Tool.COLOR)
                                        editorViewModel.onColorClicked()
                                    },
                                    content = AzComposableContent { isEnabled ->
                                        val liveState by editorViewModel.uiState.collectAsState()
                                        val isActive = liveState.activeTool == Tool.COLOR
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .background(
                                                    if (isActive) Cyan.copy(alpha = 0.15f)
                                                    else Color.Transparent
                                                )
                                                .pointerInput(isEnabled) {
                                                    if (!isEnabled) return@pointerInput
                                                    detectDragGestures { change, dragAmount ->
                                                        change.consume()
                                                        editorViewModel.adjustColorHSV(
                                                            lightnessDelta = -dragAmount.y * 0.002f,
                                                            saturationDelta = dragAmount.x * 0.002f
                                                        )
                                                    }
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(28.dp)
                                                    .background(liveState.activeColor, CircleShape)
                                                    .border(1.dp, Color.White.copy(alpha = 0.5f), CircleShape)
                                            )
                                        }
                                    }
                                )
                                // --- Brush tools at bottom ---
                                azRailItem(id = "brush_${layer.id}", text = "Brush", color = if (activeTool == Tool.BRUSH) Cyan else Color.White, info = navStrings.brushInfo, onClick = { activate(); editorViewModel.setActiveTool(Tool.BRUSH) })
                                azRailItem(id = "eraser_${layer.id}", text = "Eraser", color = if (activeTool == Tool.ERASER) Cyan else Color.White, info = navStrings.eraserInfo, onClick = { activate(); editorViewModel.setActiveTool(Tool.ERASER) })
                                azRailItem(id = "blur_${layer.id}", text = "Blur", color = if (activeTool == Tool.BLUR) Cyan else Color.White, info = navStrings.blurInfo, onClick = { activate(); editorViewModel.setActiveTool(Tool.BLUR) })
                                azRailItem(id = "liquify_${layer.id}", text = "Liquify", color = if (activeTool == Tool.LIQUIFY) Cyan else Color.White, info = navStrings.liquifyInfo, onClick = { activate(); editorViewModel.setActiveTool(Tool.LIQUIFY) })
                                azRailItem(id = "dodge_${layer.id}", text = "Dodge", color = if (activeTool == Tool.DODGE) Cyan else Color.White, info = navStrings.dodgeInfo, onClick = { activate(); editorViewModel.setActiveTool(Tool.DODGE) })
                                azRailItem(id = "burn_${layer.id}", text = "Burn", color = if (activeTool == Tool.BURN) Cyan else Color.White, info = navStrings.burnInfo, onClick = { activate(); editorViewModel.setActiveTool(Tool.BURN) })
                                addSizeItem()
                            }
                            else -> {
                                azRailItem(id = "iso_${layer.id}", text = "Isolate", color = Color.White, shape = AzButtonShape.RECTANGLE, info = navStrings.isolateInfo, onClick = { activate(); editorViewModel.onRemoveBackgroundClicked() })
                                azRailItem(id = "line_${layer.id}", text = "Outline", color = Color.White, shape = AzButtonShape.RECTANGLE, info = navStrings.outlineInfo, onClick = { activate(); editorViewModel.onLineDrawingClicked() })
                                azRailItem(id = "adj_${layer.id}", text = "Adjust", color = Color.White, shape = AzButtonShape.RECTANGLE, info = navStrings.adjustInfo, onClick = { activate(); editorViewModel.onAdjustClicked() })
                                azRailItem(id = "balance_${layer.id}", text = "Balance", color = Color.White, shape = AzButtonShape.RECTANGLE, info = navStrings.balanceInfo, onClick = { activate(); editorViewModel.onBalanceClicked() })
                                azRailItem(id = "blend_${layer.id}", text = "Blend", color = Color.White, shape = AzButtonShape.RECTANGLE, info = navStrings.blendingInfo, onClick = { activate(); editorViewModel.onCycleBlendMode() })

                                // --- Brush tools at bottom ---
                                azRailItem(id = "eraser_${layer.id}", text = "Eraser", color = if (activeTool == Tool.ERASER) Cyan else Color.White, info = navStrings.eraserInfo, onClick = { activate(); editorViewModel.setActiveTool(Tool.ERASER) })
                                azRailItem(id = "blur_${layer.id}", text = "Blur", color = if (activeTool == Tool.BLUR) Cyan else Color.White, info = navStrings.blurInfo, onClick = { activate(); editorViewModel.setActiveTool(Tool.BLUR) })
                                azRailItem(id = "liquify_${layer.id}", text = "Liquify", color = if (activeTool == Tool.LIQUIFY) Cyan else Color.White, info = navStrings.liquifyInfo, onClick = { activate(); editorViewModel.setActiveTool(Tool.LIQUIFY) })
                                azRailItem(id = "dodge_${layer.id}", text = "Dodge", color = if (activeTool == Tool.DODGE) Cyan else Color.White, info = navStrings.dodgeInfo, onClick = { activate(); editorViewModel.setActiveTool(Tool.DODGE) })
                                azRailItem(id = "burn_${layer.id}", text = "Burn", color = if (activeTool == Tool.BURN) Cyan else Color.White, info = navStrings.burnInfo, onClick = { activate(); editorViewModel.setActiveTool(Tool.BURN) })
                                addSizeItem()
                            }
                        }
                    }
                ) {
                    inputItem(hint = "Rename") { newName -> editorViewModel.onLayerRenamed(layer.id, newName) }
                    if (layer.textParams != null) {
                        inputItem(hint = "Edit text") { text -> editorViewModel.onTextContentChanged(layer.id, text) }
                    }
                    listItem(text = "Copy Edits") { editorViewModel.copyLayerModifications(layer.id) }
                    listItem(text = "Paste Edits") { editorViewModel.pasteLayerModifications(layer.id) }
                    listItem(text = "Duplicate") { editorViewModel.onLayerDuplicated(layer.id) }
                    listItem(text = "Delete") { editorViewModel.onLayerRemoved(layer.id) }
                }
            }
        }

        azDivider()

        if (editorUiState.editorMode == EditorMode.AR || editorUiState.editorMode == EditorMode.OVERLAY) {
            azRailItem(id = "light", text = navStrings.light, color = Color.White, info = navStrings.lightInfo, onClick = { arViewModel.toggleFlashlight() })
        }

        azRailItem(id = "lock_trace", text = navStrings.lock, color = Color.White, info = navStrings.lockInfo, onClick = { mainViewModel.setTouchLocked(true) })

    }
}

@Composable
private fun DepthApiUnsupportedBanner(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(Color(0xEECC4400), RoundedCornerShape(12.dp))
            .padding(horizontal = 20.dp, vertical = 10.dp)
    ) {
        Text(
            text = "This device doesn't support the Depth API.\nSwitch to Cloud Points mode in Settings.",
            color = HotPink,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun TapTargetOverlay(
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(bottom = 96.dp).padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .background(Color(0xEE000000), RoundedCornerShape(16.dp))
                .border(2.dp, Color.Cyan, RoundedCornerShape(16.dp))
                .padding(20.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "TARGET CREATION",
                    color = Color.Cyan,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "Tap directly on your painted reference marks on the screen. The app will immediately isolate them.",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Start
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        OutlinedButton(
            onClick = onCancel,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Gray)
        ) {
            Text("Cancel", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun DiagPopup(
    diagLog: String?,
    modifier: Modifier = Modifier
) {
    var offsetX by remember { mutableFloatStateOf(16f) }
    var offsetY by remember { mutableFloatStateOf(80f) }
    var visible by remember { mutableStateOf(true) }
    var copied by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    if (!visible) return

    Box(
        modifier = modifier
            .offset { IntOffset(offsetX.toInt(), offsetY.toInt()) }
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    offsetX += dragAmount.x
                    offsetY += dragAmount.y
                }
            }
            .pointerInput(diagLog) {
                detectTapGestures {
                    val text = diagLog ?: return@detectTapGestures
                    val cm = context.getSystemService(AndroidClipboardManager::class.java)
                    cm.setPrimaryClip(ClipData.newPlainText("diag", text))
                    copied = true
                    scope.launch {
                        kotlinx.coroutines.delay(1500)
                        copied = false
                    }
                }
            }
            .background(
                if (copied) Color(0xDD004444) else Color(0xDD000000),
                RoundedCornerShape(8.dp)
            )
            .border(
                1.dp,
                if (copied) Color.Green else Color.Cyan,
                RoundedCornerShape(8.dp)
            )
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .widthIn(max = 300.dp)
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (copied) "COPIED ✓" else "DEPTH DIAG  (tap to copy)",
                    color = if (copied) Color.Green else Color.Cyan,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    "✕",
                    color = Color.Gray,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .padding(start = 12.dp)
                        .pointerInput(Unit) {
                            detectTapGestures { _ -> visible = false }
                        }
                )
            }
            Text(
                text = diagLog ?: "Waiting for first frame…",
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                lineHeight = MaterialTheme.typography.labelSmall.lineHeight
            )
        }
    }
}

@Composable
private fun ScanCoachingOverlay(
    splatCount: Int,
    hint: String?,
    modifier: Modifier = Modifier,
    scanPhase: ScanPhase = ScanPhase.AMBIENT,
    ambientSectorsCovered: Int = 0,
) {
    val phaseLabel = when (scanPhase) {
        ScanPhase.AMBIENT -> "Step 1: Map your surroundings"
        ScanPhase.WALL -> "Step 2: Scan the target wall"
        ScanPhase.COMPLETE -> null
    }
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        AnimatedVisibility(
            visible = hint != null,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit  = fadeOut() + slideOutVertically { it / 2 }
        ) {
            AnimatedContent(
                targetState = hint ?: "",
                transitionSpec = {
                    (fadeIn() + slideInVertically { -it / 3 })
                        .togetherWith(fadeOut() + slideOutVertically { it / 3 })
                },
                label = "scan_hint"
            ) { text ->
                Box(
                    modifier = Modifier
                        .background(
                            Color(0xCC000000),
                            RoundedCornerShape(20.dp)
                        )
                        .padding(horizontal = 16.dp, vertical = 7.dp)
                ) {
                    Text(
                        text = text,
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .background(Color(0xCC000000), RoundedCornerShape(20.dp))
                .padding(horizontal = 14.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (phaseLabel != null) {
                    Text(
                        text = phaseLabel,
                        color = Color.Cyan,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (scanPhase == ScanPhase.AMBIENT) {
                        LinearProgressIndicator(
                            progress = { (ambientSectorsCovered / 12f).coerceIn(0f, 1f) },
                            modifier = Modifier.width(100.dp),
                            color = Color.Cyan,
                            trackColor = Color.White.copy(alpha = 0.2f)
                        )
                        Text(
                            text = "${ambientSectorsCovered * 30}° / 360°",
                            color = Color.LightGray,
                            style = MaterialTheme.typography.labelSmall
                        )
                    } else {
                        LinearProgressIndicator(
                            progress = { (splatCount / 50_000f).coerceIn(0f, 1f) },
                            modifier = Modifier.width(100.dp),
                            color = Color.Cyan,
                            trackColor = Color.White.copy(alpha = 0.2f)
                        )
                        Text(
                            text = "${splatCount / 1000}k / 50k",
                            color = Color.LightGray,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaneConfirmOverlay(
    onConfirm: () -> Unit,
    onRedetect: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(bottom = 96.dp).padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .background(Color(0xEE000000), RoundedCornerShape(16.dp))
                .border(2.dp, Color(0xFFFF8C00), RoundedCornerShape(16.dp))
                .padding(20.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "Is the artwork on the correct wall?",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(16.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = onConfirm,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
                    ) {
                        Text("Looks correct")
                    }
                    OutlinedButton(
                        onClick = onRedetect,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF8C00))
                    ) {
                        Text("Re-detect")
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaneRealignmentOverlay(
    onTryThisPlane: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(bottom = 96.dp).padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .background(Color(0xEE000000), RoundedCornerShape(16.dp))
                .border(2.dp, Color(0xFFFF8C00), RoundedCornerShape(16.dp))
                .padding(20.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "Re-detect Wall Surface",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "The anchor is placed from where you stood when you captured. " +
                        "ARCore picks the tracked vertical surface most directly in front of " +
                        "that original position.\n\n" +
                        "1. Return to approximately where you stood during capture\n" +
                        "2. Slowly pan the camera across your mural wall so ARCore can " +
                        "register it as a flat surface\n" +
                        "3. Hold steady facing the artwork, then tap below\n\n" +
                        "The orange border will jump to the newly detected surface.",
                    color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Start
                )
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = onTryThisPlane,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
                    ) {
                        Text("Use This Wall")
                    }
                    OutlinedButton(
                        onClick = onCancel,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF8C00))
                    ) {
                        Text("Cancel")
                    }
                }
            }
        }
    }
}

@Composable
private fun DistanceBadge(
    distanceMeters: Float,
    imperial: Boolean,
    modifier: Modifier = Modifier
) {
    val label = if (imperial) {
        val feet = distanceMeters * 3.28084f
        "%.1f ft".format(feet)
    } else {
        if (distanceMeters < 1f) "${(distanceMeters * 100).toInt()} cm"
        else "%.1f m".format(distanceMeters)
    }
    Box(
        modifier = modifier
            .background(Color(0xCC000000), RoundedCornerShape(20.dp))
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Text(
            text = label,
            color = Color.White,
            style = MaterialTheme.typography.labelMedium
        )
    }
}

@Composable
private fun PaintingProgressIndicator(
    progress: Float,
    modifier: Modifier = Modifier
) {
    val pct = (progress * 100f).toInt().coerceIn(0, 100)
    val barColor = when {
        pct >= 80 -> Color(0xFF66BB6A)
        pct >= 40 -> Color(0xFFFFCA28)
        else      -> Color(0xFFEF5350)
    }
    Box(
        modifier = modifier
            .background(Color(0xCC000000), RoundedCornerShape(20.dp))
            .padding(horizontal = 14.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.width(90.dp),
                color = barColor,
                trackColor = Color.White.copy(alpha = 0.2f)
            )
            Text(
                text = "$pct%",
                color = Color.White,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

private val AVAILABLE_FONTS = listOf(
    "Roboto", "Oswald", "Bebas Neue", "Anton",
    "Playfair Display", "Pacifico", "Dancing Script",
    "Permanent Marker", "Rock Salt", "Bangers", "Righteous"
)

@Composable
private fun FontPickerDialog(
    onFontSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val googleFontsProvider = remember {
        androidx.compose.ui.text.googlefonts.GoogleFont.Provider(
            providerAuthority = "com.google.android.gms.fonts",
            providerPackage = "com.google.android.gms",
            certificates = com.hereliesaz.graffitixr.R.array.com_google_android_gms_fonts_certs
        )
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose Font") },
        text = {
            LazyColumn {
                items(AVAILABLE_FONTS) { fontName ->
                    val googleFont = androidx.compose.ui.text.googlefonts.GoogleFont(fontName)
                    val fontFamily = FontFamily(
                        androidx.compose.ui.text.googlefonts.Font(googleFont, googleFontsProvider)
                    )
                    Text(
                        text = "Aa  $fontName",
                        fontFamily = fontFamily,
                        fontSize = 20.sp,
                        color = Color.White,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onFontSelected(fontName) }
                            .padding(12.dp)
                    )
                }
            }
        },
        confirmButton = {}
    )
}