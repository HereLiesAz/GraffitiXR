package com.hereliesaz.graffitixr

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.hereliesaz.aznavrail.AzNavRail
import com.hereliesaz.aznavrail.model.AzButtonShape
import com.hereliesaz.graffitixr.composables.AdjustmentsKnobsRow
import com.hereliesaz.graffitixr.composables.ColorBalanceKnobsRow
import com.hereliesaz.graffitixr.composables.DrawingCanvas
import com.hereliesaz.graffitixr.composables.GestureFeedback
import com.hereliesaz.graffitixr.composables.GhostScreen
import com.hereliesaz.graffitixr.composables.HelpScreen
import com.hereliesaz.graffitixr.composables.MockupScreen
import com.hereliesaz.graffitixr.composables.ProjectLibraryScreen
import com.hereliesaz.graffitixr.composables.RotationAxisFeedback
import com.hereliesaz.graffitixr.composables.SettingsScreen
import com.hereliesaz.graffitixr.composables.TapFeedbackEffect
import com.hereliesaz.graffitixr.composables.TargetCreationOverlay
import com.hereliesaz.graffitixr.composables.TargetRefinementScreen
import com.hereliesaz.graffitixr.composables.TraceScreen
import com.hereliesaz.graffitixr.composables.UndoRedoRow
import com.hereliesaz.graffitixr.dialogs.DoubleTapHintDialog
import com.hereliesaz.graffitixr.dialogs.OnboardingDialog
import com.hereliesaz.graffitixr.dialogs.SaveProjectDialog
import com.hereliesaz.graffitixr.utils.captureWindow
import kotlinx.coroutines.delay

@Composable
fun MainScreen(viewModel: MainViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val tapFeedback by viewModel.tapFeedback.collectAsState()
    val context = LocalContext.current
    var showSliderDialog by remember { mutableStateOf<String?>(null) }
    var showColorBalanceDialog by remember { mutableStateOf(false) }
    var showProjectLibrary by remember { mutableStateOf(false) }
    var showSaveProjectDialog by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var gestureInProgress by remember { mutableStateOf(false) }

    // Vibration Logic
    LaunchedEffect(viewModel, context) {
        viewModel.feedbackEvent.collect { event ->
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }

            if (vibrator.hasVibrator()) {
                when (event) {
                    is FeedbackEvent.VibrateSingle -> {
                        vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
                    }
                    is FeedbackEvent.VibrateDouble -> {
                        val timing = longArrayOf(0, 50, 50, 50)
                        val amplitude = intArrayOf(0, 255, 0, 255)
                        vibrator.vibrate(VibrationEffect.createWaveform(timing, amplitude, -1))
                    }
                }
            }
        }
    }

    LaunchedEffect(viewModel, context) {
        viewModel.captureEvent.collect { event ->
            when (event) {
                is CaptureEvent.RequestCapture -> {
                    (context as? Activity)?.let { activity ->
                        captureWindow(activity) { bitmap ->
                            bitmap?.let {
                                viewModel.saveCapturedBitmap(it)
                            }
                        }
                    }
                }
            }
        }
    }

    val overlayImagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let { viewModel.onOverlayImageSelected(it) } }

    val backgroundImagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let { viewModel.onBackgroundImageSelected(it) } }

    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri -> uri?.let { viewModel.exportProjectToUri(it) } }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val screenHeight = maxHeight
        val verticalMargin = screenHeight * 0.1f // 10% Margin

        if (showProjectLibrary) {
            ProjectLibraryScreen(
                projects = viewModel.getProjectList(),
                onLoadProject = { projectName ->
                    viewModel.loadProject(projectName)
                    showProjectLibrary = false
                },
                onDeleteProject = { projectName ->
                    viewModel.deleteProject(projectName)
                },
                onNewProject = {
                    viewModel.onNewProject()
                    showProjectLibrary = false
                }
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(1f),
                contentAlignment = Alignment.Center
            ) {
                when (uiState.editorMode) {
                    EditorMode.HELP -> HelpScreen(onGetStarted = { viewModel.onEditorModeChanged(EditorMode.STATIC) })
                    EditorMode.STATIC -> MockupScreen(
                        uiState = uiState,
                        onBackgroundImageSelected = viewModel::onBackgroundImageSelected,
                        onOverlayImageSelected = viewModel::onOverlayImageSelected,
                        onOpacityChanged = viewModel::onOpacityChanged,
                        onBrightnessChanged = viewModel::onBrightnessChanged,
                        onContrastChanged = viewModel::onContrastChanged,
                        onSaturationChanged = viewModel::onSaturationChanged,
                        onScaleChanged = viewModel::onScaleChanged,
                        onOffsetChanged = viewModel::onOffsetChanged,
                        onRotationZChanged = viewModel::onRotationZChanged,
                        onRotationXChanged = viewModel::onRotationXChanged,
                        onRotationYChanged = viewModel::onRotationYChanged,
                        onCycleRotationAxis = viewModel::onCycleRotationAxis,
                        onGestureStart = {
                            viewModel.onGestureStart()
                            gestureInProgress = true
                        },
                        onGestureEnd = {
                            viewModel.onGestureEnd()
                            gestureInProgress = false
                        }
                    )
                    EditorMode.TRACE -> TraceScreen(
                        uiState = uiState,
                        onOverlayImageSelected = viewModel::onOverlayImageSelected,
                        onScaleChanged = viewModel::onScaleChanged,
                        onOffsetChanged = viewModel::onOffsetChanged,
                        onRotationZChanged = viewModel::onRotationZChanged,
                        onRotationXChanged = viewModel::onRotationXChanged,
                        onRotationYChanged = viewModel::onRotationYChanged,
                        onCycleRotationAxis = viewModel::onCycleRotationAxis,
                        onGestureStart = {
                            viewModel.onGestureStart()
                            gestureInProgress = true
                        },
                        onGestureEnd = {
                            viewModel.onGestureEnd()
                            gestureInProgress = false
                        }
                    )
                    EditorMode.GHOST -> GhostScreen(
                        uiState = uiState,
                        onScaleChanged = viewModel::onScaleChanged,
                        onOffsetChanged = viewModel::onOffsetChanged,
                        onRotationZChanged = viewModel::onRotationZChanged,
                        onRotationXChanged = viewModel::onRotationXChanged,
                        onRotationYChanged = viewModel::onRotationYChanged,
                        onCycleRotationAxis = viewModel::onCycleRotationAxis,
                        onGestureStart = {
                            viewModel.onGestureStart()
                            gestureInProgress = true
                        },
                        onGestureEnd = {
                            viewModel.onGestureEnd()
                            gestureInProgress = false
                        }
                    )
                    EditorMode.AR -> {
                        ArView(
                            viewModel = viewModel,
                            uiState = uiState
                        )
                    }
                }
            }
        }

        if (showSettings) {
            Box(modifier = Modifier.zIndex(1.5f)) {
                SettingsScreen(
                    currentVersion = BuildConfig.VERSION_NAME,
                    updateStatus = uiState.updateStatusMessage,
                    isCheckingForUpdate = uiState.isCheckingForUpdate,
                    onCheckForUpdates = viewModel::checkForUpdates,
                    onInstallUpdate = viewModel::installLatestUpdate,
                    onClose = { showSettings = false }
                )
            }
        }

        // New Overlay for Target Creation
        if (uiState.isCapturingTarget) {
            Box(modifier = Modifier.zIndex(5f)) {
                if (uiState.captureStep == CaptureStep.REVIEW) {
                    TargetRefinementScreen(
                        targetImage = uiState.capturedTargetImages.firstOrNull(),
                        keypoints = uiState.detectedKeypoints,
                        paths = uiState.refinementPaths,
                        isEraser = uiState.isRefinementEraser,
                        onPathAdded = viewModel::onRefinementPathAdded,
                        onModeChanged = viewModel::onRefinementModeChanged,
                        onConfirm = viewModel::onConfirmTargetCreation
                    )
                } else {
                    TargetCreationOverlay(
                        step = uiState.captureStep,
                        qualityWarning = uiState.qualityWarning,
                        captureFailureTimestamp = uiState.captureFailureTimestamp,
                        onCaptureClick = viewModel::onCaptureShutterClicked,
                        onCancelClick = viewModel::onCancelCaptureClicked
                    )
                }
            }
        }

        GestureFeedback(
            uiState = uiState,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = verticalMargin + 16.dp)
                .zIndex(3f),
            isVisible = gestureInProgress
        )

        // Hide toolbar if capturing target
        if (!uiState.isTouchLocked && !uiState.isCapturingTarget) {
            Box(
                modifier = Modifier
                    .zIndex(2f)
                    .padding(vertical = verticalMargin) // Apply 10% Margin to NavRail
                    .fillMaxHeight()
            ) {
                AzNavRail {
                    azSettings(
                        isLoading = uiState.isLoading,
                        packRailButtons = true,
                        defaultShape = AzButtonShape.RECTANGLE,
                    )

                    azRailHostItem(id = "mode_host", text = "Modes", route = "mode_host")
                    azRailSubItem(id = "ar", hostId = "mode_host", text = "AR Mode", onClick = { viewModel.onEditorModeChanged(EditorMode.AR) })
                    azRailSubItem(id = "ghost_mode", hostId = "mode_host", text = "Ghost", onClick = { viewModel.onEditorModeChanged(EditorMode.GHOST) })
                    azRailSubItem(id = "mockup", hostId = "mode_host", text = "Mockup", onClick = { viewModel.onEditorModeChanged(EditorMode.STATIC) })
                    azRailSubItem(id = "trace_mode", hostId = "mode_host", text = "Trace", onClick = { viewModel.onEditorModeChanged(EditorMode.TRACE) })

                    azDivider()

                    if (uiState.editorMode == EditorMode.TRACE) {
                        azRailItem(id = "lock_trace", text = "Lock", onClick = { viewModel.setTouchLocked(true) })
                    }

                    // Target Host
                    azRailHostItem(id = "target_host", text = "Target", route = "target_host")
                    if (uiState.editorMode == EditorMode.AR) {
                        azRailSubItem(id = "create_target", hostId = "target_host", text = "Create", onClick = viewModel::onCreateTargetClicked)
                    }
                    azRailSubItem(id = "refine_target", hostId = "target_host", text = "Refine", onClick = viewModel::onRefineTargetToggled)
                    azRailSubItem(id = "mark_progress", hostId = "target_host", text = "Update", onClick = viewModel::onMarkProgressToggled)

                    azDivider()

                    // Image Host (now includes Adjustments)
                    azRailHostItem(id = "overlay", text = "Overlay", route = "overlay") {}
                    if (uiState.editorMode == EditorMode.STATIC) {
                        azRailSubItem(id = "background", hostId = "overlay", text = "Background") {
                            backgroundImagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }
                    }
                    azRailSubItem(id = "image", text = "Image", hostId = "overlay") {
                        overlayImagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                    if (uiState.overlayImageUri != null) {
                        azRailSubItem(id = "remove_bg", hostId = "overlay", text = "Remove\n Background", onClick = viewModel::onRemoveBackgroundClicked)
                        azRailSubItem(id = "line_drawing", hostId = "overlay", text = "Outline", onClick = viewModel::onLineDrawingClicked)
                        azDivider()

                        // Moved Adjustment Items
                        azRailSubItem(id = "opacity", hostId = "overlay", text = "Opacity") { showSliderDialog = "Opacity" }
                        azRailSubItem(id = "brightness", hostId = "overlay", text = "Brightness") { showSliderDialog = "Brightness" }
                        azRailSubItem(id = "contrast", hostId = "overlay", text = "Contrast") { showSliderDialog = "Contrast" }
                        azRailSubItem(id = "saturation", hostId = "overlay", text = "Saturation") { showSliderDialog = "Saturation" }
                        azRailSubItem(id = "color_balance", hostId = "overlay", text = "Balance") { showColorBalanceDialog = !showColorBalanceDialog }
                        azRailSubItem(id = "blend_mode", hostId = "overlay", text = "Blend", onClick = viewModel::onCycleBlendMode)
                    }

                    azDivider()

                    // Settings Host (Moved Project items here)
                    azRailHostItem(id = "settings_host", text = "Settings", route = "settings_host"){ showSettings = true }
                    azRailSubItem(id = "new_project", hostId = "settings_host", text = "New", onClick = viewModel::onNewProject)
                    azRailSubItem(id = "save_project", hostId = "settings_host", text = "Save") { createDocumentLauncher.launch("Project.gxr") }
                    azRailSubItem(id = "load_project", hostId = "settings_host", text = "Load") { showProjectLibrary = true }
                    azRailSubItem(id = "export_project", hostId = "settings_host", text = "Export", onClick = viewModel::onSaveClicked)
                }
            }
        }

        if (uiState.isTouchLocked) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(100f)
                    .background(Color.Transparent)
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent(pass = PointerEventPass.Initial)
                                val event = awaitPointerEvent()
                                event.changes.forEach { it.consume() }
                            }
                        }
                    }
            )
        }

        if (uiState.isMarkingProgress) {
            DrawingCanvas(
                paths = uiState.drawingPaths,
                onPathFinished = viewModel::onDrawingPathFinished
            )
        }

        if (showSaveProjectDialog) {
            SaveProjectDialog(
                onDismissRequest = { showSaveProjectDialog = false },
                onSaveRequest = { projectName ->
                    viewModel.saveProject(projectName)
                    showSaveProjectDialog = false
                }
            )
        }

        if (showSettings) {
            SettingsScreen(
                currentVersion = BuildConfig.VERSION_NAME,
                updateStatus = uiState.updateStatusMessage,
                isCheckingForUpdate = uiState.isCheckingForUpdate,
                onCheckForUpdates = viewModel::checkForUpdates,
                onInstallUpdate = viewModel::installLatestUpdate,
                onClose = { showSettings = false }
            )
        }

        // Adjustments Panel (Knobs and Undo/Redo)
        if (uiState.overlayImageUri != null) {
            val showKnobs = showSliderDialog == "Opacity" || showSliderDialog == "Brightness" || showSliderDialog == "Contrast" || showSliderDialog == "Saturation"

            // Undo/Redo Buttons (15% from bottom)
            UndoRedoRow(
                canUndo = uiState.canUndo,
                canRedo = uiState.canRedo,
                onUndo = viewModel::onUndoClicked,
                onRedo = viewModel::onRedoClicked,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = screenHeight * 0.15f)
                    .zIndex(3f)
            )

            // Adjustment Knobs (25% from bottom)
            if (showKnobs) {
                AdjustmentsKnobsRow(
                    opacity = uiState.opacity,
                    brightness = uiState.brightness,
                    contrast = uiState.contrast,
                    saturation = uiState.saturation,
                    onOpacityChange = viewModel::onOpacityChanged,
                    onBrightnessChange = viewModel::onBrightnessChanged,
                    onContrastChange = viewModel::onContrastChanged,
                    onSaturationChange = viewModel::onSaturationChanged,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = screenHeight * 0.25f)
                        .zIndex(3f)
                )
            }

            // Color Balance Knobs (40% from bottom)
            if (showColorBalanceDialog) {
                ColorBalanceKnobsRow(
                    colorBalanceR = uiState.colorBalanceR,
                    colorBalanceG = uiState.colorBalanceG,
                    colorBalanceB = uiState.colorBalanceB,
                    onColorBalanceRChange = viewModel::onColorBalanceRChanged,
                    onColorBalanceGChange = viewModel::onColorBalanceGChanged,
                    onColorBalanceBChange = viewModel::onColorBalanceBChanged,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = screenHeight * 0.40f)
                        .zIndex(3f)
                )
            }
        }

        uiState.showOnboardingDialogForMode?.let { mode ->
            OnboardingDialog(
                editorMode = mode,
                onDismissRequest = { dontShowAgain ->
                    viewModel.onOnboardingComplete(mode, dontShowAgain)
                }
            )
        }

        RotationAxisFeedback(
            axis = uiState.activeRotationAxis,
            visible = uiState.showRotationAxisFeedback,
            onFeedbackShown = viewModel::onFeedbackShown,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = verticalMargin + 32.dp) // Margin + Padding
                .zIndex(4f)
        )

        TapFeedbackEffect(feedback = tapFeedback)

        if (uiState.showDoubleTapHint) {
            DoubleTapHintDialog(onDismissRequest = viewModel::onDoubleTapHintDismissed)
        }

        if (uiState.isMarkingProgress) {
            Text(
                text = "Progress: %.2f%%".format(uiState.progressPercentage),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = verticalMargin + 16.dp) // Margin + Padding
                    .zIndex(3f)
            )
        }

        if (uiState.isCapturingTarget) {
            CaptureAnimation()
        }
    }
}

@Composable
private fun CaptureAnimation() {
    var flashAlpha by remember { mutableFloatStateOf(0f) }
    var shutterAlpha by remember { mutableFloatStateOf(0f) }

    val animatedFlashAlpha by animateFloatAsState(
        targetValue = flashAlpha,
        animationSpec = tween(durationMillis = 200),
        label = "Flash Animation"
    )
    val animatedShutterAlpha by animateFloatAsState(
        targetValue = shutterAlpha,
        animationSpec = tween(durationMillis = 300),
        label = "Shutter Animation"
    )

    LaunchedEffect(Unit) {
        // Shutter closes
        shutterAlpha = 0.5f
        delay(100)
        // Flash
        flashAlpha = 1f
        delay(50)
        flashAlpha = 0f
        // Shutter opens
        delay(150)
        shutterAlpha = 0f
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = animatedShutterAlpha))
            .zIndex(10f) // Make sure it's on top
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White.copy(alpha = animatedFlashAlpha))
            .zIndex(11f) // Flash on top of shutter
    )
}