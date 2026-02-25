package com.hereliesaz.graffitixr

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Canvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.zIndex
import androidx.navigation.NavController
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import com.hereliesaz.aznavrail.*
import com.hereliesaz.aznavrail.model.AzDockingSide
import com.hereliesaz.graffitixr.common.model.ArUiState
import com.hereliesaz.graffitixr.common.model.EditorUiState
import com.hereliesaz.graffitixr.common.model.EditorMode
import com.hereliesaz.graffitixr.common.model.CaptureStep
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.util.ImageProcessor
import com.hereliesaz.graffitixr.design.components.TouchLockOverlay
import com.hereliesaz.graffitixr.design.components.UnlockInstructionsPopup
import com.hereliesaz.graffitixr.feature.ar.ArView
import com.hereliesaz.graffitixr.feature.ar.ArViewModel
import com.hereliesaz.graffitixr.feature.ar.rendering.ArRenderer
import com.hereliesaz.graffitixr.feature.ar.ui.TargetEvolutionScreen
import com.hereliesaz.graffitixr.feature.dashboard.DashboardViewModel
import com.hereliesaz.graffitixr.feature.dashboard.ProjectLibraryScreen
import com.hereliesaz.graffitixr.feature.dashboard.SaveProjectDialog
import com.hereliesaz.graffitixr.feature.dashboard.SettingsScreen
import com.hereliesaz.graffitixr.feature.editor.*
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.min

// Removed @Composable annotation as this is now a configuration function called from non-composable scope
fun MainScreen(
    navHostScope: AzNavHostScope,
    viewModel: MainViewModel,
    editorViewModel: EditorViewModel,
    arViewModel: ArViewModel,
    dashboardViewModel: DashboardViewModel,
    navController: NavController,
    slamManager: SlamManager,
    projectRepository: com.hereliesaz.graffitixr.domain.repository.ProjectRepository,
    renderRefState: MutableState<ArRenderer?>,
    onRendererCreated: (ArRenderer) -> Unit,
    // Hoisted State Providers
    hoistedUse3dBackground: () -> Boolean,
    hoistedShowSaveDialog: () -> Boolean,
    hoistedShowInfoScreen: () -> Boolean,
    onUse3dBackgroundChange: (Boolean) -> Unit,
    onShowSaveDialogChange: (Boolean) -> Unit,
    onShowInfoScreenChange: (Boolean) -> Unit,
    hasCameraPermission: () -> Boolean,
    requestPermissions: () -> Unit,
    onOverlayImagePick: () -> Unit,
    onBackgroundImagePick: () -> Unit,
    dockingSide: AzDockingSide
) {
    val localNavController = navController

    with(navHostScope) {
        // --- 1. BACKGROUND / CONTENT ---
        background(weight = 0) {
            val uiState by viewModel.uiState.collectAsState()
            val arUiState by arViewModel.uiState.collectAsState()
            val editorUiState by editorViewModel.uiState.collectAsState()

            val navBackStackEntry by localNavController.currentBackStackEntryAsState()
            val currentNavRoute = navBackStackEntry?.destination?.route

            val use3dBackground = hoistedUse3dBackground()
            val hasPermission = hasCameraPermission()

            val has3dModel = remember(editorUiState.mapPath) {
                !editorUiState.mapPath.isNullOrEmpty() && File(editorUiState.mapPath!!).exists()
            }

            Box(modifier = Modifier.fillMaxSize()) {
                if (uiState.isCapturingTarget) {
                    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                        com.hereliesaz.graffitixr.feature.ar.TargetCreationBackground(
                            uiState = arUiState,
                            captureStep = uiState.captureStep,
                            onPhotoCaptured = { bitmap ->
                                arViewModel.setTempCapture(bitmap)
                                localNavController.navigate("target_evolution")
                            },
                            onCaptureConsumed = arViewModel::onCaptureConsumed,
                            onInitUnwarpPoints = arViewModel::updateUnwarpPoints
                        )
                    }
                } else if (currentNavRoute == "surveyor") {
                    com.hereliesaz.graffitixr.feature.ar.MappingBackground(
                        slamManager = slamManager,
                        projectRepository = projectRepository,
                        onRendererCreated = onRendererCreated
                    )
                } else if (currentNavRoute == "editor" || currentNavRoute == null || currentNavRoute == "project_library" || currentNavRoute == "settings") {
                    val backgroundColor = if (editorUiState.editorMode == EditorMode.AR || editorUiState.editorMode == EditorMode.OVERLAY) Color.Transparent else Color.Black
                    Box(modifier = Modifier.fillMaxSize().background(backgroundColor)) {
                        MainContentLayer(
                            editorUiState = editorUiState,
                            arUiState = arUiState,
                            editorViewModel = editorViewModel,
                            arViewModel = arViewModel,
                            onRendererCreated = onRendererCreated,
                            slamManager = slamManager,
                            projectRepository = projectRepository,
                            hasCameraPermission = hasPermission,
                            use3dBackground = use3dBackground
                        )
                    }
                } else {
                    Box(modifier = Modifier.fillMaxSize().background(Color.Black))
                }
            }
        }

        // --- 2. ONSCREEN OVERLAYS ---
        onscreen(Alignment.Center) {
            val uiState by viewModel.uiState.collectAsState()
            val arUiState by arViewModel.uiState.collectAsState()
            val editorUiState by editorViewModel.uiState.collectAsState()
            val dashboardUiState by dashboardViewModel.uiState.collectAsState()
            val exportTrigger by editorViewModel.exportTrigger.collectAsState()
            val navTrigger by dashboardViewModel.navigationTrigger.collectAsState()

            val scope = rememberCoroutineScope()
            val view = LocalView.current
            val context = LocalContext.current
            val window = (view.context as? android.app.Activity)?.window

            val renderRef by renderRefState

            val showSaveDialog = hoistedShowSaveDialog()
            val showInfoScreen = hoistedShowInfoScreen()

            LaunchedEffect(exportTrigger) {
                if (exportTrigger && window != null) {
                    delay(300)
                    captureScreenshot(window) { bitmap ->
                        saveExportedImage(context, bitmap)
                        editorViewModel.onExportComplete()
                    }
                }
            }

            LaunchedEffect(navTrigger) {
                navTrigger?.let { dest ->
                    localNavController.navigate(dest)
                    dashboardViewModel.onNavigationConsumed()
                }
            }

            LaunchedEffect(arUiState.pendingKeyframePath) {
                arUiState.pendingKeyframePath?.let { path ->
                    renderRef?.saveKeyframe(path)
                    arViewModel.onKeyframeCaptured()
                }
            }

            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
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

                    // --- TARGET EVOLUTION ---
                    composable(route = "target_evolution") {
                        val evolutionBitmap = arUiState.tempCaptureBitmap

                        if (evolutionBitmap != null) {
                            TargetEvolutionScreen(
                                image = evolutionBitmap,
                                onCornersConfirmed = { corners ->
                                    val unwarped = ImageProcessor.unwarpImage(evolutionBitmap, corners)
                                    if (unwarped != null) {
                                        arViewModel.setTempCapture(unwarped)
                                        viewModel.setCaptureStep(CaptureStep.MASK)
                                        localNavController.popBackStack()
                                    }
                                }
                            )
                        } else {
                            Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                                Text("Reality failed to load.", color = Color.White)
                            }
                        }
                    }
                }

                if (uiState.isCapturingTarget) {
                    com.hereliesaz.graffitixr.feature.ar.TargetCreationUi(
                        uiState = arUiState,
                        isRightHanded = editorUiState.isRightHanded,
                        captureStep = uiState.captureStep,
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
                        },
                        onRequestCapture = arViewModel::requestCapture,
                        onUpdateUnwarpPoints = arViewModel::updateUnwarpPoints,
                        onSetActiveUnwarpPoint = arViewModel::setActiveUnwarpPointIndex,
                        onSetMagnifierPosition = arViewModel::setMagnifierPosition,
                        onUpdateMaskPath = arViewModel::setMaskPath
                    )
                }

                TouchLockOverlay(uiState.isTouchLocked) {
                    viewModel.showUnlockInstructions(true)
                }
                UnlockInstructionsPopup(uiState.showUnlockInstructions)

                if (showInfoScreen) {
                    com.hereliesaz.graffitixr.design.components.InfoDialog(
                        title = "GraffitiXR Help",
                        content = "Design and project graffiti onto physical walls using AR.",
                        onDismiss = { onShowInfoScreenChange(false) }
                    )
                }

                if (showSaveDialog) {
                    SaveProjectDialog(
                        initialName = projectRepository.currentProject.value?.name ?: "",
                        onDismissRequest = { onShowSaveDialogChange(false) },
                        onSaveRequest = { name ->
                            editorViewModel.saveProject(name)
                            onShowSaveDialogChange(false)
                        }
                    )
                }
            }
        }
    }
}

// MainContentLayer and LayersOverlay kept as is
@Composable
fun MainContentLayer(
    editorUiState: EditorUiState,
    arUiState: ArUiState,
    editorViewModel: EditorViewModel,
    arViewModel: ArViewModel,
    onRendererCreated: (ArRenderer) -> Unit,
    slamManager: SlamManager,
    projectRepository: com.hereliesaz.graffitixr.domain.repository.ProjectRepository,
    hasCameraPermission: Boolean,
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
                    onRendererCreated = onRendererCreated,
                    hasCameraPermission = hasCameraPermission
                )
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
                        onRendererCreated = onRendererCreated,
                        hasCameraPermission = hasCameraPermission
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

                val scaleFactor = min(size.width / srcWidth, size.height / srcHeight)
                val drawnWidth = srcWidth * scaleFactor
                val drawnHeight = srcHeight * scaleFactor
                val topLeftX = (size.width - drawnWidth) / 2
                val topLeftY = (size.height - drawnHeight) / 2

                val centerX = size.width / 2
                val centerY = size.height / 2

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
