package com.hereliesaz.graffitixr

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.hereliesaz.aznavrail.AzNavRail
import com.hereliesaz.graffitixr.composables.DrawingCanvas
import com.hereliesaz.graffitixr.composables.GestureFeedback
import com.hereliesaz.graffitixr.composables.GhostScreen
import com.hereliesaz.graffitixr.composables.HelpScreen
import com.hereliesaz.graffitixr.composables.MockupScreen
import com.hereliesaz.graffitixr.composables.ProjectLibraryScreen
import com.hereliesaz.graffitixr.composables.TraceScreen
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import com.hereliesaz.graffitixr.composables.RotationAxisFeedback
import com.hereliesaz.graffitixr.composables.TapFeedbackEffect
import com.hereliesaz.graffitixr.dialogs.AdjustmentSliderDialog
import com.hereliesaz.graffitixr.dialogs.ColorBalanceDialog
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
    var gestureInProgress by remember { mutableStateOf(false) }

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

    Box(modifier = Modifier.fillMaxSize()) {
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

        GestureFeedback(
            uiState = uiState,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 16.dp)
                .zIndex(3f),
            isVisible = gestureInProgress
        )

        if (!uiState.isTouchLocked) {
            Box(modifier = Modifier.zIndex(2f)) {
                AzNavRail {
                    azSettings(isLoading = uiState.isLoading,
                        packRailButtons = true
                    )

                    azMenuHostItem(id = "mode_host", text = "Mode", route = "mode_host")
                    azMenuSubItem(id = "ar", hostId = "mode_host", text = "AR Mode", onClick = { viewModel.onEditorModeChanged(EditorMode.AR) })
                    azMenuSubItem(id = "ghost_mode", hostId = "mode_host", text = "Ghost", onClick = { viewModel.onEditorModeChanged(EditorMode.GHOST) })
                    azMenuSubItem(id = "trace_mode", hostId = "mode_host", text = "Trace", onClick = { viewModel.onEditorModeChanged(EditorMode.TRACE) })
                    azMenuSubItem(id = "mockup", hostId = "mode_host", text = "Mockup", onClick = { viewModel.onEditorModeChanged(EditorMode.STATIC) })

                    azRailHostItem(id = "project_host", text = "Project", route = "project_host")
                    azRailSubItem(id = "save_project", hostId = "project_host", text = "Save") {
                        showSaveProjectDialog = true
                    }
                    azRailSubItem(id = "project_library", hostId = "project_host", text = "Library") {
                        showProjectLibrary = true
                    }
                    azRailSubItem(id = "export", hostId = "project_host", text = "Export", onClick = viewModel::onSaveClicked)

                    azDivider()

                    if (uiState.editorMode == EditorMode.AR) {
                        azRailItem(id = "create_target", text = "Create Target", onClick = viewModel::onCreateTargetClicked)
                    }

                    if (uiState.editorMode == EditorMode.TRACE) {
                        azRailItem(id = "lock_trace", text = "Lock", onClick = { viewModel.setTouchLocked(true) })
                    }

                    azRailHostItem(id = "image_host", text = "Image", route = "image_host")
                if (uiState.editorMode == EditorMode.STATIC) {
                    azRailSubItem(id = "background", hostId = "image_host", text = "Background") {
                        backgroundImagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                }
                azRailSubItem(id = "overlay", hostId = "image_host", text = "Image") {
                    overlayImagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }

                if (uiState.overlayImageUri != null) {
                    azRailSubItem(id = "remove_bg", hostId = "image_host", text = "Remove\n Background", onClick = viewModel::onRemoveBackgroundClicked)
                    azRailSubItem(id = "line_drawing", hostId = "image_host", text = "Outline", onClick = viewModel::onLineDrawingClicked)
                }

                azDivider()

                azRailHostItem(id = "adjustments_host", text = "Adjust", route = "adjustments_host")
                azRailSubItem(id = "opacity", hostId = "adjustments_host", text = "Opacity") { showSliderDialog = "Opacity" }
                azRailSubItem(id = "contrast", hostId = "adjustments_host", text = "Contrast") { showSliderDialog = "Contrast" }
                azRailSubItem(id = "saturation", hostId = "adjustments_host", text = "Saturation") { showSliderDialog = "Saturation" }
                azRailSubItem(id = "color_balance", hostId = "adjustments_host", text = "Balance") { showColorBalanceDialog = true }
                azRailSubItem(id = "blend_mode", hostId = "adjustments_host", text = "Blend Mode", onClick = viewModel::onCycleBlendMode)

                azDivider()

                azRailItem(id = "mark_progress", text = "Mark Progress", onClick = viewModel::onMarkProgressToggled)
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

        if (showColorBalanceDialog) {
            ColorBalanceDialog(
                title = "Color Balance",
                valueR = uiState.colorBalanceR,
                valueG = uiState.colorBalanceG,
                valueB = uiState.colorBalanceB,
                onValueRChange = viewModel::onColorBalanceRChanged,
                onValueGChange = viewModel::onColorBalanceGChanged,
                onValueBChange = viewModel::onColorBalanceBChanged,
                onDismissRequest = { showColorBalanceDialog = false }
            )
        }

        when (showSliderDialog) {
            "Opacity" -> AdjustmentSliderDialog(
                title = "Opacity",
                value = uiState.opacity,
                onValueChange = viewModel::onOpacityChanged,
                onDismissRequest = { showSliderDialog = null }
            )
            "Contrast" -> AdjustmentSliderDialog(
                title = "Contrast",
                value = uiState.contrast,
                onValueChange = viewModel::onContrastChanged,
                onDismissRequest = { showSliderDialog = null },
                valueRange = 0f..2f
            )
            "Saturation" -> AdjustmentSliderDialog(
                title = "Saturation",
                value = uiState.saturation,
                onValueChange = viewModel::onSaturationChanged,
                onDismissRequest = { showSliderDialog = null },
                valueRange = 0f..2f
            )
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
                .padding(bottom = 32.dp)
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
                    .padding(top = 16.dp)
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