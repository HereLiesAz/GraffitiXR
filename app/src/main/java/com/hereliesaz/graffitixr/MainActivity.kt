// FILE: app/src/main/java/com/hereliesaz/graffitixr/MainActivity.kt
package com.hereliesaz.graffitixr

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.google.android.gms.common.GoogleApiAvailability
import com.hereliesaz.aznavrail.*
import com.hereliesaz.aznavrail.model.*
import com.hereliesaz.graffitixr.common.model.CaptureStep
import com.hereliesaz.graffitixr.common.model.EditorMode
import com.hereliesaz.graffitixr.common.model.EditorUiState
import com.hereliesaz.graffitixr.common.model.RotationAxis
import com.hereliesaz.graffitixr.common.model.Tool
import com.hereliesaz.graffitixr.common.security.SecurityProviderManager
import com.hereliesaz.graffitixr.common.security.SecurityProviderState
import com.hereliesaz.graffitixr.common.util.ImageProcessor
import com.hereliesaz.graffitixr.design.components.InfoDialog
import com.hereliesaz.graffitixr.design.components.TouchLockOverlay
import com.hereliesaz.graffitixr.design.components.UnlockInstructionsPopup
import com.hereliesaz.graffitixr.design.theme.GraffitiXRTheme
import com.hereliesaz.graffitixr.design.theme.NavStrings
import com.hereliesaz.graffitixr.feature.ar.ArViewModel
import com.hereliesaz.graffitixr.feature.ar.TargetCreationBackground
import com.hereliesaz.graffitixr.feature.ar.TargetCreationUi
import com.hereliesaz.graffitixr.feature.ar.rememberCameraController
import com.hereliesaz.graffitixr.feature.ar.rendering.ArRenderer
import com.hereliesaz.graffitixr.feature.dashboard.DashboardViewModel
import com.hereliesaz.graffitixr.feature.dashboard.ProjectLibraryScreen
import com.hereliesaz.graffitixr.feature.dashboard.SaveProjectDialog
import com.hereliesaz.graffitixr.feature.dashboard.SettingsScreen
import com.hereliesaz.graffitixr.feature.editor.EditorUi
import com.hereliesaz.graffitixr.feature.editor.EditorViewModel
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The panopticon. Orchestrates the UI reality and hardware lifecycle.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var slamManager: SlamManager
    @Inject lateinit var projectRepository: com.hereliesaz.graffitixr.domain.repository.ProjectRepository
    @Inject lateinit var securityProviderManager: SecurityProviderManager

    private val arViewModel: ArViewModel by viewModels()

    var use3dBackground by mutableStateOf(false)
    var showSaveDialog by mutableStateOf(false)
    var showLibrary by mutableStateOf(true)
    var showSettings by mutableStateOf(false)
    var showHelpDialog by mutableStateOf(false)
    var hasCameraPermission by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { p ->
        hasCameraPermission = p[android.Manifest.permission.CAMERA] ?: false
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

                val mainViewModel: MainViewModel = hiltViewModel()
                val editorViewModel: EditorViewModel = hiltViewModel()
                val dashboardViewModel: DashboardViewModel = hiltViewModel()
                val cameraController = com.hereliesaz.graffitixr.feature.ar.rememberCameraController()

                val editorUiState by editorViewModel.uiState.collectAsState()
                val mainUiState by mainViewModel.uiState.collectAsState()
                val arUiState by arViewModel.uiState.collectAsState()
                val dashboardNavigation by dashboardViewModel.navigationTrigger.collectAsState()

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
                            } catch (e: Exception) { }
                        }
                    }
                }

                val isRailVisible = !editorUiState.hideUiForCapture && !mainUiState.isTouchLocked && !mainUiState.isCapturingTarget && !showLibrary && !showSettings

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

                AzHostActivityLayout(navController = navController, initiallyExpanded = false) {
                    if (isRailVisible) {
                        configureRail(
                            mainViewModel, editorViewModel, arViewModel, dashboardViewModel,
                            overlayImagePicker, backgroundImagePicker, editorUiState
                        )
                    }

                    background(weight = 0) {
                        MainScreen(
                            uiState = editorUiState,
                            arUiState = arUiState,
                            isTouchLocked = mainUiState.isTouchLocked,
                            isCameraActive = !showLibrary,
                            editorViewModel = editorViewModel,
                            arViewModel = arViewModel,
                            slamManager = slamManager,
                            hasCameraPermission = hasCameraPermission,
                            cameraController = cameraController,
                            onRendererCreated = { renderer ->
                                // UI threads have no business holding executioner rights.
                            }
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
                        Box(Modifier.fillMaxSize()) {
                            AzNavHost(startDestination = EditorMode.AR.name) {
                                composable(EditorMode.AR.name) { EditorOverlay(editorViewModel, mainUiState) }
                                composable(EditorMode.OVERLAY.name) { EditorOverlay(editorViewModel, mainUiState) }
                                composable(EditorMode.MOCKUP.name) { EditorOverlay(editorViewModel, mainUiState) }
                                composable(EditorMode.TRACE.name) { EditorOverlay(editorViewModel, mainUiState) }
                            }

                            if (mainUiState.isTouchLocked) {
                                var showUnlockInstructions by remember(mainUiState.isTouchLocked) { mutableStateOf(true) }
                                LaunchedEffect(mainUiState.isTouchLocked) {
                                    if (mainUiState.isTouchLocked) {
                                        kotlinx.coroutines.delay(3000)
                                        showUnlockInstructions = false
                                    }
                                }
                                TouchLockOverlay(
                                    isLocked = true,
                                    onUnlockRequested = { mainViewModel.setTouchLocked(false) }
                                )
                                UnlockInstructionsPopup(visible = showUnlockInstructions)
                            }

                            if (mainUiState.isCapturingTarget) {
                                TargetCreationUi(
                                    uiState = arUiState,
                                    isRightHanded = editorUiState.isRightHanded,
                                    captureStep = mainUiState.captureStep,
                                    onConfirm = { mainViewModel.onConfirmTargetCreation(arUiState.tempCaptureBitmap) },
                                    onRetake = { mainViewModel.onRetakeCapture() },
                                    onCancel = { mainViewModel.onCancelCaptureClicked() },
                                    onUnwarpConfirm = { points ->
                                        val currentBitmap = arUiState.tempCaptureBitmap
                                        if (currentBitmap != null && points.size == 4) {
                                            lifecycleScope.launch(Dispatchers.Default) {
                                                val unwarped = ImageProcessor.unwarpImage(currentBitmap, points)
                                                if (unwarped != null) {
                                                    arViewModel.setTempCapture(unwarped)
                                                }
                                                mainViewModel.setCaptureStep(CaptureStep.MASK)
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
                                    }
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

    private fun AzNavHostScope.configureRail(
        mainViewModel: MainViewModel,
        editorViewModel: EditorViewModel,
        arViewModel: ArViewModel,
        dashboardViewModel: DashboardViewModel,
        overlayPicker: androidx.activity.compose.ManagedActivityResultLauncher<PickVisualMediaRequest, android.net.Uri?>,
        backgroundPicker: androidx.activity.compose.ManagedActivityResultLauncher<PickVisualMediaRequest, android.net.Uri?>,
        editorUiState: EditorUiState
    ) {
        val navStrings = NavStrings()
        val activeHighlightColor = when (editorUiState.activeRotationAxis) {
            RotationAxis.X -> Color.Cyan
            RotationAxis.Y -> Color(0xFFFF69B4) // Pink
            RotationAxis.Z -> Color.Green
        }

        azTheme(activeColor = activeHighlightColor, defaultShape = AzButtonShape.RECTANGLE, headerIconShape = AzHeaderIconShape.ROUNDED)
        azConfig(packButtons = true, dockingSide = if (editorUiState.isRightHanded) AzDockingSide.LEFT else AzDockingSide.RIGHT)
        azAdvanced(helpEnabled = true)

        val requestPermissions = {
            permissionLauncher.launch(
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION)
            )
        }

        azRailHostItem(id = "mode_host", text = navStrings.modes)
        azRailSubItem(id = "ar", hostId = "mode_host", text = navStrings.arMode, route = EditorMode.AR.name, shape = AzButtonShape.NONE)
        azRailSubItem(id = "overlay", hostId = "mode_host", text = navStrings.overlay, route = EditorMode.OVERLAY.name, shape = AzButtonShape.NONE)
        azRailSubItem(id = "mockup", hostId = "mode_host", text = navStrings.mockup, route = EditorMode.MOCKUP.name, shape = AzButtonShape.NONE)
        azRailSubItem(id = "trace", hostId = "mode_host", text = navStrings.trace, route = EditorMode.TRACE.name, shape = AzButtonShape.NONE)

        azDivider()

        if (editorUiState.editorMode == EditorMode.AR || editorUiState.editorMode == EditorMode.OVERLAY) {
            azRailHostItem(id = "target_host", text = navStrings.grid)
            azRailSubItem(id = "create", hostId = "target_host", text = navStrings.create, shape = AzButtonShape.NONE) {
                if (hasCameraPermission) mainViewModel.startTargetCapture() else requestPermissions()
            }
            azRailSubItem(id = "key", hostId = "target_host", text = "Keyframe", shape = AzButtonShape.NONE) {
                arViewModel.captureKeyframe()
            }
            azDivider()
        }

        azRailHostItem(id = "design_host", text = navStrings.design)
        azRailSubItem(id = "add_img", hostId = "design_host", text = "Image", shape = AzButtonShape.NONE) {
            overlayPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        azRailSubItem(id = "add_draw", hostId = "design_host", text = "Draw", shape = AzButtonShape.NONE) {
            editorViewModel.onAddBlankLayer()
        }

        if (editorUiState.editorMode == EditorMode.MOCKUP) {
            azRailSubItem(id = "wall", hostId = "design_host", text = navStrings.wall, shape = AzButtonShape.NONE) {
                backgroundPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
        }

        azDivider()

        editorUiState.layers.reversed().forEach { layer ->
            azRailRelocItem(
                id = "layer_${layer.id}",
                hostId = "design_host",
                text = layer.name,
                nestedRailAlignment = AzNestedRailAlignment.HORIZONTAL,
                keepNestedRailOpen = true,
                onClick = {
                    editorViewModel.onLayerActivated(layer.id)
                },
                onRelocate = { _, _, new -> editorViewModel.onLayerReordered(new.map { it.removePrefix("layer_") }.reversed()) },
                nestedContent = {
                    val activate = { editorViewModel.onLayerActivated(layer.id) }

                    val addSizeItem: () -> Unit = {
                        azRailItem(
                            id = "size_${layer.id}",
                            text = "Size",
                            shape = AzButtonShape.RECTANGLE,
                            content = AzComposableContent {
                                DisposableEffect(Unit) {
                                    if (editorViewModel.uiState.value.activeTool == Tool.NONE) {
                                        editorViewModel.setActiveTool(Tool.BRUSH)
                                    }
                                    onDispose {
                                        editorViewModel.setActiveTool(Tool.NONE)
                                    }
                                }
                                val liveState by editorViewModel.uiState.collectAsState()
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .pointerInput(Unit) {
                                            detectVerticalDragGestures { change, dragAmount ->
                                                change.consume()
                                                val currentSize = editorViewModel.uiState.value.brushSize
                                                editorViewModel.setBrushSize(currentSize - dragAmount * 0.5f)
                                            }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size((liveState.brushSize / 2f).coerceIn(4f, 64f).dp)
                                            .background(Color.White, CircleShape)
                                    )
                                }
                            }
                        )
                    }

                    if (layer.isSketch) {
                        azRailItem(id = "brush_${layer.id}", text = "Brush", shape = AzButtonShape.RECTANGLE) { activate(); editorViewModel.setActiveTool(Tool.BRUSH) }
                        azRailItem(id = "eraser_${layer.id}", text = "Eraser", shape = AzButtonShape.RECTANGLE) { activate(); editorViewModel.setActiveTool(Tool.ERASER) }
                        azRailItem(id = "blur_${layer.id}", text = "Blur", shape = AzButtonShape.RECTANGLE) { activate(); editorViewModel.setActiveTool(Tool.BLUR) }
                        azRailItem(id = "liquify_${layer.id}", text = "Liquify", shape = AzButtonShape.RECTANGLE) { activate(); editorViewModel.setActiveTool(Tool.LIQUIFY) }
                        azRailItem(id = "blend_${layer.id}", text = "Blend", shape = AzButtonShape.RECTANGLE) { activate(); editorViewModel.onCycleBlendMode() }

                        addSizeItem()

                        azRailItem(id = "color_${layer.id}", text = "Color", shape = AzButtonShape.RECTANGLE, content = editorUiState.activeColor) {
                            activate()
                            editorViewModel.setActiveTool(Tool.COLOR)
                            editorViewModel.onColorClicked()
                        }
                        azRailItem(id = "adj_${layer.id}", text = "Adjust", shape = AzButtonShape.RECTANGLE) { activate(); editorViewModel.onAdjustClicked() }
                    } else {
                        azRailItem(id = "iso_${layer.id}", text = "Isolate", shape = AzButtonShape.RECTANGLE) { activate(); editorViewModel.onRemoveBackgroundClicked() }
                        azRailItem(id = "line_${layer.id}", text = "Outline", shape = AzButtonShape.RECTANGLE) { activate(); editorViewModel.onLineDrawingClicked() }
                        azRailItem(id = "adj_${layer.id}", text = "Adjust", shape = AzButtonShape.RECTANGLE) { activate(); editorViewModel.onAdjustClicked() }
                        azRailItem(id = "eraser_${layer.id}", text = "Eraser", shape = AzButtonShape.RECTANGLE) { activate(); editorViewModel.setActiveTool(Tool.ERASER) }
                        azRailItem(id = "blur_${layer.id}", text = "Blur", shape = AzButtonShape.RECTANGLE) { activate(); editorViewModel.setActiveTool(Tool.BLUR) }
                        azRailItem(id = "liquify_${layer.id}", text = "Liquify", shape = AzButtonShape.RECTANGLE) { activate(); editorViewModel.setActiveTool(Tool.LIQUIFY) }
                        azRailItem(id = "blend_${layer.id}", text = "Blend", shape = AzButtonShape.RECTANGLE) { activate(); editorViewModel.onCycleBlendMode() }

                        addSizeItem()

                        azRailItem(id = "balance_${layer.id}", text = "Balance", shape = AzButtonShape.RECTANGLE) { activate(); editorViewModel.onBalanceClicked() }
                    }
                }
            ) {
                inputItem(hint = "Rename") { newName -> editorViewModel.onLayerRenamed(layer.id, newName) }
                listItem(text = "Copy Edits") { editorViewModel.copyLayerModifications(layer.id) }
                listItem(text = "Paste Edits") { editorViewModel.pasteLayerModifications(layer.id) }
                listItem(text = "Duplicate") { editorViewModel.onLayerDuplicated(layer.id) }
                listItem(text = "Delete") { editorViewModel.onLayerRemoved(layer.id) }
            }
        }

        azDivider()

        if (editorUiState.editorMode == EditorMode.AR || editorUiState.editorMode == EditorMode.OVERLAY) {
            azRailItem(id = "light", text = navStrings.light) { arViewModel.toggleFlashlight() }
        }

        azRailItem(id = "lock_trace", text = navStrings.lock) { mainViewModel.setTouchLocked(true) }
    }
}