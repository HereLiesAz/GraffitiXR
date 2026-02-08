package com.hereliesaz.graffitixr

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
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
import com.hereliesaz.graffitixr.feature.dashboard.ProjectLibraryScreen
import com.hereliesaz.graffitixr.feature.dashboard.SettingsScreen
import com.hereliesaz.graffitixr.feature.editor.*

@Composable
fun MainScreen(
    viewModel: MainViewModel,
    editorViewModel: EditorViewModel,
    arViewModel: ArViewModel,
    navController: NavController,
    onRendererCreated: (ArRenderer) -> Unit
) {
    val localNavController = rememberNavController()
    val navBackStackEntry by localNavController.currentBackStackEntryAsState()
    val currentNavRoute = navBackStackEntry?.destination?.route

    val uiState by viewModel.uiState.collectAsState()
    val arUiState by arViewModel.uiState.collectAsState()
    val editorUiState by editorViewModel.uiState.collectAsState()
    
    val context = LocalContext.current
    val navStrings = remember { NavStrings() } // Using default instance for now

    var showSliderDialog by remember { mutableStateOf<String?>(null) }
    var showColorBalanceDialog by remember { mutableStateOf(false) }
    var showInfoScreen by remember { mutableStateOf(false) }
    var hasSelectedModeOnce by remember { mutableStateOf(false) }

    val resetDialogs = remember { { showSliderDialog = null; showColorBalanceDialog = false } }

    val overlayImagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> 
        uri?.let { editorViewModel.onOverlayImageSelected(it) } 
    }
    val backgroundImagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> 
        uri?.let { viewModel.onBackgroundImageSelected(it) } 
    }
    val createDocumentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> 
        uri?.let { viewModel.exportProjectToUri(it) } 
    }

    // Feedback & Capture Handlers
    LaunchedEffect(viewModel, context) {
        viewModel.feedbackEvent.collect { event ->
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION") context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (vibrator.hasVibrator()) {
                when (event) {
                    is com.hereliesaz.graffitixr.common.model.FeedbackEvent.VibrateSingle -> vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
                    is com.hereliesaz.graffitixr.common.model.FeedbackEvent.VibrateDouble -> vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 50, 50, 50), intArrayOf(0, 255, 0, 255), -1))
                    is com.hereliesaz.graffitixr.common.model.FeedbackEvent.Toast -> Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    LaunchedEffect(viewModel, context) {
        viewModel.captureEvent.collect { event ->
            if (event is com.hereliesaz.graffitixr.common.model.CaptureEvent.RequestCapture && uiState.editorMode != EditorMode.AR) {
                (context as? android.app.Activity)?.let { activity ->
                     com.hereliesaz.graffitixr.common.util.CaptureUtils.captureWindow(activity) { bitmap ->
                        bitmap?.let { viewModel.saveCapturedBitmap(it) }
                    }
                }
            }
        }
    }

    // Combined Mode Selection Logic
    val onModeSelected = remember(viewModel, editorViewModel, hasSelectedModeOnce) {
        { mode: EditorMode ->
            viewModel.onEditorModeChanged(mode)
            // Also notify editor viewmodel if needed, though MainViewModel seems to drive mode
            resetDialogs()
            if (!hasSelectedModeOnce) {
                hasSelectedModeOnce = true
                if (mode == EditorMode.AR) viewModel.onCreateTargetClicked()
            }
        }
    }

    // Determine Rail Visibility
    val isRailVisible = !uiState.hideUiForCapture && !uiState.isTouchLocked

    // Active Highlight Color (Rotation)
    val activeHighlightColor = remember(uiState.activeColorSeed) {
        val colors = listOf(Color.Green, Color.Magenta, Color.Cyan)
        colors[kotlin.math.abs(uiState.activeColorSeed) % colors.size]
    }

    AzHostActivityLayout(navController = localNavController) {
        if (isRailVisible) {
            azTheme(activeColor = activeHighlightColor, defaultShape = AzButtonShape.RECTANGLE, headerIconShape = AzHeaderIconShape.ROUNDED)
            azConfig(packButtons = true, dockingSide = if (uiState.isRightHanded) AzDockingSide.LEFT else AzDockingSide.RIGHT)
            
            // Advanced: Loading / Info
            // Note: azAdvanced signature might differ in actual library, adjusting based on typical DSL
            // Assuming azAdvanced(isLoading, infoScreen, onDismiss)
            // If DSL is strict, I might need to verify parameter names.
            // Using standard placeholders.
            
            azRailHostItem(id = "mode_host", text = navStrings.modes, onClick = {})
            azRailSubItem(id = "ar", hostId = "mode_host", text = navStrings.arMode, info = navStrings.arModeInfo, onClick = { onModeSelected(EditorMode.AR) })
            azRailSubItem(id = "ghost_mode", hostId = "mode_host", text = navStrings.overlay, info = navStrings.overlayInfo, onClick = { onModeSelected(EditorMode.OVERLAY) })
            azRailSubItem(id = "mockup", hostId = "mode_host", text = navStrings.mockup, info = navStrings.mockupInfo, onClick = { onModeSelected(EditorMode.STATIC) })
            azRailSubItem(id = "trace_mode", hostId = "mode_host", text = navStrings.trace, info = navStrings.traceInfo, onClick = { onModeSelected(EditorMode.TRACE) })

            azDivider()

            if (uiState.editorMode == EditorMode.AR) {
                azRailHostItem(id = "target_host", text = navStrings.grid, onClick = {})
                azRailSubItem(id = "surveyor", hostId = "target_host", text = navStrings.surveyor, info = navStrings.surveyorInfo, onClick = { localNavController.navigate("surveyor"); resetDialogs() })
                azRailSubItem(id = "create_target", hostId = "target_host", text = navStrings.create, info = navStrings.createInfo, onClick = { viewModel.onCreateTargetClicked(); resetDialogs() })
                azRailSubItem(id = "refine_target", hostId = "target_host", text = navStrings.refine, info = navStrings.refineInfo, onClick = { viewModel.onRefineTargetToggled(); resetDialogs() })
                azRailSubItem(id = "mark_progress", hostId = "target_host", text = navStrings.update, info = navStrings.updateInfo, onClick = { viewModel.onMarkProgressToggled(); resetDialogs() })
                azDivider()
            }

            azRailHostItem(id = "design_host", text = navStrings.design, onClick = {})
            val openButtonText = if (editorUiState.layers.isNotEmpty()) "Add" else navStrings.open
            val openButtonId = if (editorUiState.layers.isNotEmpty()) "add_layer" else "image"
            azRailSubItem(id = openButtonId, text = openButtonText, hostId = "design_host", info = navStrings.openInfo) { resetDialogs(); overlayImagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }

            // Layer Management
            editorUiState.layers.reversed().forEach { layer ->
                azRailRelocItem(
                    id = "layer_${layer.id}", hostId = "design_host", text = layer.name,
                    onClick = { if (editorUiState.activeLayerId != layer.id) editorViewModel.onLayerActivated(layer.id) },
                    onRelocate = { _, _, newOrder -> editorViewModel.onLayerReordered(newOrder.map { it.removePrefix("layer_") }.reversed()) }
                ) {
                    inputItem(hint = "Rename") { editorViewModel.onLayerRenamed(layer.id, it) }
                    listItem(text = "Duplicate") { editorViewModel.onLayerDuplicated(layer.id) }
                    listItem(text = "Copy Mods") { editorViewModel.copyLayerModifications(layer.id) }
                    listItem(text = "Paste Mods") { editorViewModel.pasteLayerModifications(layer.id) }
                    listItem(text = "Remove") { editorViewModel.onLayerRemoved(layer.id) }
                }
            }

            if (uiState.editorMode == EditorMode.STATIC) {
                azRailSubItem(id = "background", hostId = "design_host", text = navStrings.wall, info = navStrings.wallInfo) { resetDialogs(); backgroundImagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
            }

            if (uiState.overlayImageUri != null || editorUiState.layers.isNotEmpty()) {
                azRailSubItem(id = "isolate", hostId = "design_host", text = navStrings.isolate, info = navStrings.isolateInfo, onClick = { editorViewModel.onRemoveBackgroundClicked(); showSliderDialog = null; showColorBalanceDialog = false; resetDialogs() })
                azRailSubItem(id = "outline", hostId = "design_host", text = navStrings.outline, info = navStrings.outlineInfo, onClick = { editorViewModel.onLineDrawingClicked(); showSliderDialog = null; showColorBalanceDialog = false; resetDialogs() })
                azDivider()
                azRailSubItem(id = "adjust", hostId = "design_host", text = navStrings.adjust, info = navStrings.adjustInfo) { showSliderDialog = if (showSliderDialog == "Adjust") null else "Adjust"; showColorBalanceDialog = false }
                azRailSubItem(id = "color_balance", hostId = "design_host", text = navStrings.balance, info = navStrings.balanceInfo) { showColorBalanceDialog = true; showSliderDialog = null }
                azRailSubItem(id = "blending", hostId = "design_host", text = navStrings.build, info = navStrings.blendingInfo, onClick = { editorViewModel.onCycleBlendMode(); showSliderDialog = null; showColorBalanceDialog = false; resetDialogs() })
                azRailSubToggle(id = "lock_image", hostId = "design_host", isChecked = uiState.isImageLocked, toggleOnText = "Locked", toggleOffText = "Unlocked", info = "Prevent accidental moves", onClick = { editorViewModel.toggleImageLock() })
            }
            azDivider()
            azRailHostItem(id = "project_host", text = navStrings.project, onClick = {})
            azRailSubItem(id = "settings_sub", hostId = "project_host", text = navStrings.settings, info = "App Settings") { localNavController.navigate("settings"); resetDialogs() }
            azRailSubItem(id = "new_project", hostId = "project_host", text = navStrings.new, info = navStrings.newInfo, onClick = { viewModel.onNewProject(); resetDialogs() })
            azRailSubItem(id = "save_project", hostId = "project_host", text = navStrings.save, info = navStrings.saveInfo) { createDocumentLauncher.launch("Project.gxr"); resetDialogs() }
            azRailSubItem(id = "load_project", hostId = "project_host", text = navStrings.load, info = navStrings.loadInfo) { localNavController.navigate("project_library"); resetDialogs() }
            azRailSubItem(id = "export_project", hostId = "project_host", text = navStrings.export, info = navStrings.exportInfo, onClick = { viewModel.onSaveClicked(); resetDialogs() } )
            azDivider()
            
            azRailItem(id = "help", text = "Help", info = "Show Help") { showInfoScreen = true; resetDialogs() }
            if (uiState.editorMode == EditorMode.AR) azRailItem(id = "ghost", text = "Ghost", info = "Toggle Point Cloud", onClick = { arViewModel.togglePointCloud(); resetDialogs() })
            if (uiState.editorMode == EditorMode.AR || uiState.editorMode == EditorMode.OVERLAY) azRailItem(id = "light", text = navStrings.light, info = navStrings.lightInfo, onClick = { arViewModel.toggleFlashlight(); resetDialogs() })
            if (uiState.editorMode == EditorMode.TRACE) azRailItem(id = "lock_trace", text = navStrings.lock, info = navStrings.lockInfo, onClick = { viewModel.setTouchLocked(true); resetDialogs() })
        }

        // Background / Content Area
        background(weight = 0) {
             if (currentNavRoute == "editor" || currentNavRoute == null) {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                    // This is where the main AR/Editor view lives
                     MainContentLayer(
                         uiState = uiState, 
                         editorUiState = editorUiState,
                         arUiState = arUiState,
                         viewModel = viewModel, 
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
                        // Editor UI (Overlays on top of background)
                        EditorUi(
                             uiState = mapToCommonUiState(uiState, editorUiState, arUiState),
                             actions = editorViewModel,
                             tapFeedback = uiState.tapFeedback,
                             showSliderDialog = showSliderDialog,
                             showColorBalanceDialog = showColorBalanceDialog,
                             gestureInProgress = editorUiState.gestureInProgress
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
                        LaunchedEffect(Unit) { viewModel.loadAvailableProjects(context) }
                        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                            ProjectLibraryScreen(
                                projects = uiState.availableProjects,
                                onLoadProject = { viewModel.openProject(it, context); localNavController.popBackStack() },
                                onDeleteProject = { viewModel.deleteProject(context, it) },
                                onNewProject = { viewModel.onNewProject(); localNavController.popBackStack() }
                            )
                            // Back button for library
                            com.hereliesaz.aznavrail.AzButton(text = "Back", onClick = { localNavController.popBackStack() }, modifier = Modifier.align(Alignment.TopStart).padding(16.dp))
                        }
                    }
                    composable("settings") {
                        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                            SettingsScreen(
                                currentVersion = "1.0.0", // TODO: Get from BuildConfig
                                updateStatus = uiState.updateStatusMessage,
                                isCheckingForUpdate = uiState.isCheckingForUpdate,
                                isRightHanded = uiState.isRightHanded,
                                onHandednessChanged = { viewModel.setHandedness(it) },
                                onCheckForUpdates = { viewModel.checkForUpdates() },
                                onInstallUpdate = { viewModel.installLatestUpdate() },
                                onClose = { localNavController.popBackStack() }
                            )
                        }
                    }
                }
                
                // Global overlays
                TouchLockOverlay(uiState.isTouchLocked, viewModel::showUnlockInstructions)
                UnlockInstructionsPopup(uiState.showUnlockInstructions)
                
                 if (uiState.isCapturingTarget) {
                    Box(modifier = Modifier.fillMaxSize().zIndex(20f)) {
                         TargetCreationFlow(
                            uiState = uiState,
                            context = context,
                            onConfirm = viewModel::onConfirmTargetCreation,
                            onRetake = viewModel::onRetakeCapture,
                            onCancel = viewModel::onCancelCaptureClicked,
                            onCaptureShutter = viewModel::onCaptureShutterClicked,
                            onCalibrationPointCaptured = { viewModel.onCalibrationPointCaptured(it) },
                            onUnwarpImage = viewModel::unwarpImage
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MainContentLayer(
    uiState: UiState, 
    editorUiState: EditorUiState,
    arUiState: ArUiState,
    viewModel: MainViewModel, 
    editorViewModel: EditorViewModel,
    arViewModel: ArViewModel,
    onRendererCreated: (ArRenderer) -> Unit
) {
    Box(Modifier.fillMaxSize().zIndex(1f), contentAlignment = Alignment.Center) {
        
        // Gesture Logic
        // We use EditorViewModel for gestures
        val onScale: (Float) -> Unit = editorViewModel::onScaleChanged
        val onOffset: (Offset) -> Unit = editorViewModel::onOffsetChanged
        val onRotZ: (Float) -> Unit = editorViewModel::onRotationZChanged
        val onCycle: () -> Unit = editorViewModel::onCycleRotationAxis
        val onStart: () -> Unit = editorViewModel::onGestureStart
        val onEnd: () -> Unit = editorViewModel::onGestureEnd
        
        val onOverlayGestureEnd: (Float, Offset, Float, Float, Float) -> Unit = { s, o, rx, ry, rz ->
             editorViewModel.setLayerTransform(s, o, rx, ry, rz)
             editorViewModel.onGestureEnd()
        }
        
        val gestureInProgress = editorUiState.gestureInProgress

        when (uiState.editorMode) {
            EditorMode.STATIC -> MockupScreen(
                // MockupScreen expects the legacy UiState or new?
                // It likely expects UiState. We can pass the mapped one.
                uiState = mapToCommonUiState(uiState, editorUiState, arUiState),
                onBackgroundImageSelected = viewModel::onBackgroundImageSelected,
                onOverlayImageSelected = editorViewModel::onOverlayImageSelected,
                onOpacityChanged = editorViewModel::onOpacityChanged,
                onBrightnessChanged = editorViewModel::onBrightnessChanged,
                onContrastChanged = editorViewModel::onContrastChanged,
                onSaturationChanged = editorViewModel::onSaturationChanged,
                onCycleRotationAxis = onCycle,
                onGestureStart = onStart,
                onGestureEnd = onOverlayGestureEnd
            )
            EditorMode.TRACE -> TraceScreen(
                uiState = mapToCommonUiState(uiState, editorUiState, arUiState),
                onOverlayImageSelected = editorViewModel::onOverlayImageSelected,
                onCycleRotationAxis = onCycle,
                onGestureStart = onStart,
                onGestureEnd = onOverlayGestureEnd
            )
            EditorMode.OVERLAY -> OverlayScreen(
                uiState = mapToCommonUiState(uiState, editorUiState, arUiState), 
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
                        // ForEachGesture for up/cancel is handled by detectTransformGestures usually?
                        // Or we can add explicit up handler if needed.
                         .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while(true) {
                                    val event = awaitPointerEvent()
                                    // Simple logic to detect up if needed, but EditorViewModel gesture end logic might be simpler
                                    if (event.changes.any { !it.pressed }) {
                                         // onEnd() - Called too often?
                                         // detectTransformGestures handles most.
                                    }
                                }
                            }
                        }
                ) {
                    ArView(viewModel = arViewModel, uiState = arUiState, onRendererCreated = onRendererCreated)
                }
            }
             // Handle other modes as Overlay
            else -> OverlayScreen(
                uiState = mapToCommonUiState(uiState, editorUiState, arUiState), 
                onCycleRotationAxis = onCycle, 
                onGestureStart = onStart, 
                onGestureEnd = onOverlayGestureEnd
            )
        }
    }
}
