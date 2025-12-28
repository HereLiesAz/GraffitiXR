package com.hereliesaz.graffitixr

import android.app.Activity
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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import com.hereliesaz.aznavrail.AzNavRail
import com.hereliesaz.aznavrail.model.AzButtonShape
import com.hereliesaz.graffitixr.composables.AdjustmentsKnobsRow
import com.hereliesaz.graffitixr.composables.ColorBalanceKnobsRow
import com.hereliesaz.graffitixr.composables.DrawingCanvas
import com.hereliesaz.graffitixr.composables.GestureFeedback
import com.hereliesaz.graffitixr.composables.HelpOverlay
import com.hereliesaz.graffitixr.composables.MockupScreen
import com.hereliesaz.graffitixr.composables.OverlayScreen
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The top-level UI composable for the GraffitiXR application.
 */
@Composable
fun MainScreen(viewModel: MainViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val tapFeedback by viewModel.tapFeedback.collectAsState()
    val context = LocalContext.current

    // UI Visibility States
    var showSliderDialog by remember { mutableStateOf<String?>(null) }
    var showColorBalanceDialog by remember { mutableStateOf(false) }
    var showProjectLibrary by remember { mutableStateOf(false) }
    var showSaveProjectDialog by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var gestureInProgress by remember { mutableStateOf(false) }

    // Automation State
    var hasSelectedModeOnce by remember { mutableStateOf(false) }

    // Dynamic Rail Position State for Help Overlay
    var railTop by remember { mutableFloatStateOf(0f) }
    var railItemPositions by remember { mutableStateOf<Map<String, Rect>>(emptyMap()) }
    val onProbePosition: (String, Rect) -> Unit = { id, rect ->
        railItemPositions = railItemPositions + (id to rect)
    }

    // Fallback mechanism if dynamic anchoring fails (Help Overlay)
    LaunchedEffect(railTop, uiState.editorMode) {
        if (uiState.editorMode == EditorMode.HELP) {
            // Give a small delay for the layout pass
            delay(100)
            if (railItemPositions.isEmpty() && railTop > 0f) {
                // Calculate fallback positions based on RailConstants
                val density = context.resources.displayMetrics.density
                val widthPx = 80f * density // RailConstants.Width
                val headerPx = 110f * density // RailConstants.HeaderHeight
                val itemPx = 65f * density // RailConstants.ItemHeight

                // Assuming items: Modes, Design, Settings
                val modesRect = Rect(0f, railTop + headerPx, widthPx, railTop + headerPx + itemPx)
                val designRect = Rect(0f, railTop + headerPx + itemPx, widthPx, railTop + headerPx + (itemPx * 2))
                val settingsRect = Rect(0f, railTop + headerPx + (itemPx * 2), widthPx, railTop + headerPx + (itemPx * 3))

                railItemPositions = mapOf(
                    "mode_host" to modesRect,
                    "design_host" to designRect,
                    "settings_host" to settingsRect
                )
            }
        }
    }

    // Haptic Feedback Handler
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

    // Capture Event Handler (PixelCopy)
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

    // Launchers
    val overlayImagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let { viewModel.onOverlayImageSelected(it) } }

    val backgroundImagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let { viewModel.onBackgroundImageSelected(it) } }

    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri -> uri?.let { viewModel.exportProjectToUri(it) } }

    // Image Picker Request Handler
    LaunchedEffect(viewModel) {
        viewModel.requestImagePicker.collect {
            overlayImagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
    }

    // Helper for automation
    fun onModeSelected(mode: EditorMode) {
        viewModel.onEditorModeChanged(mode)

        // Hide adjustment knobs when switching modes/tools
        showSliderDialog = null
        showColorBalanceDialog = false

        if (!hasSelectedModeOnce) {
            hasSelectedModeOnce = true
            // If AR, auto-launch target capture (which now starts with INSTRUCTION step)
            if (mode == EditorMode.AR) {
                viewModel.onCreateTargetClicked()
            }
        }
    }

    LaunchedEffect(uiState.editorMode) {
        if (uiState.editorMode == EditorMode.TRACE && uiState.overlayImageUri == null) {
            overlayImagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color.Black
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            val screenHeight = maxHeight

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
                // Main Content Area (Z-Index 1)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(1f),
                    contentAlignment = Alignment.Center
                ) {
                    when (uiState.editorMode) {
                        EditorMode.HELP -> { /* Rendered as overlay */ }
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
                        EditorMode.OVERLAY -> OverlayScreen(
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

            // Status Overlay (AR Debug/Messages)
            if (uiState.editorMode == EditorMode.AR && !uiState.isCapturingTarget && !uiState.hideUiForCapture) {
                StatusOverlay(
                    qualityWarning = uiState.qualityWarning,
                    arState = uiState.arState,
                    isPlanesDetected = uiState.isArPlanesDetected,
                    isTargetCreated = uiState.isArTargetCreated,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 40.dp)
                        .zIndex(10f)
                )
            }

            // Settings Dialog
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

            // --- AR TARGET CREATION FLOW ---
            if (uiState.isCapturingTarget) {
                Box(modifier = Modifier.zIndex(5f)) {
                    if (uiState.captureStep == CaptureStep.REVIEW) {
                        val uri = uiState.capturedTargetUris.firstOrNull()
                        val imageBitmap by produceState<Bitmap?>(initialValue = null, uri) {
                            uri?.let {
                                value = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                    val source = ImageDecoder.createSource(context.contentResolver, it)
                                    ImageDecoder.decodeBitmap(source)
                                } else {
                                    @Suppress("DEPRECATION")
                                    android.provider.MediaStore.Images.Media.getBitmap(context.contentResolver, it)
                                }
                            }
                        }

                        val maskUri = uiState.targetMaskUri
                        val maskBitmap by produceState<Bitmap?>(initialValue = null, maskUri) {
                            if (maskUri != null) {
                                value = withContext(Dispatchers.IO) {
                                    com.hereliesaz.graffitixr.utils.BitmapUtils.getBitmapFromUri(context, maskUri)
                                }
                            } else {
                                value = null
                            }
                        }

                        TargetRefinementScreen(
                            targetImage = imageBitmap,
                            mask = maskBitmap,
                            keypoints = uiState.detectedKeypoints,
                            paths = uiState.refinementPaths,
                            isEraser = uiState.isRefinementEraser,
                            canUndo = uiState.canUndo,
                            canRedo = uiState.canRedo,
                            onPathAdded = viewModel::onRefinementPathAdded,
                            onModeChanged = { viewModel.onRefinementModeChanged(!it) },
                            onUndo = viewModel::onUndoClicked,
                            onRedo = viewModel::onRedoClicked,
                            onConfirm = viewModel::onConfirmTargetCreation
                        )
                    } else {
                        TargetCreationOverlay(
                            step = uiState.captureStep,
                            targetCreationMode = uiState.targetCreationMode,
                            gridRows = uiState.gridRows,
                            gridCols = uiState.gridCols,
                            qualityWarning = uiState.qualityWarning,
                            captureFailureTimestamp = uiState.captureFailureTimestamp,
                            onCaptureClick = {
                                if (uiState.captureStep.name.startsWith("CALIBRATION_POINT")) {
                                    viewModel.onCalibrationPointCaptured()
                                } else {
                                    viewModel.onCaptureShutterClicked()
                                }
                            },
                            onCancelClick = viewModel::onCancelCaptureClicked,
                            onMethodSelected = viewModel::onTargetCreationMethodSelected,
                            onGridConfigChanged = viewModel::onGridConfigChanged,
                            onGpsDecision = viewModel::onGpsDecision,
                            onFinishPhotoSequence = viewModel::onPhotoSequenceFinished
                        )
                    }
                }
            }

            // Gesture Feedback
            if (!uiState.hideUiForCapture && !uiState.isTouchLocked) {
                GestureFeedback(
                    uiState = uiState,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 16.dp)
                        .zIndex(3f),
                    isVisible = gestureInProgress
                )
            }

            // Navigation Rail
            if (!uiState.isTouchLocked && !uiState.hideUiForCapture) {
                Box(
                    modifier = Modifier
                        .zIndex(6f)
                        .fillMaxHeight()
                        .onGloballyPositioned { coordinates ->
                            railTop = coordinates.positionInRoot().y
                        }
                ) {
                    AzNavRail {
                        azSettings(
                            isLoading = uiState.isLoading,
                            packRailButtons = true,
                            defaultShape = AzButtonShape.RECTANGLE,
                            onItemGloballyPositioned = onProbePosition
                        )

                        if (uiState.editorMode == EditorMode.HELP) {
                            azRailHostItem(id = "mode_host", text = "Modes", route = "mode_host")
                            azRailHostItem(id = "design_host", text = "Design", route = "design_host") {}
                            azRailHostItem(id = "settings_host", text = "Settings", route = "settings_host") {}
                        } else {
                            azRailHostItem(id = "mode_host", text = "Modes", route = "mode_host")
                            azRailSubItem(id = "ar", hostId = "mode_host", text = "AR Mode", onClick = { onModeSelected(EditorMode.AR) })
                        azRailSubItem(id = "ghost_mode", hostId = "mode_host", text = "Overlay", onClick = { onModeSelected(EditorMode.OVERLAY) })
                        azRailSubItem(id = "mockup", hostId = "mode_host", text = "Mockup", onClick = { onModeSelected(EditorMode.STATIC) })
                        azRailSubItem(id = "trace_mode", hostId = "mode_host", text = "Trace", onClick = { onModeSelected(EditorMode.TRACE) })

                        azDivider()

                        if (uiState.editorMode == EditorMode.AR) {
                            azRailHostItem(id = "target_host", text = "Grid", route = "target_host")
                            azRailSubItem(id = "create_target", hostId = "target_host", text = "Create", onClick = {
                                viewModel.onCreateTargetClicked()
                                showSliderDialog = null; showColorBalanceDialog = false
                            })
                            azRailSubItem(id = "refine_target", hostId = "target_host", text = "Refine", onClick = {
                                viewModel.onRefineTargetToggled()
                                showSliderDialog = null; showColorBalanceDialog = false
                            })
                            azRailSubItem(id = "mark_progress", hostId = "target_host", text = "Update", onClick = {
                                viewModel.onMarkProgressToggled()
                                showSliderDialog = null; showColorBalanceDialog = false
                            })

                            azDivider()
                        }

                        azRailHostItem(id = "design_host", text = "Design", route = "design_host") {}

                        azRailSubItem(id = "image", text = "Open", hostId = "design_host") {
                            showSliderDialog = null; showColorBalanceDialog = false
                            overlayImagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }

                        if (uiState.editorMode == EditorMode.STATIC) {
                            azRailSubItem(id = "background", hostId = "design_host", text = "Wall") {
                                showSliderDialog = null; showColorBalanceDialog = false
                                backgroundImagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            }
                        }

                        if (uiState.overlayImageUri != null) {
                            azRailSubItem(id = "isolate", hostId = "design_host", text = "Isolate", onClick = {
                                viewModel.onRemoveBackgroundClicked()
                                showSliderDialog = null; showColorBalanceDialog = false
                            })
                            azRailSubItem(id = "line_drawing", hostId = "design_host", text = "Outline", onClick = {
                                viewModel.onLineDrawingClicked()
                                showSliderDialog = null; showColorBalanceDialog = false
                            })
                            azDivider()

                            azRailSubItem(id = "adjust", hostId = "design_host", text = "Adjust") {
                                showSliderDialog = if (showSliderDialog == "Adjust") null else "Adjust"
                                showColorBalanceDialog = false
                            }
                            azRailSubItem(id = "color_balance", hostId = "design_host", text = "Balance") {
                                showColorBalanceDialog = !showColorBalanceDialog
                                showSliderDialog = null
                            }
                            azRailSubItem(id = "blending", hostId = "design_host", text = "Blending", onClick = {
                                viewModel.onCycleBlendMode()
                                showSliderDialog = null; showColorBalanceDialog = false
                            })
                        }

                        azDivider()

                        azRailHostItem(id = "settings_host", text = "Settings", route = "settings_host"){
                            showSettings = true
                            showSliderDialog = null; showColorBalanceDialog = false
                        }
                        azRailSubItem(id = "new_project", hostId = "settings_host", text = "New", onClick = {
                            viewModel.onNewProject()
                            showSliderDialog = null; showColorBalanceDialog = false
                        })
                        azRailSubItem(id = "save_project", hostId = "settings_host", text = "Save") {
                            createDocumentLauncher.launch("Project.gxr")
                            showSliderDialog = null; showColorBalanceDialog = false
                        }
                        azRailSubItem(id = "load_project", hostId = "settings_host", text = "Load") {
                            showProjectLibrary = true
                            showSliderDialog = null; showColorBalanceDialog = false
                        }
                        azRailSubItem(id = "export_project", hostId = "settings_host", text = "Export", onClick = {
                            viewModel.onSaveClicked()
                            showSliderDialog = null; showColorBalanceDialog = false
                        })
                        azRailSubItem(id = "help", hostId = "settings_host", text = "Help", onClick = {
                            viewModel.onEditorModeChanged(EditorMode.HELP)
                            showSliderDialog = null; showColorBalanceDialog = false
                        })

                        azDivider()

                        if (uiState.editorMode == EditorMode.AR || uiState.editorMode == EditorMode.OVERLAY) {
                            azRailItem(id = "light", text = "Light", onClick = {
                                viewModel.onToggleFlashlight()
                                showSliderDialog = null; showColorBalanceDialog = false
                            })
                        }

                        if (uiState.editorMode == EditorMode.TRACE) {
                            azRailItem(id = "lock_trace", text = "Lock", onClick = {
                                viewModel.setTouchLocked(true)
                                showSliderDialog = null; showColorBalanceDialog = false
                            })
                        }
                        } // End of else block
                    }
                }
            }

            // Touch Lock Overlay
            if (uiState.isTouchLocked) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(100f)
                        .background(Color.Transparent)
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                var tapCount = 0
                                var lastTapTime = 0L
                                while (true) {
                                    val event = awaitPointerEvent(pass = PointerEventPass.Main)
                                    val change = event.changes.firstOrNull()
                                    if (change != null && change.changedToUp()) {
                                        val now = System.currentTimeMillis()
                                        if (now - lastTapTime < 500) {
                                            tapCount++
                                        } else {
                                            tapCount = 1
                                        }
                                        lastTapTime = now

                                        if (tapCount == 4) {
                                            viewModel.showUnlockInstructions()
                                            tapCount = 0
                                        }
                                    }
                                    event.changes.forEach { it.consume() }
                                }
                            }
                        }
                )
            }

            // Unlock Instructions
            UnlockInstructionsPopup(visible = uiState.showUnlockInstructions)

            // Progress Drawing Canvas
            if (uiState.isMarkingProgress) {
                DrawingCanvas(
                    paths = uiState.drawingPaths,
                    onPathFinished = viewModel::onDrawingPathFinished
                )
            }

            // Dialogs
            if (showSaveProjectDialog) {
                SaveProjectDialog(
                    onDismissRequest = { showSaveProjectDialog = false },
                    onSaveRequest = { projectName ->
                        viewModel.saveProject(projectName)
                        showSaveProjectDialog = false
                    }
                )
            }

            // Adjustments Panels
            if (uiState.overlayImageUri != null && !uiState.hideUiForCapture && !uiState.isTouchLocked) {
                val showKnobs = showSliderDialog == "Adjust"

                UndoRedoRow(
                    canUndo = uiState.canUndo,
                    canRedo = uiState.canRedo,
                    onUndo = viewModel::onUndoClicked,
                    onRedo = viewModel::onRedoClicked,
                    onMagicClicked = viewModel::onMagicClicked,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(start = 100.dp, bottom = screenHeight * 0.075f) // Moved down and shifted right
                        .zIndex(3f)
                )

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

            // Onboarding
            uiState.showOnboardingDialogForMode?.let { mode ->
                OnboardingDialog(
                    editorMode = mode,
                    onDismiss = {
                        viewModel.onOnboardingComplete(mode)
                    }
                )
            }

            // Feedback Elements
            if (!uiState.hideUiForCapture && !uiState.isTouchLocked) {
                RotationAxisFeedback(
                    axis = uiState.activeRotationAxis,
                    visible = uiState.showRotationAxisFeedback,
                    onFeedbackShown = viewModel::onFeedbackShown,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 32.dp)
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
                            .padding(top = 16.dp)
                            .zIndex(3f)
                    )
                }
            }

            if (uiState.isCapturingTarget) {
                CaptureAnimation()
            }

            if (uiState.isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = Color.White
                )
            }

            // Help Mode Overlay
            if (uiState.editorMode == EditorMode.HELP) {
                HelpOverlay(
                    itemPositions = railItemPositions,
                    onDismiss = { onModeSelected(EditorMode.STATIC) }
                )
            }
        }
    }
}

@Composable
fun StatusOverlay(
    qualityWarning: String?,
    arState: ArState,
    isPlanesDetected: Boolean,
    isTargetCreated: Boolean,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = true,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        val backgroundColor = if (qualityWarning != null) Color.Red.copy(alpha = 0.8f) else Color.Black.copy(alpha = 0.5f)
        val text = when {
            qualityWarning != null -> qualityWarning
            !isTargetCreated -> "Create a Grid to start."
            arState == ArState.SEARCHING && !isPlanesDetected -> "Scan surfaces around you."
            arState == ArState.SEARCHING && isPlanesDetected -> "Tap a surface to place anchor."
            arState == ArState.LOCKED -> "Looking for your Grid..."
            arState == ArState.PLACED -> "Ready."
            else -> ""
        }

        if (text.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .background(backgroundColor, RoundedCornerShape(8.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    text = text,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            }
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
        shutterAlpha = 0.5f
        delay(100)
        flashAlpha = 1f
        delay(50)
        flashAlpha = 0f
        delay(150)
        shutterAlpha = 0f
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = animatedShutterAlpha))
            .zIndex(10f)
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White.copy(alpha = animatedFlashAlpha))
            .zIndex(11f)
    )
}

@Composable
fun UnlockInstructionsPopup(visible: Boolean) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
        exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 }),
        modifier = Modifier
            .fillMaxSize()
            .zIndex(200f)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 120.dp),
            contentAlignment = Alignment.BottomCenter
        ) {
            Box(
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.8f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 24.dp, vertical = 16.dp)
            ) {
                Text(
                    text = "Press Volume Up & Down to unlock",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
