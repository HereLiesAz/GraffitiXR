package com.hereliesaz.graffitixr

import android.content.ContentValues
import android.graphics.Bitmap
import android.provider.MediaStore
import android.view.PixelCopy
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
import com.hereliesaz.aznavrail.model.AzButtonShape
import com.hereliesaz.aznavrail.model.AzDockingSide
import com.hereliesaz.aznavrail.model.AzHeaderIconShape
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
import com.hereliesaz.graffitixr.feature.ar.TargetCreationFlow
import com.hereliesaz.graffitixr.feature.dashboard.DashboardViewModel
import com.hereliesaz.graffitixr.feature.dashboard.ProjectLibraryScreen
import com.hereliesaz.graffitixr.feature.dashboard.SettingsScreen
import com.hereliesaz.graffitixr.feature.editor.*
import com.hereliesaz.graffitixr.feature.editor.GsViewer
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

    val haptic = LocalHapticFeedback.current
    val performHaptic = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) }

    var use3dBackground by remember { mutableStateOf(false) }

    val has3dModel = remember(editorUiState.mapPath) {
        !editorUiState.mapPath.isNullOrEmpty() && File(editorUiState.mapPath!!).exists()
    }

    var renderRef by remember { mutableStateOf<ArRenderer?>(null) }
    val onRendererCreatedWrapper: (ArRenderer) -> Unit = { renderer ->
        renderRef = renderer
        onRendererCreated(renderer)
    }

    LaunchedEffect(exportTrigger) {
        if (exportTrigger && window != null) {
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
                performHaptic()
                if (hasCameraPermission) {
                    editorViewModel.setEditorMode(EditorMode.AR)
                } else {
                    requestPermissions()
                }
            })
            azRailSubItem(id = "overlay", hostId = "mode_host", text = navStrings.overlay, info = navStrings.overlayInfo, onClick = {
                performHaptic()
                if (hasCameraPermission) {
                    editorViewModel.setEditorMode(EditorMode.OVERLAY)
                } else {
                    requestPermissions()
                }
            })
            azRailSubItem(id = "mockup", hostId = "mode_host", text = navStrings.mockup, info = navStrings.mockupInfo, onClick = {
                performHaptic()
                editorViewModel.setEditorMode(EditorMode.STATIC)
            })
            azRailSubItem(id = "trace", hostId = "mode_host", text = navStrings.trace, info = navStrings.traceInfo, onClick = {
                performHaptic()
                editorViewModel.setEditorMode(EditorMode.TRACE)
            })

            azDivider()

            if (editorUiState.editorMode == EditorMode.AR) {
                azRailHostItem(id = "target_host", text = navStrings.grid, onClick = {})
                azRailSubItem(id = "create", hostId = "target_host", text = navStrings.create, info = navStrings.createInfo, onClick = {
                    performHaptic()
                    if (hasCameraPermission) {
                        viewModel.startTargetCapture()
                        resetDialogs()
                    } else {
                        requestPermissions()
                    }
                })
                azRailSubItem(id = "surveyor", hostId = "target_host", text = navStrings.surveyor, info = navStrings.surveyorInfo, onClick = {
                    performHaptic()
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
                    performHaptic()
                    resetDialogs()
                    backgroundImagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }

                if (has3dModel) {
                    azRailSubToggle(
                        id = "toggle_3d",
                        hostId = "design_host",
                        isChecked = use3dBackground,
                        toggleOnText = "3D View",
                        toggleOffText = "2D View",
                        info = "Switch Mockup",
                        onClick = {
                            performHaptic()
                            use3dBackground = !use3dBackground
                        }
                    )
                }
            }

            val openButtonText = if (editorUiState.layers.isNotEmpty()) "Add" else navStrings.open
            val openButtonId = if (editorUiState.layers.isNotEmpty()) "add_layer" else "image"
            azRailSubItem(id = openButtonId, text = openButtonText, hostId = "design_host", info = navStrings.openInfo) {
                performHaptic()
                resetDialogs()
                overlayImagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }

            editorUiState.layers.reversed().forEach { layer ->
                azRailRelocItem(
                    id = "layer_${layer.id}", hostId = "design_host", text = layer.name,
                    onClick = {
                        performHaptic()
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
                    performHaptic()
                    editorViewModel.onRemoveBackgroundClicked()
                    resetDialogs()
                })
                azRailSubItem(id = "outline", hostId = "design_host", text = navStrings.outline, info = navStrings.outlineInfo, onClick = {
                    performHaptic()
                    editorViewModel.onLineDrawingClicked()
                    resetDialogs()
                })
                azDivider()
                azRailSubItem(id = "adjust", hostId = "design_host", text = navStrings.adjust, info = navStrings.adjustInfo) {
                    performHaptic()
                    editorViewModel.onAdjustClicked()
                    resetDialogs()
                }
                azRailSubItem(id = "balance", hostId = "design_host", text = navStrings.balance, info = navStrings.balanceInfo) {
                    performHaptic()
                    editorViewModel.onColorClicked()
                    resetDialogs()
                }
                azRailSubItem(id = "blending", hostId = "design_host", text = navStrings.build, info = navStrings.blendingInfo, onClick = {
                    performHaptic()
                    editorViewModel.onCycleBlendMode()
                    resetDialogs()
                })
                azRailSubToggle(id = "lock_image", hostId = "design_host", isChecked = editorUiState.isImageLocked, toggleOnText = "Locked", toggleOffText = "Unlocked", info = "Prevent accidental moves", onClick = {
                    performHaptic()
                    editorViewModel.toggleImageLock()
                })
            }
            azDivider()
            azRailHostItem(id = "project_host", text = navStrings.project, onClick = {})
            azRailSubItem(id = "save_project", hostId = "project_host", text = navStrings.save, info = navStrings.saveInfo) {
                performHaptic()
                editorViewModel.saveProject()
                resetDialogs()
            }
            azRailSubItem(id = "load_project", hostId = "project_host", text = navStrings.load, info = navStrings.loadInfo) {
                performHaptic()
                localNavController.navigate("project_library")
                resetDialogs()
            }
            azRailSubItem(id = "export_project", hostId = "project_host", text = navStrings.export, info = navStrings.exportInfo) {
                performHaptic()
                editorViewModel.exportProject()
                resetDialogs()
            }
            azRailSubItem(id = "settings_sub", hostId = "project_host", text = navStrings.settings, info = "App Settings") {
                performHaptic()
                localNavController.navigate("settings")
                resetDialogs()
            }
            azDivider()

            azRailItem(id = "help", text = "Help", info = "Show Help") {
                performHaptic()
                showInfoScreen = true
                resetDialogs()
            }
            if (editorUiState.editorMode == EditorMode.AR || editorUiState.editorMode == EditorMode.OVERLAY) azRailItem(id = "light", text = navStrings.light, info = navStrings.lightInfo, onClick = {
                performHaptic()
                arViewModel.toggleFlashlight()
                resetDialogs()
            })
            if (editorUiState.editorMode == EditorMode.TRACE) azRailItem(id = "lock_trace", text = navStrings.lock, info = navStrings.lockInfo, onClick = {
                performHaptic()
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
                        onRendererCreated = onRendererCreatedWrapper,
                        onPickBackground = {
                            backgroundImagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                        use3dBackground = use3dBackground
                    )
                }
            } else {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black))
            }
        }

        // Onscreen Overlays & Navigation Host
        onscreen(alignment = Alignment.Center) {
            Box(modifier = Modifier.fillMaxSize()) {
                AzNavHost(startDestination = "project_library") {
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
                                    localNavController.navigate("editor") {
                                        popUpTo("project_library") { inclusive = false }
                                    }
                                },
                                onDeleteProject = { /* TODO */ },
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

                // Fixed: Explicitly passing 'true' to showUnlockInstructions
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
                            onCaptureShutter = {
                                renderRef?.captureFrame { bitmap ->
                                    arViewModel.setTempCapture(bitmap)
                                    viewModel.setCaptureStep(CaptureStep.RECTIFY)
                                }
                            },
                            onCalibrationPointCaptured = { },
                            onUnwarpImage = { points ->
                                arUiState.tempCaptureBitmap?.let { src ->
                                    ImageProcessor.unwarpImage(src, points)?.let { unwarped ->
                                        saveBitmapToCache(context, unwarped)?.let { uri ->
                                            arViewModel.onFrameCaptured(unwarped, uri)
                                            viewModel.setCaptureStep(CaptureStep.REVIEW)
                                        }
                                    }
                                }
                            }
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
    onPickBackground: () -> Unit,
    use3dBackground: Boolean = false
) {
    Box(Modifier.fillMaxSize().zIndex(1f), contentAlignment = Alignment.Center) {

        val onScale: (Float) -> Unit = editorViewModel::onScaleChanged
        val onOffset: (Offset) -> Unit = editorViewModel::onOffsetChanged
        val onRotZ: (Float) -> Unit = editorViewModel::onRotationZChanged
        val onCycle: () -> Unit = editorViewModel::onCycleRotationAxis
        val onStart: () -> Unit = editorViewModel::onGestureStart

        when (editorUiState.editorMode) {
            EditorMode.AR -> {
                ArView(
                    viewModel = arViewModel,
                    uiState = arUiState,
                    onRendererCreated = onRendererCreated
                )
            }
            EditorMode.STATIC -> {
                if (use3dBackground && !editorUiState.mapPath.isNullOrEmpty()) {
                    GsViewer(
                        mapPath = editorUiState.mapPath!!,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    if (editorUiState.backgroundBitmap != null) {
                        Image(
                            bitmap = editorUiState.backgroundBitmap!!.asImageBitmap(),
                            contentDescription = "Mockup Background",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    } else {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("No Background Image", color = Color.Gray)
                        }
                    }
                }
            }
            EditorMode.TRACE -> {
                Box(Modifier.fillMaxSize().background(Color.White))
            }
            EditorMode.OVERLAY -> {
                ArView(
                    viewModel = arViewModel,
                    uiState = arUiState.copy(showPointCloud = false),
                    onRendererCreated = onRendererCreated
                )
            }
            else -> {
                Box(Modifier.fillMaxSize().background(Color.Black))
            }
        }

        if (editorUiState.layers.isNotEmpty()) {
            if (editorUiState.editorMode == EditorMode.AR && arUiState.isTargetDetected) {
                // AR Scene rendering
            } else {
                val modifier = if (!editorUiState.isImageLocked) {
                    Modifier.pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, rotation ->
                            onStart()
                            onScale(zoom)
                            onOffset(pan)
                            onRotZ(rotation)
                        }
                    }
                } else Modifier

                Box(modifier = modifier.fillMaxSize())
            }
        }
    }
}

// ... [Helper functions remain same as before] ...
fun captureScreenshot(window: android.view.Window, onCaptured: (Bitmap) -> Unit) {
    val view = window.decorView
    val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
    try {
        PixelCopy.request(window, android.graphics.Rect(0, 0, view.width, view.height), bitmap, { copyResult ->
            if (copyResult == PixelCopy.SUCCESS) {
                onCaptured(bitmap)
            }
        }, android.os.Handler(android.os.Looper.getMainLooper()))
    } catch (e: IllegalArgumentException) {
        e.printStackTrace()
    }
}

fun saveExportedImage(context: android.content.Context, bitmap: Bitmap) {
    val filename = "GraffitiXR_Export_${System.currentTimeMillis()}.png"
    val contentValues = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
        put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
        put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/GraffitiXR")
    }

    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

    uri?.let {
        resolver.openOutputStream(it)?.use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        }
    }
}

fun saveBitmapToCache(context: android.content.Context, bitmap: Bitmap): android.net.Uri? {
    val filename = "Target_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"
    val file = File(context.cacheDir, filename)
    FileOutputStream(file).use { out ->
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
    }
    return androidx.core.content.FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file
    )
}