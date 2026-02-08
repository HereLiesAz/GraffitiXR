package com.hereliesaz.graffitixr

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
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
import com.hereliesaz.aznavrail.model.AzButtonShape
import com.hereliesaz.aznavrail.model.AzDockingSide
import com.hereliesaz.aznavrail.model.AzHeaderIconShape
import com.hereliesaz.graffitixr.common.model.EditorMode
import com.hereliesaz.graffitixr.common.model.RotationAxis
import com.hereliesaz.graffitixr.design.components.TouchLockOverlay
import com.hereliesaz.graffitixr.design.components.UnlockInstructionsPopup
import com.hereliesaz.graffitixr.design.theme.NavStrings
import com.hereliesaz.graffitixr.feature.ar.ArRenderer
import com.hereliesaz.graffitixr.feature.ar.ArView
import com.hereliesaz.graffitixr.feature.ar.ArViewModel
import com.hereliesaz.graffitixr.feature.ar.MappingScreen
import com.hereliesaz.graffitixr.feature.ar.TargetCreationFlow
import com.hereliesaz.graffitixr.feature.dashboard.DashboardViewModel
import com.hereliesaz.graffitixr.feature.dashboard.ProjectLibraryScreen
import com.hereliesaz.graffitixr.feature.dashboard.SettingsScreen
import com.hereliesaz.graffitixr.feature.editor.*

@Composable
fun MainScreen(
    viewModel: MainViewModel,
    editorViewModel: EditorViewModel,
    arViewModel: ArViewModel,
    dashboardViewModel: DashboardViewModel,
    navController: NavController,
    onRendererCreated: (ArRenderer) -> Unit
) {
    val localNavController = rememberNavController()
    val navBackStackEntry by localNavController.currentBackStackEntryAsState()
    val currentNavRoute = navBackStackEntry?.destination?.route

    val uiState by viewModel.uiState.collectAsState()
    val arUiState by arViewModel.uiState.collectAsState()
    val editorUiState by editorViewModel.uiState.collectAsState()
    val dashboardUiState by dashboardViewModel.uiState.collectAsState()
    
    val context = LocalContext.current

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
            balance = "Balance", balanceInfo = "Color Tint",
            build = "Blend", blendingInfo = "Blend Mode",
            settings = "Settings", project = "Library",
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
    var hasSelectedModeOnce by remember { mutableStateOf(false) }

    val resetDialogs = remember { { showSliderDialog = null; showColorBalanceDialog = false } }

    val overlayImagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> 
        uri?.let { editorViewModel.onAddLayer(it) } 
    }
    val backgroundImagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> 
        uri?.let { editorViewModel.setBackgroundImage(it) }
    }

    // Permissions
    var hasCameraPermission by remember { mutableStateOf(false) }
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

    // Determine Rail Visibility
    val isRailVisible = !editorUiState.hideUiForCapture && !uiState.isTouchLocked

    // Active Highlight Color (Rotation)
    val activeHighlightColor = remember(editorUiState.activeRotationAxis) {
        when (editorUiState.activeRotationAxis) {
            RotationAxis.X -> Color.Red
            RotationAxis.Y -> Color.Green
            RotationAxis.Z -> Color.Blue
        }
    }

    AzHostActivityLayout(navController = localNavController) {
        if (isRailVisible) {
            azTheme(activeColor = activeHighlightColor, defaultShape = AzButtonShape.RECTANGLE, headerIconShape = AzHeaderIconShape.ROUNDED)
            azConfig(packButtons = true, dockingSide = if (editorUiState.isRightHanded) AzDockingSide.LEFT else AzDockingSide.RIGHT)
            
            azRailHostItem(id = "mode_host", text = navStrings.modes, onClick = {})
            azRailSubItem(id = "ar", hostId = "mode_host", text = navStrings.arMode, info = navStrings.arModeInfo, onClick = {
                if (hasCameraPermission) {
                    editorViewModel.setEditorMode(EditorMode.AR)
                } else {
                    requestPermissions()
                }
            })
            azRailSubItem(id = "overlay", hostId = "mode_host", text = navStrings.overlay, info = navStrings.overlayInfo, onClick = {
                if (hasCameraPermission) {
                    editorViewModel.setEditorMode(EditorMode.OVERLAY)
                } else {
                    requestPermissions()
                }
            })
            azRailSubItem(id = "mockup", hostId = "mode_host", text = navStrings.mockup, info = navStrings.mockupInfo, onClick = {
                editorViewModel.setEditorMode(EditorMode.STATIC)
            })
            azRailSubItem(id = "trace", hostId = "mode_host", text = navStrings.trace, info = navStrings.traceInfo, onClick = {
                editorViewModel.setEditorMode(EditorMode.TRACE)
            })
            
            azDivider()

            if (editorUiState.editorMode == EditorMode.AR) {
                azRailHostItem(id = "target_host", text = navStrings.grid, onClick = {})
                azRailSubItem(id = "surveyor", hostId = "target_host", text = navStrings.surveyor, info = navStrings.surveyorInfo, onClick = {
                    if (hasCameraPermission) {
                        localNavController.navigate("surveyor")
                        resetDialogs()
                    } else {
                        requestPermissions()
                    }
                })
                azDivider()
            }

            azRailHostItem(id = "design_host", text = navStrings.design, onClick = {})

            if (editorUiState.editorMode == EditorMode.STATIC) {
                azRailSubItem(id = "wall", hostId = "design_host", text = navStrings.wall, info = navStrings.wallInfo) {
                    resetDialogs()
                    backgroundImagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
            }

            val openButtonText = if (editorUiState.layers.isNotEmpty()) "Add" else navStrings.open
            val openButtonId = if (editorUiState.layers.isNotEmpty()) "add_layer" else "image"
            azRailSubItem(id = openButtonId, text = openButtonText, hostId = "design_host", info = navStrings.openInfo) {
                resetDialogs()
                overlayImagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }

            // Layer Management
            editorUiState.layers.reversed().forEach { layer ->
                azRailRelocItem(
                    id = "layer_${layer.id}", hostId = "design_host", text = layer.name,
                    onClick = {
                        if (editorUiState.activeLayerId != layer.id) editorViewModel.onLayerActivated(layer.id)
                    },
                    onRelocate = { _, _, newOrder -> editorViewModel.onLayerReordered(newOrder.map { it.removePrefix("layer_") }.reversed()) }
                ) {
                    inputItem(hint = "Rename") { editorViewModel.onLayerRenamed(layer.id, it) }
                    listItem(text = "Remove") { editorViewModel.onLayerRemoved(layer.id) }
                }
            }

            if (editorUiState.layers.isNotEmpty()) {
                azRailSubItem(id = "isolate", hostId = "design_host", text = navStrings.isolate, info = navStrings.isolateInfo, onClick = {
                    editorViewModel.onRemoveBackgroundClicked()
                    resetDialogs()
                })
                azRailSubItem(id = "outline", hostId = "design_host", text = navStrings.outline, info = navStrings.outlineInfo, onClick = {
                    editorViewModel.onLineDrawingClicked()
                    resetDialogs()
                })
                azDivider()
                azRailSubItem(id = "adjust", hostId = "design_host", text = navStrings.adjust, info = navStrings.adjustInfo) {
                    showSliderDialog = if (showSliderDialog == "Adjust") null else "Adjust"
                    showColorBalanceDialog = false
                }
                azRailSubItem(id = "blending", hostId = "design_host", text = navStrings.build, info = navStrings.blendingInfo, onClick = {
                    editorViewModel.onCycleBlendMode()
                    resetDialogs()
                })
                azRailSubToggle(id = "lock_image", hostId = "design_host", isChecked = editorUiState.isImageLocked, toggleOnText = "Locked", toggleOffText = "Unlocked", info = "Prevent accidental moves", onClick = {
                    editorViewModel.toggleImageLock()
                })
            }
            azDivider()
            azRailHostItem(id = "project_host", text = navStrings.project, onClick = {})
            azRailSubItem(id = "settings_sub", hostId = "project_host", text = navStrings.settings, info = "App Settings") {
                localNavController.navigate("settings")
                resetDialogs()
            }
            azRailSubItem(id = "load_project", hostId = "project_host", text = navStrings.load, info = navStrings.loadInfo) {
                localNavController.navigate("project_library")
                resetDialogs()
            }
            azDivider()
            
            azRailItem(id = "help", text = "Help", info = "Show Help") {
                showInfoScreen = true
                resetDialogs()
            }
            if (editorUiState.editorMode == EditorMode.AR) azRailItem(id = "ghost", text = "Ghost", info = "Toggle Point Cloud", onClick = {
                arViewModel.togglePointCloud()
                resetDialogs()
            })
            if (editorUiState.editorMode == EditorMode.AR || editorUiState.editorMode == EditorMode.OVERLAY) azRailItem(id = "light", text = navStrings.light, info = navStrings.lightInfo, onClick = {
                arViewModel.toggleFlashlight()
                resetDialogs()
            })
            if (editorUiState.editorMode == EditorMode.TRACE) azRailItem(id = "lock_trace", text = navStrings.lock, info = navStrings.lockInfo, onClick = {
                viewModel.setTouchLocked(true)
                resetDialogs()
            })
        }

        // Background / Content Area
        background(weight = 0) {
             if (currentNavRoute == "editor" || currentNavRoute == null) {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                     MainContentLayer(
                         editorUiState = editorUiState,
                         arUiState = arUiState,
                         editorViewModel = editorViewModel,
                         arViewModel = arViewModel,
                         onRendererCreated = onRendererCreated
                    )
                }
            } else {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black))
            }
        }

        // Onscreen Overlays & Navigation Host
        onscreen(alignment = Alignment.Center) {
            Box(modifier = Modifier.fillMaxSize()) {
                AzNavHost(startDestination = "editor") {
                    composable("editor") {
                        EditorUi(
                             actions = editorViewModel,
                             uiState = editorUiState,
                             isTouchLocked = uiState.isTouchLocked,
                             showUnlockInstructions = uiState.showUnlockInstructions
                        )
                    }
                    composable("surveyor") {
                        MappingScreen(
                            onMapSaved = { /* Optional callback */ },
                            onExit = { localNavController.popBackStack() },
                            onRendererCreated = { /* handled internally */ }
                        )
                    }
                    composable("project_library") {
                        LaunchedEffect(Unit) { dashboardViewModel.loadAvailableProjects() }
                        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                            ProjectLibraryScreen(
                                projects = dashboardUiState.availableProjects,
                                onLoadProject = {
                                    dashboardViewModel.openProject(it)
                                    localNavController.popBackStack()
                                },
                                onDeleteProject = { /* TODO: Implement project deletion */ },
                                onNewProject = {
                                    dashboardViewModel.onNewProject(editorUiState.isRightHanded)
                                    localNavController.popBackStack()
                                }
                            )
                            AzButton(text = "Back", onClick = {
                                localNavController.popBackStack()
                            }, modifier = Modifier.align(Alignment.TopStart).padding(16.dp))
                        }
                    }
                    composable("settings") {
                        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                            SettingsScreen(
                                currentVersion = "1.0.0",
                                updateStatus = "Up to date",
                                isCheckingForUpdate = false,
                                isRightHanded = editorUiState.isRightHanded,
                                onHandednessChanged = { /* Implement hand preference change in DashboardViewModel or EditorViewModel */ },
                                onCheckForUpdates = { },
                                onInstallUpdate = { },
                                onClose = { localNavController.popBackStack() }
                            )
                        }
                    }
                }
                
                TouchLockOverlay(uiState.isTouchLocked, viewModel::showUnlockInstructions)
                UnlockInstructionsPopup(uiState.showUnlockInstructions)
                
                 if (uiState.isCapturingTarget) {
                    Box(modifier = Modifier.fillMaxSize().zIndex(20f)) {
                         TargetCreationFlow(
                            uiState = arUiState,
                            isRightHanded = editorUiState.isRightHanded,
                            captureStep = uiState.captureStep,
                            context = context,
                            onConfirm = viewModel::onConfirmTargetCreation,
                            onRetake = viewModel::onRetakeCapture,
                            onCancel = viewModel::onCancelCaptureClicked,
                            onCaptureShutter = { },
                            onCalibrationPointCaptured = { },
                            onUnwarpImage = { _ -> }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MainContentLayer(
    editorUiState: EditorUiState,
    arUiState: com.hereliesaz.graffitixr.feature.ar.ArUiState,
    editorViewModel: EditorViewModel,
    arViewModel: ArViewModel,
    onRendererCreated: (ArRenderer) -> Unit
) {
    val backgroundImagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { editorViewModel.setBackgroundImage(it) }
    }

    Box(Modifier.fillMaxSize().zIndex(1f), contentAlignment = Alignment.Center) {
        
        val onScale: (Float) -> Unit = editorViewModel::onScaleChanged
        val onOffset: (Offset) -> Unit = editorViewModel::onOffsetChanged
        val onRotZ: (Float) -> Unit = editorViewModel::onRotationZChanged
        val onCycle: () -> Unit = editorViewModel::onCycleRotationAxis
        val onStart: () -> Unit = editorViewModel::onGestureStart
        
        val onOverlayGestureEnd: (Float, Offset, Float, Float, Float) -> Unit = { s, o, rx, ry, rz ->
             editorViewModel.setLayerTransform(s, o, rx, ry, rz)
             editorViewModel.onGestureEnd()
        }
        
        val gestureInProgress = editorUiState.gestureInProgress

        when (editorUiState.editorMode) {
            EditorMode.STATIC -> MockupScreen(
                uiState = editorUiState,
                onBackgroundImageSelected = {
                    backgroundImagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                onOverlayImageSelected = editorViewModel::onAddLayer,
                onOpacityChanged = editorViewModel::onOpacityChanged,
                onBrightnessChanged = editorViewModel::onBrightnessChanged,
                onContrastChanged = editorViewModel::onContrastChanged,
                onSaturationChanged = editorViewModel::onSaturationChanged,
                onCycleRotationAxis = onCycle,
                onGestureStart = onStart,
                onGestureEnd = onOverlayGestureEnd
            )
            EditorMode.TRACE -> TraceScreen(
                uiState = editorUiState,
                onOverlayImageSelected = editorViewModel::onAddLayer,
                onCycleRotationAxis = onCycle,
                onGestureStart = onStart,
                onGestureEnd = onOverlayGestureEnd
            )
            EditorMode.OVERLAY -> OverlayScreen(
                uiState = editorUiState, 
                onCycleRotationAxis = onCycle, 
                onGestureStart = onStart, 
                onGestureEnd = onOverlayGestureEnd
            )
            EditorMode.AR -> {
                Box(
                    modifier = Modifier.fillMaxSize()
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, rotation ->
                                if (!gestureInProgress) onStart()
                                if (zoom != 1f) onScale(zoom)
                                if (rotation != 0f) onRotZ(rotation)
                                if (pan != Offset.Zero) onOffset(pan)
                            }
                        }
                ) {
                    // Check for permissions locally for the view, but the main check is at the button
                    // Keep the view rendering if permitted, else blank (or handled by button)
                    ArView(viewModel = arViewModel, uiState = arUiState, onRendererCreated = onRendererCreated)
                }
            }
            else -> OverlayScreen(
                uiState = editorUiState, 
                onCycleRotationAxis = onCycle, 
                onGestureStart = onStart, 
                onGestureEnd = onOverlayGestureEnd
            )
        }
    }
}
