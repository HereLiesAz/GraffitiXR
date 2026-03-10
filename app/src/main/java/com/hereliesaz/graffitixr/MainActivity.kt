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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.border
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
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
import com.hereliesaz.graffitixr.common.model.ArUiState
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
import javax.inject.Inject

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
                val cameraController = rememberCameraController()

                val editorUiState by editorViewModel.uiState.collectAsState()
                val mainUiState by mainViewModel.uiState.collectAsState()
                val arUiState by arViewModel.uiState.collectAsState()
                val dashboardNavigation by dashboardViewModel.navigationTrigger.collectAsState()

                var isProcessing by remember { mutableStateOf(false) }

                val currentTempCapture = arUiState.tempCaptureBitmap
                val currentCaptureStep = mainUiState.captureStep
                LaunchedEffect(currentTempCapture, currentCaptureStep) {
                    if (currentTempCapture != null && currentCaptureStep == CaptureStep.CAPTURE) {
                        // Skip rectify/mask — go straight to review
                        mainViewModel.setCaptureStep(CaptureStep.REVIEW)
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
                            overlayImagePicker, backgroundImagePicker, editorUiState, arUiState
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
                            onRendererCreated = { renderer -> }
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

                            val isScanningPhase = editorUiState.editorMode == EditorMode.AR && arUiState.splatCount < 50000
                            if (isScanningPhase && !mainUiState.isCapturingTarget && !showLibrary && !showSettings) {
                                ScanCoachingOverlay(
                                    splatCount = arUiState.splatCount,
                                    hint = arUiState.scanHint,
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .padding(bottom = 96.dp)
                                )
                            }

                            // Depth pipeline diagnostic popup — always visible in AR mode
                            if (editorUiState.editorMode == EditorMode.AR) {
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
                                    onConfirm = {
                                        mainViewModel.onConfirmTargetCreation(arUiState.tempCaptureBitmap)
                                        arViewModel.restoreSplats()
                                    },
                                    onRetake = {
                                        mainViewModel.onRetakeCapture()
                                        arViewModel.restoreSplats()
                                        arViewModel.requestCapture()
                                    },
                                    onCancel = {
                                        mainViewModel.onCancelCaptureClicked()
                                        arViewModel.restoreSplats()
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
        editorUiState: EditorUiState,
        arUiState: ArUiState
    ) {
        val navStrings = NavStrings()

        azTheme(
            activeColor = Color.Cyan,
            defaultShape = AzButtonShape.RECTANGLE,
            headerIconShape = AzHeaderIconShape.ROUNDED
        )
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

        val isArOrOverlay = editorUiState.editorMode == EditorMode.AR || editorUiState.editorMode == EditorMode.OVERLAY
        val hasSufficientSplats = arUiState.splatCount >= 50000

        if (isArOrOverlay) {
            azRailHostItem(id = "target_host", text = navStrings.grid)

            azRailSubItem(id = "create", hostId = "target_host", text = navStrings.create, shape = AzButtonShape.NONE) {
                if (hasCameraPermission) mainViewModel.startTargetCapture() else requestPermissions()
            }

            azRailSubItem(id = "key", hostId = "target_host", text = "Keyframe", shape = AzButtonShape.NONE) {
                arViewModel.captureKeyframe()
            }
            azDivider()
        }

        val canEdit = if (isArOrOverlay) arUiState.isScanning && hasSufficientSplats else true

        if (canEdit) {
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
        }

        azRailHostItem(id = "project_host", text = navStrings.project)
        azRailSubItem(id = "new", hostId = "project_host", text = navStrings.new, shape = AzButtonShape.NONE) {
            dashboardViewModel.onNewProject(editorUiState.isRightHanded)
            showLibrary = false
        }
        azRailSubItem(id = "save", hostId = "project_host", text = navStrings.save, shape = AzButtonShape.NONE) {
            showSaveDialog = true
        }
        azRailSubItem(id = "load", hostId = "project_host", text = navStrings.load, shape = AzButtonShape.NONE) {
            showLibrary = true
        }
        azRailSubItem(id = "export", hostId = "project_host", text = navStrings.export, shape = AzButtonShape.NONE) {
            editorViewModel.exportImage()
        }
        azHelpSubItem(id = "help_sub", hostId = "project_host", text = navStrings.help, shape = AzButtonShape.NONE)
        azRailSubItem(id = "settings", hostId = "project_host", text = navStrings.settings, shape = AzButtonShape.NONE) {
            showSettings = true
        }

        azDivider()

        if (canEdit) {
            editorUiState.layers.reversed().forEach { layer ->
                // FIX: Capture activeTool for use inside the nested content lambda.
                // The rail DSL rebuilds on each recomposition so this always reflects
                // the current ViewModel state.
                val activeTool = editorUiState.activeTool

                azRailRelocItem(
                    id = "layer_${layer.id}",
                    hostId = "design_host",
                    text = layer.name,
                    nestedRailAlignment = AzNestedRailAlignment.HORIZONTAL,
                    // FIX: keepNestedRailOpen = false so that tapping the layer item
                    // again (which calls onLayerActivated -> activeTool = NONE) also
                    // closes the nested tool rail, deactivating the active tool visually.
                    keepNestedRailOpen = false,
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
                            // FIX: Use azRailToggle so the active tool stays visually highlighted.
                            // isChecked reflects ViewModel state — persists across rail recompositions.
                            // Tapping a different tool deactivates the previous one automatically.
                            // The nested rail closes (and activeTool resets) when the layer item is tapped.
                            azRailToggle(
                                id = "brush_${layer.id}",
                                isChecked = activeTool == Tool.BRUSH,
                                toggleOnText = "Brush",
                                toggleOffText = "Brush",
                                onClick = { activate(); editorViewModel.setActiveTool(Tool.BRUSH) }
                            )
                            azRailToggle(
                                id = "eraser_${layer.id}",
                                isChecked = activeTool == Tool.ERASER,
                                toggleOnText = "Eraser",
                                toggleOffText = "Eraser",
                                onClick = { activate(); editorViewModel.setActiveTool(Tool.ERASER) }
                            )
                            azRailToggle(
                                id = "blur_${layer.id}",
                                isChecked = activeTool == Tool.BLUR,
                                toggleOnText = "Blur",
                                toggleOffText = "Blur",
                                onClick = { activate(); editorViewModel.setActiveTool(Tool.BLUR) }
                            )
                            azRailToggle(
                                id = "liquify_${layer.id}",
                                isChecked = activeTool == Tool.LIQUIFY,
                                toggleOnText = "Liquify",
                                toggleOffText = "Liquify",
                                onClick = { activate(); editorViewModel.setActiveTool(Tool.LIQUIFY) }
                            )
                            azRailItem(id = "blend_${layer.id}", text = "Blend", shape = AzButtonShape.RECTANGLE) { activate(); editorViewModel.onCycleBlendMode() }

                            addSizeItem()

                            azRailToggle(
                                id = "color_${layer.id}",
                                isChecked = activeTool == Tool.COLOR,
                                toggleOnText = "Color",
                                toggleOffText = "Color",
                                onClick = {
                                    activate()
                                    editorViewModel.setActiveTool(Tool.COLOR)
                                    editorViewModel.onColorClicked()
                                }
                            )
                            azRailItem(id = "adj_${layer.id}", text = "Adjust", shape = AzButtonShape.RECTANGLE) { activate(); editorViewModel.onAdjustClicked() }
                        } else {
                            azRailItem(id = "iso_${layer.id}", text = "Isolate", shape = AzButtonShape.RECTANGLE) { activate(); editorViewModel.onRemoveBackgroundClicked() }
                            azRailItem(id = "line_${layer.id}", text = "Outline", shape = AzButtonShape.RECTANGLE) { activate(); editorViewModel.onLineDrawingClicked() }
                            azRailItem(id = "adj_${layer.id}", text = "Adjust", shape = AzButtonShape.RECTANGLE) { activate(); editorViewModel.onAdjustClicked() }
                            azRailToggle(
                                id = "eraser_${layer.id}",
                                isChecked = activeTool == Tool.ERASER,
                                toggleOnText = "Eraser",
                                toggleOffText = "Eraser",
                                onClick = { activate(); editorViewModel.setActiveTool(Tool.ERASER) }
                            )
                            azRailToggle(
                                id = "blur_${layer.id}",
                                isChecked = activeTool == Tool.BLUR,
                                toggleOnText = "Blur",
                                toggleOffText = "Blur",
                                onClick = { activate(); editorViewModel.setActiveTool(Tool.BLUR) }
                            )
                            azRailToggle(
                                id = "liquify_${layer.id}",
                                isChecked = activeTool == Tool.LIQUIFY,
                                toggleOnText = "Liquify",
                                toggleOffText = "Liquify",
                                onClick = { activate(); editorViewModel.setActiveTool(Tool.LIQUIFY) }
                            )
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
        }

        azDivider()

        if (editorUiState.editorMode == EditorMode.AR || editorUiState.editorMode == EditorMode.OVERLAY) {
            azRailItem(id = "light", text = navStrings.light) { arViewModel.toggleFlashlight() }
        }

        azRailItem(id = "lock_trace", text = navStrings.lock) { mainViewModel.setTouchLocked(true) }
    }
}

// ─── Scan coaching overlay ────────────────────────────────────────────────────
// Sits just above the bottom rail. A small progress pill shows how far along
// the scan is. Below it, a hint line animates whenever the specific guidance
// message changes — telling the user exactly what they need to do more of.

@Composable
private fun DiagPopup(
    diagLog: String?,
    modifier: Modifier = Modifier
) {
    var offsetX by remember { mutableStateOf(16f) }
    var offsetY by remember { mutableStateOf(80f) }
    var visible by remember { mutableStateOf(true) }
    var copied by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
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
                    clipboard.setText(AnnotatedString(text))
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
        androidx.compose.foundation.layout.Column(
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            androidx.compose.foundation.layout.Row(
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
                            detectTapGestures { visible = false }
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
    modifier: Modifier = Modifier
) {
    androidx.compose.foundation.layout.Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Hint line — animates out when the message changes so updates feel deliberate
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
                androidx.compose.foundation.layout.Box(
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

        // Slim progress pill — counts up without being distracting
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .background(Color(0xCC000000), RoundedCornerShape(20.dp))
                .padding(horizontal = 14.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.foundation.layout.Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
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
