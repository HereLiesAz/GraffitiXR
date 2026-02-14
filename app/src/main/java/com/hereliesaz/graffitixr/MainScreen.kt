package com.hereliesaz.graffitixr

import android.view.PixelCopy
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.min
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.BlendMode as ModelBlendMode
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.geometry.Offset
import androidx.navigation.NavController
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.hereliesaz.aznavrail.*
import com.hereliesaz.graffitixr.common.model.ArUiState
import com.hereliesaz.graffitixr.common.model.EditorUiState
import com.hereliesaz.graffitixr.common.model.EditorMode
import com.hereliesaz.graffitixr.common.model.RotationAxis
import com.hereliesaz.graffitixr.common.model.CaptureStep
import com.hereliesaz.graffitixr.common.util.ImageProcessor
import com.hereliesaz.graffitixr.design.components.TouchLockOverlay
import com.hereliesaz.graffitixr.design.components.UnlockInstructionsPopup
import com.hereliesaz.graffitixr.design.theme.NavStrings
import com.hereliesaz.graffitixr.feature.ar.rendering.ArRenderer
import com.hereliesaz.graffitixr.feature.ar.ArView
import com.hereliesaz.graffitixr.feature.ar.ArViewModel
import com.hereliesaz.graffitixr.feature.ar.MappingScreen
import com.hereliesaz.graffitixr.feature.dashboard.DashboardViewModel
import com.hereliesaz.graffitixr.feature.dashboard.ProjectLibraryScreen
import com.hereliesaz.graffitixr.feature.dashboard.SettingsScreen
import com.hereliesaz.graffitixr.feature.editor.*
import com.hereliesaz.graffitixr.feature.editor.GsViewer
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

import androidx.compose.foundation.gestures.detectTapGestures
import com.hereliesaz.graffitixr.feature.dashboard.SaveProjectDialog

/**
 * The main screen composable that orchestrates the entire application UI.
 *
 * It composes the [GraffitiNavRail] (navigation side-bar), the main content area
 * (switching between [ArView], [GsViewer], etc.), and overlay dialogs/panels.
 */
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    editorViewModel: EditorViewModel,
    arViewModel: ArViewModel,
    dashboardViewModel: DashboardViewModel,
    navController: NavController,
    slamManager: SlamManager, // Injected dependency
    projectRepository: com.hereliesaz.graffitixr.domain.repository.ProjectRepository,
    onRendererCreated: (ArRenderer) -> Unit
) {
    val localNavController = rememberNavController()
    val navBackStackEntry by localNavController.currentBackStackEntryAsState()
    val currentNavRoute = navBackStackEntry?.destination?.route

    val uiState by viewModel.uiState.collectAsState()
    val arUiState by arViewModel.uiState.collectAsState()
    val editorUiState by editorViewModel.uiState.collectAsState()
    val dashboardUiState by dashboardViewModel.uiState.collectAsState()
    val exportTrigger by editorViewModel.exportTrigger.collectAsState()

    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val context = LocalContext.current
    val window = (view.context as? android.app.Activity)?.window

    val haptic = LocalHapticFeedback.current
    val performHaptic = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) }

    var use3dBackground by remember { mutableStateOf(false) }
    var showSaveDialog by remember { mutableStateOf(false) }

    val has3dModel = remember(editorUiState.mapPath) {
        !editorUiState.mapPath.isNullOrEmpty() && File(editorUiState.mapPath!!).exists()
    }

    var renderRef by remember { mutableStateOf<ArRenderer?>(null) }
    val onRendererCreatedWrapper: (ArRenderer) -> Unit = { renderer ->
        renderRef = renderer
        onRendererCreated(renderer)
    }

    // Effect: Handle Export Trigger
    LaunchedEffect(exportTrigger) {
        if (exportTrigger && window != null) {
            delay(300)
            captureScreenshot(window) { bitmap ->
                saveExportedImage(context, bitmap)
                editorViewModel.onExportComplete()
            }
        }
    }

    // Effect: Handle Photogrammetry Keyframe Capture
    LaunchedEffect(arUiState.pendingKeyframePath) {
        arUiState.pendingKeyframePath?.let { path ->
            renderRef?.saveKeyframe(path)
            arViewModel.onKeyframeCaptured()
        }
    }

    val navStrings = remember {
        NavStrings(
            modes = "Modes", arMode = "AR", arModeInfo = "AR Projection",
            overlay = "Overlay", overlayInfo = "Overlay Mode",
            mockup = "Mockup", mockupInfo = "Mockup Mode",
            trace = "Trace", traceInfo = "Trace Mode",
            grid = "Target", surveyor = "Survey", surveyorInfo = "Map Wall",
            create = "Create", createInfo = "New Target",
            refine = "Refine", refineInfo = "Adjust Target",
            update = "Progress", updateInfo = "Mark Work",
            design = "Design", open = "Open", openInfo = "Add Image",
            wall = "Wall", wallInfo = "Change Wall",
            isolate = "Isolate", isolateInfo = "Remove BG",
            outline = "Outline", outlineInfo = "Line Art",
            adjust = "Adjust", adjustInfo = "Colors",
            balance = "Color", balanceInfo = "Color Tint",
            build = "Blend", blendingInfo = "Blend Mode",
            settings = "Settings", project = "Project",
            new = "New", newInfo = "Clear Canvas",
            save = "Save", saveInfo = "Save to File",
            load = "Load", loadInfo = "Open Project",
            export = "Export", exportInfo = "Export Image",
            help = "Help", helpInfo = "Guide",
            light = "Light", lightInfo = "Flashlight",
            lock = "Lock", lockInfo = "Touch Lock"
        )
    }

    var showSliderDialog by remember { mutableStateOf<String?>(null) }
    var showColorBalanceDialog by remember { mutableStateOf(false) }
    var showInfoScreen by remember { mutableStateOf(false) }

    val resetDialogs = remember { { showSliderDialog = null; showColorBalanceDialog = false } }

    val overlayImagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { editorViewModel.onAddLayer(it) }
    }
    val backgroundImagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { editorViewModel.setBackgroundImage(it) }
    }

    var hasCameraPermission by remember {
        mutableStateOf(
            androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.CAMERA
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasCameraPermission = permissions[android.Manifest.permission.CAMERA] ?: false
    }

    val requestPermissions = {
        permissionLauncher.launch(
            arrayOf(
                android.Manifest.permission.CAMERA,
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    val isRailVisible = !editorUiState.hideUiForCapture && !uiState.isTouchLocked
    val targetCreationState = com.hereliesaz.graffitixr.feature.ar.rememberTargetCreationState()

    AzHostActivityLayout(navController = localNavController) {
        // --- 1. THE RAIL ---
        if (isRailVisible) {
            GraffitiNavRail(
                navStrings = navStrings,
                editorUiState = editorUiState,
                editorViewModel = editorViewModel,
                viewModel = viewModel,
                arViewModel = arViewModel,
                navController = localNavController,
                hasCameraPermission = hasCameraPermission,
                requestPermissions = requestPermissions,
                performHaptic = performHaptic,
                resetDialogs = resetDialogs,
                backgroundImagePicker = backgroundImagePicker,
                overlayImagePicker = overlayImagePicker,
                has3dModel = has3dModel,
                use3dBackground = use3dBackground,
                onToggle3dBackground = { use3dBackground = !use3dBackground },
                onShowInfoScreen = { showInfoScreen = true },
                onSaveProject = { showSaveDialog = true } // Use the dialog
            )
        }

        // --- 2. MAIN BACKGROUND / CONTENT ---
        background(weight = 0) {
            if (uiState.isCapturingTarget) {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                    com.hereliesaz.graffitixr.feature.ar.TargetCreationBackground(
                        uiState = arUiState,
                        captureStep = uiState.captureStep,
                        state = targetCreationState,
                        onPhotoCaptured = { bitmap ->
                            arViewModel.setTempCapture(bitmap)
                            viewModel.setCaptureStep(CaptureStep.RECTIFY)
                        }
                    )
                }
            } else if (currentNavRoute == "surveyor") {
                com.hereliesaz.graffitixr.feature.ar.MappingBackground(
                    slamManager = slamManager,
                    projectRepository = projectRepository,
                    onRendererCreated = onRendererCreatedWrapper
                )
            } else if (currentNavRoute == "editor" || currentNavRoute == null) {
                val backgroundColor = if (editorUiState.editorMode == EditorMode.TRACE) Color.Black else Color.Black
                Box(modifier = Modifier.fillMaxSize().background(backgroundColor)) {
                    MainContentLayer(
                        editorUiState = editorUiState,
                        arUiState = arUiState,
                        editorViewModel = editorViewModel,
                        arViewModel = arViewModel,
                        onRendererCreated = onRendererCreatedWrapper,
                        onPickBackground = {
                            backgroundImagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                        slamManager = slamManager, // FIXED: Passing Shared Engine
                        projectRepository = projectRepository,
                        use3dBackground = use3dBackground
                    )
                }
            } else {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black))
            }
        }

        // --- 3. ONSCREEN OVERLAYS ---
        onscreen(alignment = Alignment.Center) {
            Box(modifier = Modifier.fillMaxSize()) {
                if (uiState.isCapturingTarget) {
                    com.hereliesaz.graffitixr.feature.ar.TargetCreationUi(
                        uiState = arUiState,
                        isRightHanded = editorUiState.isRightHanded,
                        captureStep = uiState.captureStep,
                        state = targetCreationState,
                        onConfirm = {
                            val bitmapToSave = arUiState.tempCaptureBitmap
                            if (bitmapToSave != null) {
                                scope.launch(Dispatchers.IO) {
                                    val uri = saveBitmapToCache(context, bitmapToSave)
                                    if (uri != null) {
                                        withContext(Dispatchers.Main) {
                                            arViewModel.onFrameCaptured(bitmapToSave, uri)
                                            viewModel.onConfirmTargetCreation()
                                        }
                                    } else {
                                        withContext(Dispatchers.Main) {
                                            android.widget.Toast.makeText(context, "Failed to save target", android.widget.Toast.LENGTH_SHORT).show()
                                            viewModel.onConfirmTargetCreation()
                                        }
                                    }
                                }
                            } else {
                                viewModel.onConfirmTargetCreation()
                            }
                        },
                        onRetake = viewModel::onRetakeCapture,
                        onCancel = viewModel::onCancelCaptureClicked,
                        onUnwarpConfirm = { points: List<Offset> ->
                            arUiState.tempCaptureBitmap?.let { src ->
                                ImageProcessor.unwarpImage(src, points)?.let { unwarped ->
                                    arViewModel.setTempCapture(unwarped)
                                    viewModel.setCaptureStep(CaptureStep.MASK)
                                }
                            }
                        },
                        onMaskConfirmed = { maskedBitmap: Bitmap ->
                            val extracted = ImageProcessor.detectEdges(maskedBitmap) ?: maskedBitmap
                            arViewModel.setTempCapture(extracted)
                            viewModel.setCaptureStep(CaptureStep.REVIEW)
                        }
                    )
                }

                AzNavHost(startDestination = "project_library") {
                    composable("editor") {
                        EditorUi(
                            actions = editorViewModel,
                            uiState = editorUiState,
                            isTouchLocked = uiState.isTouchLocked,
                            showUnlockInstructions = uiState.showUnlockInstructions,
                            isCapturingTarget = uiState.isCapturingTarget
                        )
                    }
                    composable("surveyor") {
                        com.hereliesaz.graffitixr.feature.ar.MappingUi(
                            onBackClick = { localNavController.popBackStack() },
                            onScanComplete = { localNavController.popBackStack() }
                        )
                    }
                    composable("project_library") {
                        LaunchedEffect(Unit) { dashboardViewModel.loadAvailableProjects() }
                        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                            ProjectLibraryScreen(
                                projects = dashboardUiState.availableProjects,
                                onLoadProject = {
                                    dashboardViewModel.openProject(it)
                                    localNavController.navigate("editor") {
                                        popUpTo("project_library") { inclusive = false }
                                    }
                                },
                                onDeleteProject = { projectId ->
                                    dashboardViewModel.deleteProject(projectId)
                                },
                                onNewProject = {
                                    dashboardViewModel.onNewProject(editorUiState.isRightHanded)
                                    localNavController.navigate("editor") {
                                        popUpTo("project_library") { inclusive = false }
                                    }
                                }
                            )
                        }
                    }
                    composable("settings") {
                        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                            SettingsScreen(
                                currentVersion = "1.0.0",
                                updateStatus = "Up to date",
                                isCheckingForUpdate = false,
                                isRightHanded = editorUiState.isRightHanded,
                                onHandednessChanged = { editorViewModel.toggleHandedness() },
                                onCheckForUpdates = { },
                                onInstallUpdate = { },
                                onClose = { localNavController.popBackStack() }
                            )
                        }
                    }
                }

                TouchLockOverlay(uiState.isTouchLocked) {
                    viewModel.showUnlockInstructions(true)
                }
                UnlockInstructionsPopup(uiState.showUnlockInstructions)

                if (showInfoScreen) {
                    com.hereliesaz.graffitixr.design.components.InfoDialog(
                        title = "GraffitiXR Help",
                        content = "Design and project graffiti onto physical walls using AR.",
                        onDismiss = { showInfoScreen = false }
                    )
                }

                if (showSaveDialog) {
                    SaveProjectDialog(
                        initialName = projectRepository.currentProject.value?.name ?: "",
                        onDismissRequest = { showSaveDialog = false },
                        onSaveRequest = { name ->
                            editorViewModel.saveProject(name)
                            showSaveDialog = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun MainContentLayer(
    editorUiState: EditorUiState,
    arUiState: ArUiState,
    editorViewModel: EditorViewModel,
    arViewModel: ArViewModel,
    onRendererCreated: (ArRenderer) -> Unit,
    onPickBackground: () -> Unit,
    slamManager: SlamManager,
    projectRepository: com.hereliesaz.graffitixr.domain.repository.ProjectRepository,
    use3dBackground: Boolean = false
) {
    Box(Modifier.fillMaxSize().zIndex(1f), contentAlignment = Alignment.Center) {
        val activeLayer = editorUiState.layers.find { it.id == editorUiState.activeLayerId } ?: editorUiState.layers.firstOrNull()

        when (editorUiState.editorMode) {
            EditorMode.AR -> {
                ArView(
                    viewModel = arViewModel,
                    uiState = arUiState,
                    slamManager = slamManager,
                    projectRepository = projectRepository,
                    activeLayer = activeLayer,
                    onRendererCreated = onRendererCreated
                )
                // If no target detected, allow manual placement overlay
                if (!arUiState.isTargetDetected && editorUiState.layers.isNotEmpty()) {
                    OverlayScreen(uiState = editorUiState, viewModel = editorViewModel)
                }
            }
            EditorMode.STATIC, EditorMode.MOCKUP -> {
                if (use3dBackground && !editorUiState.mapPath.isNullOrEmpty()) {
                    GsViewer(
                        mapPath = editorUiState.mapPath!!,
                        slamManager = slamManager,
                        activeLayer = activeLayer,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    MockupScreen(uiState = editorUiState, viewModel = editorViewModel)
                }
            }
            EditorMode.TRACE -> {
                TraceScreen(uiState = editorUiState, viewModel = editorViewModel)
            }
            EditorMode.OVERLAY -> {
                Box(Modifier.fillMaxSize()) {
                    ArView(
                        viewModel = arViewModel,
                        uiState = arUiState.copy(showPointCloud = false),
                        slamManager = slamManager,
                        projectRepository = projectRepository,
                        activeLayer = activeLayer,
                        onRendererCreated = onRendererCreated
                    )
                    OverlayScreen(uiState = editorUiState, viewModel = editorViewModel)
                }
            }
            else -> {
                Box(Modifier.fillMaxSize().background(Color.Black))
            }
        }
    }
}

@Composable
private fun LayersOverlay(
    layers: List<Layer>,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        layers.forEach { layer ->
            if (layer.isVisible) {
                val imageBitmap = layer.bitmap.asImageBitmap()
                val srcWidth = imageBitmap.width.toFloat()
                val srcHeight = imageBitmap.height.toFloat()

                // ContentScale.Fit logic
                val scaleFactor = min(size.width / srcWidth, size.height / srcHeight)
                val drawnWidth = srcWidth * scaleFactor
                val drawnHeight = srcHeight * scaleFactor
                val topLeftX = (size.width - drawnWidth) / 2
                val topLeftY = (size.height - drawnHeight) / 2

                val centerX = size.width / 2
                val centerY = size.height / 2

                // Transforms
                withTransform({
                    translate(layer.offset.x, layer.offset.y)
                    rotate(layer.rotationZ, pivot = Offset(centerX, centerY))
                    scale(layer.scale, layer.scale, pivot = Offset(centerX, centerY))
                }) {
                    drawImage(
                        image = imageBitmap,
                        dstOffset = IntOffset(topLeftX.toInt(), topLeftY.toInt()),
                        dstSize = IntSize(drawnWidth.toInt(), drawnHeight.toInt()),
                        alpha = layer.opacity,
                        blendMode = mapBlendMode(layer.blendMode)
                    )
                }
            }
        }
    }
}