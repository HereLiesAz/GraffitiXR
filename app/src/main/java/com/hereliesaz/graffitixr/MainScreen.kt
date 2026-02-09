package com.hereliesaz.graffitixr

import android.graphics.Bitmap
import android.content.ContentValues
import android.provider.MediaStore
import android.view.PixelCopy
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
import androidx.compose.ui.platform.LocalView
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
import com.hereliesaz.graffitixr.common.model.ArUiState
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    val exportTrigger by editorViewModel.exportTrigger.collectAsState()
    
    val view = LocalView.current
    val context = LocalContext.current
    val window = (view.context as? android.app.Activity)?.window

    // Export Logic
    LaunchedEffect(exportTrigger) {
        if (exportTrigger && window != null) {
            // Wait for UI to hide (recomposition)
            delay(300)
            captureScreenshot(window) { bitmap ->
                saveExportedImage(context, bitmap)
                editorViewModel.onExportComplete()
            }
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
            balance = "Balance", balanceInfo = "Color Tint",
            build = "Blend", blendingInfo = "Blend Mode",
            settings = "Settings", project = "Project", // Renamed Library -> Project
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

    // Permissions
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
                    editorViewModel.onAdjustClicked()
                    resetDialogs()
                }
                azRailSubItem(id = "balance", hostId = "design_host", text = navStrings.balance, info = navStrings.balanceInfo) {
                    editorViewModel.onColorClicked()
                    resetDialogs()
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
            azRailHostItem(id = "project_host", text = navStrings.project, onClick = {}) // "Project"
            azRailSubItem(id = "save_project", hostId = "project_host", text = navStrings.save, info = navStrings.saveInfo) {
                editorViewModel.saveProject()
                resetDialogs()
            }
            azRailSubItem(id = "load_project", hostId = "project_host", text = navStrings.load, info = navStrings.loadInfo) {
                localNavController.navigate("project_library")
                resetDialogs()
            }
            azRailSubItem(id = "export_project", hostId = "project_host", text = navStrings.export, info = navStrings.exportInfo) {
                editorViewModel.exportProject()
                resetDialogs()
            }
            azRailSubItem(id = "settings_sub", hostId = "project_host", text = navStrings.settings, info = "App Settings") {
                localNavController.navigate("settings")
                resetDialogs()
            }
            azDivider()
            
            azRailItem(id = "help", text = "Help", info = "Show Help") {
                showInfoScreen = true
                resetDialogs()
            }
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
                val backgroundColor = if (editorUiState.editorMode == EditorMode.TRACE) Color.White else Color.Black
                Box(modifier = Modifier.fillMaxSize().background(backgroundColor)) {
                     MainContentLayer(
                         editorUiState = editorUiState,
                         arUiState = arUiState,
                         editorViewModel = editorViewModel,
                         arViewModel = arViewModel,
                         onRendererCreated = onRendererCreated,
                         onPickBackground = {
                             backgroundImagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                         }
                    )
                }
            } else {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black))
            }
        }

        // Onscreen Overlays & Navigation Host
        onscreen(alignment = Alignment.Center) {
            Box(modifier = Modifier.fillMaxSize()) {
                AzNavHost(startDestination = "project_library") { // Changed Start Destination
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
                                    // Navigate to editor after loading
                                    localNavController.navigate("editor") {
                                        popUpTo("project_library") { inclusive = false }
                                    }
                                },
                                onDeleteProject = { /* TODO: Implement project deletion */ },
                                onNewProject = {
                                    dashboardViewModel.onNewProject(editorUiState.isRightHanded)
                                    // Navigate to editor after creating new
                                    localNavController.navigate("editor") {
                                        popUpTo("project_library") { inclusive = false }
                                    }
                                }
                            )
                            // "Back" button removed as this is now the start screen.
                            // However, if navigating from "Load", we might want a back button.
                            // But usually Library replaces Editor.
                            // The user says "initial screen ... is a list of projects".
                            // So this is the root.
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
    arUiState: ArUiState,
    editorViewModel: EditorViewModel,
    arViewModel: ArViewModel,
    onRendererCreated: (ArRenderer) -> Unit,
    onPickBackground: () -> Unit
) {
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
                onBackgroundImageSelected = { onPickBackground() },
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
                isFlashlightOn = arUiState.isFlashlightOn,
                onOverlayImageSelected = editorViewModel::onAddLayer,
                onCycleRotationAxis = onCycle,
                onGestureStart = onStart,
                onGestureEnd = onOverlayGestureEnd
            )
            EditorMode.OVERLAY -> OverlayScreen(
                uiState = editorUiState,
                isFlashlightOn = arUiState.isFlashlightOn,
                onCycleRotationAxis = onCycle, 
                onGestureStart = onStart, 
                onGestureEnd = onOverlayGestureEnd
            )
            EditorMode.AR -> {
                Box(modifier = Modifier.fillMaxSize()) {
                    // AR View (Background - Handles Camera & Flashlight via ARCore)
                    ArView(viewModel = arViewModel, uiState = arUiState, onRendererCreated = onRendererCreated)

                    // Overlay Screen (Foreground - Handles Layers & Gestures, Camera disabled)
                    OverlayScreen(
                        uiState = editorUiState,
                        isFlashlightOn = false, // Handled by ArView/ArRenderer
                        onCycleRotationAxis = onCycle,
                        onGestureStart = onStart,
                        onGestureEnd = onOverlayGestureEnd,
                        showCamera = false
                    )
                }
            }
            else -> OverlayScreen(
                uiState = editorUiState,
                isFlashlightOn = arUiState.isFlashlightOn,
                onCycleRotationAxis = onCycle, 
                onGestureStart = onStart, 
                onGestureEnd = onOverlayGestureEnd
            )
        }
    }
}

fun captureScreenshot(window: android.view.Window, onCaptured: (Bitmap) -> Unit) {
    val bitmap = Bitmap.createBitmap(
        window.decorView.width,
        window.decorView.height,
        Bitmap.Config.ARGB_8888
    )
    val location = IntArray(2)
    window.decorView.getLocationInWindow(location)

    val handler = android.os.Handler(android.os.Looper.getMainLooper())

    try {
        PixelCopy.request(
            window,
            android.graphics.Rect(location[0], location[1], location[0] + window.decorView.width, location[1] + window.decorView.height),
            bitmap,
            { result ->
                if (result == PixelCopy.SUCCESS) {
                    onCaptured(bitmap)
                }
            },
            handler
        )
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

fun saveExportedImage(context: android.content.Context, bitmap: Bitmap) {
    try {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "Export_$timestamp.webp"

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/webp")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/GraffitiXR")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

        uri?.let {
            context.contentResolver.openOutputStream(it).use { out ->
                if (out != null) {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                        bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, out)
                    } else {
                        bitmap.compress(Bitmap.CompressFormat.WEBP, 100, out)
                    }
                }
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                context.contentResolver.update(it, contentValues, null, null)
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
}
