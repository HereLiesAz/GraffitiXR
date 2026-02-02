package com.hereliesaz.graffitixr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.forEachGesture
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.compose.composable
import com.hereliesaz.aznavrail.AzHostActivityLayout
import com.hereliesaz.aznavrail.AzNavHost
import com.hereliesaz.aznavrail.model.AzButtonShape
import com.hereliesaz.aznavrail.model.AzDockingSide
import com.hereliesaz.aznavrail.model.AzHeaderIconShape
import com.hereliesaz.graffitixr.feature.ar.*

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun MainScreen(viewModel: MainViewModel, navController: NavController, onRendererCreated: (ArRenderer) -> Unit) {
    val localNavController = rememberNavController()
    val navBackStackEntry by localNavController.currentBackStackEntryAsState()
    val currentNavRoute = navBackStackEntry?.destination?.route

    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val configuration = LocalConfiguration.current

    var showSliderDialog by remember { mutableStateOf<String?>(null) }
    var showColorBalanceDialog by remember { mutableStateOf(false) }
    var showInfoScreen by remember { mutableStateOf(false) }
    var hasSelectedModeOnce by remember { mutableStateOf(false) }
    var gestureInProgress by remember { mutableStateOf(false) }

    val resetDialogs = remember { { showSliderDialog = null; showColorBalanceDialog = false } }

    val overlayImagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { viewModel.onOverlayImageSelected(it) } }
    val backgroundImagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { viewModel.onBackgroundImageSelected(it) } }
    val createDocumentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> uri?.let { viewModel.exportProjectToUri(it) } }

    LaunchedEffect(uiState.showImagePicker) {
        if (uiState.showImagePicker) {
            overlayImagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            viewModel.onImagePickerShown()
        }
    }

    LaunchedEffect(uiState.editorMode) {
        if (uiState.editorMode == TRACE && uiState.overlayImageUri == null) {
            overlayImagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
    }

    val onModeSelected = remember(viewModel, hasSelectedModeOnce) {
        { mode: EditorMode ->
            viewModel.onEditorModeChanged(mode)
            resetDialogs()
            if (!hasSelectedModeOnce) {
                hasSelectedModeOnce = true
                if (mode == AR) viewModel.onCreateTargetClicked()
            }
        }
    }

    val activeHighlightColor = remember(uiState.activeColorSeed) {
        val colors = listOf(Color.Green, Color.Magenta, Color.Cyan)
        colors[kotlin.math.abs(uiState.activeColorSeed) % colors.size]
    }
    val navStrings = rememberNavStrings()

    LaunchedEffect(viewModel, context) {
        viewModel.feedbackEvent.collect { event ->
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION") context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (vibrator.hasVibrator()) {
                when (event) {
                    is FeedbackEvent.VibrateSingle -> vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
                    is FeedbackEvent.VibrateDouble -> vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 50, 50, 50), intArrayOf(0, 255, 0, 255), -1))
                    is FeedbackEvent.Toast -> android.widget.Toast.makeText(context, event.message, android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    LaunchedEffect(viewModel, context) {
        viewModel.captureEvent.collect { event ->
            if (event is CaptureEvent.RequestCapture && uiState.editorMode != AR) {
                context.findActivity()?.let { activity ->
                    captureWindow(activity) { bitmap ->
                        bitmap?.let { viewModel.saveCapturedBitmap(it) }
                    }
                }
            }
        }
    }

    val isRailVisible = !uiState.hideUiForCapture && !uiState.isTouchLocked

    AzHostActivityLayout(navController = localNavController) {
        if (isRailVisible) {
            azTheme(activeColor = activeHighlightColor, defaultShape = AzButtonShape.RECTANGLE, headerIconShape = AzHeaderIconShape.ROUNDED)
            azConfig(packButtons = true, dockingSide = if (uiState.isRightHanded) AzDockingSide.LEFT else AzDockingSide.RIGHT)
            azAdvanced(isLoading = uiState.isLoading, infoScreen = showInfoScreen, onDismissInfoScreen = { showInfoScreen = false })

            azRailHostItem(id = "mode_host", text = navStrings.modes, onClick = {})
            azRailSubItem(id = "ar", hostId = "mode_host", text = navStrings.arMode, info = navStrings.arModeInfo, onClick = { onModeSelected(AR) })
            azRailSubItem(id = "ghost_mode", hostId = "mode_host", text = navStrings.overlay, info = navStrings.overlayInfo, onClick = { onModeSelected(OVERLAY) })
            azRailSubItem(id = "mockup", hostId = "mode_host", text = navStrings.mockup, info = navStrings.mockupInfo, onClick = { onModeSelected(STATIC) })
            azRailSubItem(id = "trace_mode", hostId = "mode_host", text = navStrings.trace, info = navStrings.traceInfo, onClick = { onModeSelected(TRACE) })

            azDivider()

            if (uiState.editorMode == AR) {
                azRailHostItem(id = "target_host", text = navStrings.grid, onClick = {})
                azRailSubItem(id = "surveyor", hostId = "target_host", text = navStrings.surveyor, info = navStrings.surveyorInfo, onClick = { localNavController.navigate("surveyor"); resetDialogs() })
                azRailSubItem(id = "create_target", hostId = "target_host", text = navStrings.create, info = navStrings.createInfo, onClick = { viewModel.onCreateTargetClicked(); resetDialogs() })
                azRailSubItem(id = "refine_target", hostId = "target_host", text = navStrings.refine, info = navStrings.refineInfo, onClick = { viewModel.onRefineTargetToggled(); resetDialogs() })
                azRailSubItem(id = "mark_progress", hostId = "target_host", text = navStrings.update, info = navStrings.updateInfo, onClick = { viewModel.onMarkProgressToggled(); resetDialogs() })
                azDivider()
            }

            azRailHostItem(id = "design_host", text = navStrings.design, onClick = {})
            val openButtonText = if (uiState.layers.isNotEmpty()) "Add" else navStrings.open
            val openButtonId = if (uiState.layers.isNotEmpty()) "add_layer" else "image"
            azRailSubItem(id = openButtonId, text = openButtonText, hostId = "design_host", info = navStrings.openInfo) { resetDialogs(); overlayImagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }

            uiState.layers.reversed().forEach { layer ->
                azRailRelocItem(
                    id = "layer_${layer.id}", hostId = "design_host", text = layer.name,
                    onClick = { if (uiState.activeLayerId != layer.id) viewModel.onLayerActivated(layer.id) },
                    onRelocate = { _, _, newOrder -> viewModel.onLayerReordered(newOrder.map { it.removePrefix("layer_") }.reversed()) }
                ) {
                    inputItem(hint = "Rename") { viewModel.onLayerRenamed(layer.id, it) }
                    listItem(text = "Duplicate") { viewModel.onLayerDuplicated(layer.id) }
                    listItem(text = "Copy Mods") { viewModel.copyLayerModifications(layer.id) }
                    listItem(text = "Paste Mods") { viewModel.pasteLayerModifications(layer.id) }
                    listItem(text = "Remove") { viewModel.onLayerRemoved(layer.id) }
                }
            }

            if (uiState.editorMode == STATIC) {
                azRailSubItem(id = "background", hostId = "design_host", text = navStrings.wall, info = navStrings.wallInfo) { resetDialogs(); backgroundImagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
            }

            if (uiState.overlayImageUri != null || uiState.layers.isNotEmpty()) {
                azRailSubItem(id = "isolate", hostId = "design_host", text = navStrings.isolate, info = navStrings.isolateInfo, onClick = { viewModel.onRemoveBackgroundClicked(); showSliderDialog = null; showColorBalanceDialog = false; resetDialogs() })
                azRailSubItem(id = "outline", hostId = "design_host", text = navStrings.outline, info = navStrings.outlineInfo, onClick = { viewModel.onLineDrawingClicked(); showSliderDialog = null; showColorBalanceDialog = false; resetDialogs() })
                azDivider()
                azRailSubItem(id = "adjust", hostId = "design_host", text = navStrings.adjust, info = navStrings.adjustInfo) { showSliderDialog = if (showSliderDialog == "Adjust") null else "Adjust"; showColorBalanceDialog = false }
                azRailSubItem(id = "color_balance", hostId = "design_host", text = navStrings.balance, info = navStrings.balanceInfo) { showColorBalanceDialog = true; showSliderDialog = null }
                azRailSubItem(id = "blending", hostId = "design_host", text = navStrings.build, info = navStrings.blendingInfo, onClick = { viewModel.onCycleBlendMode(); showSliderDialog = null; showColorBalanceDialog = false; resetDialogs() })
                azRailSubToggle(id = "lock_image", hostId = "design_host", isChecked = uiState.isImageLocked, toggleOnText = "Locked", toggleOffText = "Unlocked", info = "Prevent accidental moves", onClick = { viewModel.toggleImageLock() })
            }
            azDivider()
            azRailHostItem(id = "project_host", text = navStrings.project, onClick = {})
            azRailSubItem(id = "settings_sub", hostId = "project_host", text = navStrings.settings, info = "App Settings") { localNavController.navigate("settings"); resetDialogs() }
            azRailSubItem(id = "new_project", hostId = "project_host", text = navStrings.new, info = navStrings.newInfo, onClick = { viewModel.onNewProject(); resetDialogs() })
            azRailSubItem(id = "save_project", hostId = "project_host", text = navStrings.save, info = navStrings.saveInfo) { createDocumentLauncher.launch("Project.gxr"); resetDialogs() }
            azRailSubItem(id = "load_project", hostId = "project_host", text = navStrings.load, info = navStrings.loadInfo) { localNavController.navigate("project_library"); resetDialogs() }
            azRailSubItem(id = "export_project", hostId = "project_host", text = navStrings.export, info = navStrings.exportInfo, onClick = { viewModel.onSaveClicked(); resetDialogs() })
            azDivider()
            azRailItem(id = "help", text = "Help", info = "Show Help") { showInfoScreen = true; resetDialogs() }
            if (uiState.editorMode == AR || uiState.editorMode == OVERLAY) azRailItem(id = "light", text = navStrings.light, info = navStrings.lightInfo, onClick = { viewModel.onToggleFlashlight(); resetDialogs() })
            if (uiState.editorMode == TRACE) azRailItem(id = "lock_trace", text = navStrings.lock, info = navStrings.lockInfo, onClick = { viewModel.setTouchLocked(true); resetDialogs() })
        }

        background(weight = 0) {
            if (currentNavRoute == "editor" || currentNavRoute == null) {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                    MainContentLayer(uiState, viewModel, gestureInProgress, onRendererCreated) { gestureInProgress = it }
                }
            } else {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black))
            }
        }

        onscreen(alignment = Alignment.Center) {
            Box(modifier = Modifier.fillMaxSize()) {
                AzNavHost(startDestination = "editor") {
                    composable("editor") {
                        EditorUi(
                            viewModel,
                            uiState,
                            showSliderDialog,
                            showColorBalanceDialog,
                            gestureInProgress
                        )
                    }
                    composable("surveyor") {
                        MappingScreen(
                            onMapSaved = { /* Optional surveyor save callback */ },
                            onExit = { localNavController.popBackStack() },
                            onRendererCreated = { /* Internal renderer handling */ }
                        )
                    }
                    composable("project_library") {
                        LaunchedEffect(Unit) { viewModel.loadAvailableProjects(context) }
                        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                            ProjectLibraryScreen(uiState.availableProjects, { viewModel.openProject(it, context); localNavController.popBackStack() }, { viewModel.deleteProject(context, it) }, { viewModel.onNewProject(); localNavController.popBackStack() })
                            com.hereliesaz.aznavrail.AzButton(text = "Back", onClick = { localNavController.popBackStack() }, modifier = Modifier.align(Alignment.TopStart).padding(16.dp))
                        }
                    }
                    composable("settings") {
                        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                            SettingsScreen(BuildConfig.VERSION_NAME, uiState.updateStatusMessage, uiState.isCheckingForUpdate, uiState.isRightHanded, viewModel::setHandedness, viewModel::checkForUpdates, viewModel::installLatestUpdate, { localNavController.popBackStack() })
                        }
                    }
                }
                TouchLockOverlay(uiState.isTouchLocked, viewModel::showUnlockInstructions)
                UnlockInstructionsPopup(uiState.showUnlockInstructions)
                if (uiState.isCapturingTarget) Box(modifier = Modifier.fillMaxSize().zIndex(20f)) { TargetCreationFlow(uiState, viewModel, context) }
                if (uiState.isCapturingTarget) CaptureAnimation()
            }
        }
    }
}

@Composable
private fun MainContentLayer(uiState: UiState, viewModel: MainViewModel, gestureInProgress: Boolean, onRendererCreated: (ArRenderer) -> Unit, onGestureToggle: (Boolean) -> Unit) {
    Box(Modifier.fillMaxSize().zIndex(1f), contentAlignment = Alignment.Center) {
        val onScale: (Float) -> Unit = viewModel::onScaleChanged
        val onOffset: (Offset) -> Unit = viewModel::onOffsetChanged
        val onRotZ: (Float) -> Unit = viewModel::onRotationZChanged
        val onCycle: () -> Unit = viewModel::onCycleRotationAxis
        val onStart: () -> Unit = { viewModel.onGestureStart(); onGestureToggle(true) }
        val onEnd: () -> Unit = { viewModel.onGestureEnd(); onGestureToggle(false) }
        val onOverlayGestureEnd: (Float, Offset, Float, Float, Float) -> Unit = { s, o, rx, ry, rz ->
            viewModel.setLayerTransform(s, o, rx, ry, rz)
            viewModel.onGestureEnd()
            onGestureToggle(false)
        }

        when (uiState.editorMode) {
            STATIC -> MockupScreen(
                uiState = uiState,
                onBackgroundImageSelected = viewModel::onBackgroundImageSelected,
                onOverlayImageSelected = viewModel::onOverlayImageSelected,
                onOpacityChanged = viewModel::onOpacityChanged,
                onBrightnessChanged = viewModel::onBrightnessChanged,
                onContrastChanged = viewModel::onContrastChanged,
                onSaturationChanged = viewModel::onSaturationChanged,
                onCycleRotationAxis = onCycle,
                onGestureStart = onStart,
                onGestureEnd = onOverlayGestureEnd
            )
            TRACE -> TraceScreen(
                uiState = uiState,
                onOverlayImageSelected = viewModel::onOverlayImageSelected,
                onCycleRotationAxis = onCycle,
                onGestureStart = onStart,
                onGestureEnd = onOverlayGestureEnd
            )
            OVERLAY -> OverlayScreen(uiState, onCycle, onStart, onOverlayGestureEnd)
            AR -> {
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
                        .pointerInput(Unit) {
                            forEachGesture {
                                awaitPointerEventScope {
                                    awaitFirstDown(requireUnconsumed = false)
                                    val up = waitForUpOrCancellation()
                                    if (up != null) onEnd()
                                }
                            }
                        }
                ) {
                    ArView(viewModel, uiState, onRendererCreated)
                }
            }
            CROP, ADJUST, DRAW, ISOLATE, BALANCE, OUTLINE -> OverlayScreen(uiState, onCycle, onStart, onOverlayGestureEnd)
            PROJECT -> Box(Modifier.fillMaxSize().background(Color.Black))
        }
    }
}

@Composable
private fun TargetCreationFlow(uiState: UiState, viewModel: MainViewModel, context: Context) {
    Box(Modifier.fillMaxSize()) {
        if (uiState.captureStep == CaptureStep.REVIEW) {
            val uri = uiState.capturedTargetUris.firstOrNull()
            val imageBitmap by produceState<Bitmap?>(null, uri) { uri?.let { value = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, it)) else @Suppress("DEPRECATION") android.provider.MediaStore.Images.Media.getBitmap(context.contentResolver, uri) } }
            val maskBitmap by produceState<Bitmap?>(null, uiState.targetMaskUri) { value = if (uiState.targetMaskUri != null) withContext(Dispatchers.IO) { com.hereliesaz.graffitixr.utils.ImageUtils.loadBitmapFromUri(context, uiState.targetMaskUri) } else null }
            TargetRefinementScreen(imageBitmap, maskBitmap, uiState.detectedKeypoints, uiState.refinementPaths, uiState.isRefinementEraser, uiState.canUndo, uiState.canRedo, viewModel::onRefinementPathAdded, { viewModel.onRefinementModeChanged(!it) }, viewModel::onUndoClicked, viewModel::onRedoClicked) { viewModel.onConfirmTargetCreation() }
        } else if (uiState.captureStep == CaptureStep.RECTIFY) {
            val uri = uiState.capturedTargetUris.firstOrNull()
            val imageBitmap by produceState<Bitmap?>(null, uri, uiState.capturedTargetImages) { value = if (uiState.capturedTargetImages.isNotEmpty()) uiState.capturedTargetImages.first() else if (uri != null) { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) else @Suppress("DEPRECATION") android.provider.MediaStore.Images.Media.getBitmap(context.contentResolver, uri) } else null }
            UnwarpScreen(uiState.isRightHanded, imageBitmap, viewModel::unwarpImage, viewModel::onRetakeCapture)
        } else {
            TargetCreationOverlay(
                uiState.isRightHanded, uiState.captureStep, uiState.targetCreationMode, uiState.gridRows, uiState.gridCols, uiState.qualityWarning, uiState.captureFailureTimestamp,
                { if (uiState.captureStep.name.startsWith("CALIBRATION_POINT")) viewModel.onCalibrationPointCaptured() else viewModel.onCaptureShutterClicked() },
                viewModel::onCancelCaptureClicked, viewModel::onTargetCreationMethodSelected, viewModel::onGridConfigChanged, viewModel::onGpsDecision, viewModel::onPhotoSequenceFinished
            )
        }
    }
}

@Composable
private fun TouchLockOverlay(isLocked: Boolean, onUnlockRequested: () -> Unit) {
    if (!isLocked) return
    Box(
        Modifier
            .fillMaxSize()
            .zIndex(100f)
            .background(Color.Transparent)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    var tapCount = 0
                    var lastTapTime = 0L
                    while (true) {
                        val change = awaitPointerEvent(PointerEventPass.Main).changes.firstOrNull()
                        if (change != null && change.changedToUp()) {
                            val now = System.currentTimeMillis()
                            if (now - lastTapTime < 500) tapCount++ else tapCount = 1
                            lastTapTime = now
                            if (tapCount == 4) {
                                onUnlockRequested()
                                tapCount = 0
                            }
                        }
                        awaitPointerEvent(PointerEventPass.Main).changes.forEach { it.consume() }
                    }
                }
            }
    )
}

@Composable
fun StatusOverlay(qualityWarning: String?, arState: ArState, isPlanesDetected: Boolean, isTargetCreated: Boolean, modifier: Modifier) {
    AnimatedVisibility(true, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        val bg = if (qualityWarning != null) Color.Red.copy(0.8f) else Color.Black.copy(0.5f)
        val txt = when {
            qualityWarning != null -> qualityWarning
            !isTargetCreated -> "Create a Grid to start."
            arState == ArState.SEARCHING && !isPlanesDetected -> "Scan surfaces around you."
            arState == ArState.SEARCHING && isPlanesDetected -> "Tap a surface to place anchor."
            arState == ArState.LOCKED -> "Looking for your Grid..."
            arState == ArState.PLACED -> "Ready."
            else -> ""
        }
        if (txt.isNotEmpty()) {
            Box(Modifier.background(bg, RoundedCornerShape(8.dp)).padding(16.dp, 8.dp)) {
                Text(txt, color = Color.White, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun CaptureAnimation() {
    var f by remember { mutableFloatStateOf(0f) }
    var s by remember { mutableFloatStateOf(0f) }
    val af by animateFloatAsState(f, tween(200))
    val `as` by animateFloatAsState(s, tween(300))

    LaunchedEffect(Unit) {
        s = 0.5f
        delay(100)
        f = 1f
        delay(50)
        f = 0f
        delay(150)
        s = 0f
    }

    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = `as`)).zIndex(10f))
    Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = af)).zIndex(11f))
}

@Composable
fun UnlockInstructionsPopup(visible: Boolean) {
    AnimatedVisibility(visible, enter = fadeIn() + slideInVertically { it / 2 }, exit = fadeOut() + slideOutVertically { it / 2 }, modifier = Modifier.fillMaxSize().zIndex(200f)) {
        Box(Modifier.fillMaxSize().padding(bottom = 120.dp), contentAlignment = Alignment.BottomCenter) {
            Box(Modifier.background(Color.Black.copy(0.8f), RoundedCornerShape(16.dp)).padding(24.dp, 16.dp)) {
                Text("Press Volume Up & Down to unlock", color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            }
        }
    }
}