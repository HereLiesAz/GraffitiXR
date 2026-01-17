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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
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
import com.hereliesaz.aznavrail.AzButton
import com.hereliesaz.aznavrail.AzNavRail
import com.hereliesaz.aznavrail.model.AzButtonShape
import com.hereliesaz.aznavrail.model.AzHeaderIconShape
import com.hereliesaz.graffitixr.EditorMode.ADJUST
import com.hereliesaz.graffitixr.EditorMode.AR
import com.hereliesaz.graffitixr.EditorMode.BALANCE
import com.hereliesaz.graffitixr.EditorMode.CROP
import com.hereliesaz.graffitixr.EditorMode.DRAW
import com.hereliesaz.graffitixr.EditorMode.ISOLATE
import com.hereliesaz.graffitixr.EditorMode.OUTLINE
import com.hereliesaz.graffitixr.EditorMode.OVERLAY
import com.hereliesaz.graffitixr.EditorMode.PROJECT
import com.hereliesaz.graffitixr.EditorMode.STATIC
import com.hereliesaz.graffitixr.EditorMode.TRACE
import com.hereliesaz.graffitixr.composables.AdjustmentsPanel
import com.hereliesaz.graffitixr.composables.CustomHelpOverlay
import com.hereliesaz.graffitixr.composables.DrawingCanvas
import com.hereliesaz.graffitixr.composables.GestureFeedback
import com.hereliesaz.graffitixr.composables.MockupScreen
import com.hereliesaz.graffitixr.composables.OverlayScreen
import com.hereliesaz.graffitixr.composables.ProjectLibraryScreen
import com.hereliesaz.graffitixr.composables.RotationAxisFeedback
import com.hereliesaz.graffitixr.composables.SettingsScreen
import com.hereliesaz.graffitixr.composables.TapFeedbackEffect
import com.hereliesaz.graffitixr.composables.TargetCreationOverlay
import com.hereliesaz.graffitixr.composables.TargetRefinementScreen
import com.hereliesaz.graffitixr.composables.TraceScreen
import com.hereliesaz.graffitixr.composables.UnwarpScreen
import com.hereliesaz.graffitixr.data.CaptureEvent
import com.hereliesaz.graffitixr.data.FeedbackEvent
import com.hereliesaz.graffitixr.dialogs.DoubleTapHintDialog
import com.hereliesaz.graffitixr.dialogs.OnboardingDialog
import com.hereliesaz.graffitixr.ui.rememberNavStrings
import com.hereliesaz.graffitixr.utils.captureWindow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The top-level UI composable for the GraffitiXR application.
 */
@Composable
fun MainScreen(viewModel: MainViewModel, navController: NavController) {
    val uiState by viewModel.uiState.collectAsState()
    val tapFeedback by viewModel.tapFeedback.collectAsState()
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.screenWidthDp > configuration.screenHeightDp

    // Capture theme color for AzNavRail
    val activeHighlightColor = MaterialTheme.colorScheme.tertiary

    // UI Visibility States
    var showSliderDialog by remember { mutableStateOf<String?>(null) }
    var showColorBalanceDialog by remember { mutableStateOf(false) }
    var showProjectLibrary by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var gestureInProgress by remember { mutableStateOf(false) }
    var showInfoScreen by remember { mutableStateOf(false) }

    // Automation State
    var hasSelectedModeOnce by remember { mutableStateOf(false) }

    // Helper to reset dialog states
    val resetDialogs = remember {
        {
            showSliderDialog = null
            showColorBalanceDialog = false
        }
    }

    // Preload strings to avoid Composable calls in lambdas
    val navStrings = rememberNavStrings()

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
                    is FeedbackEvent.Toast -> {
                         android.widget.Toast.makeText(context, event.message, android.widget.Toast.LENGTH_SHORT).show()
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
                is CaptureEvent.CaptureSuccess -> { /* Handle success if needed */ }
                is CaptureEvent.CaptureFailure -> { /* Handle failure if needed */ }
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
    LaunchedEffect(uiState.showImagePicker) {
        if (uiState.showImagePicker) {
            overlayImagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            viewModel.onImagePickerShown()
        }
    }

    // Helper for automation
    val onModeSelected = remember(viewModel, hasSelectedModeOnce) {
        { mode: EditorMode ->
            viewModel.onEditorModeChanged(mode)
            resetDialogs()

            if (!hasSelectedModeOnce) {
                hasSelectedModeOnce = true
                if (mode == EditorMode.AR) {
                    viewModel.onCreateTargetClicked()
                }
            }
        }
    }

    LaunchedEffect(uiState.editorMode) {
        if (uiState.editorMode == EditorMode.TRACE && uiState.overlayImageUri == null) {
            overlayImagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
    }

    // Calculate current route for NavRail highlighting
    val currentRoute = remember(uiState.editorMode, showSettings, showProjectLibrary, showSliderDialog, showColorBalanceDialog, uiState.isMarkingProgress, uiState.isCapturingTarget, showInfoScreen, uiState.activeLayerId, uiState.isMappingMode) {
        when {
            showInfoScreen -> "help"
            showSettings -> "settings_sub"
            showProjectLibrary -> "load_project"
            showSliderDialog == "Adjust" -> "adjust"
            showColorBalanceDialog -> "color_balance"
            uiState.isMarkingProgress -> "mark_progress"
            uiState.isMappingMode -> "neural_scan"
            uiState.isCapturingTarget -> "create_target"
            uiState.activeLayerId != null -> "layer_${uiState.activeLayerId}"
            uiState.editorMode == EditorMode.AR -> "ar"
            uiState.editorMode == EditorMode.OVERLAY -> "ghost_mode"
            uiState.editorMode == EditorMode.STATIC -> "mockup"
            uiState.editorMode == EditorMode.TRACE -> "trace_mode"
            else -> null
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color.Black
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                val screenHeight = maxHeight

                if (showProjectLibrary || uiState.showProjectList) {
                    ProjectLibraryScreen(
                        projects = uiState.availableProjects,
                        onLoadProject = { project ->
                            viewModel.openProject(project, context)
                            showProjectLibrary = false
                        },
                        onDeleteProject = { projectId ->
                            viewModel.deleteProject(context, projectId)
                        },
                        onNewProject = {
                            viewModel.onNewProject()
                            showProjectLibrary = false
                        }
                    )
                } else {
                    MainContentLayer(
                        uiState = uiState,
                        viewModel = viewModel,
                        gestureInProgress = gestureInProgress,
                        onGestureToggle = { gestureInProgress = it }
                    )
                }

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

                // NEURAL SCAN HUD
                if (uiState.isMappingMode) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 80.dp)
                            .fillMaxWidth(0.6f)
                            .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
                            .padding(16.dp)
                            .zIndex(12f)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "MAPPING QUALITY",
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { uiState.mappingQualityScore },
                                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                                color = if (uiState.mappingQualityScore > 0.8f) Color.Green else Color.Yellow,
                                trackColor = Color.DarkGray
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            if (uiState.mappingQualityScore > 0.5f) {
                                AzButton(
                                    text = if (uiState.isHostingAnchor) "UPLOADING..." else "FINALIZE MAP",
                                    shape = AzButtonShape.RECTANGLE,
                                    onClick = { viewModel.finalizeMap() },
                                    enabled = !uiState.isHostingAnchor
                                )
                            } else {
                                Text("Scan more area...", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
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

                TargetCreationFlow(uiState, viewModel, context)

                if (!uiState.isTouchLocked && !uiState.hideUiForCapture) {
                    GestureFeedback(
                        uiState = uiState,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 16.dp)
                            .zIndex(3f),
                        isVisible = gestureInProgress
                    )
                }

                if (!uiState.isTouchLocked && !uiState.hideUiForCapture) {
                    Box(
                        modifier = Modifier
                            .zIndex(6f)
                            .fillMaxHeight()
                    ) {
                        AzNavRail(
                            navController = navController,
                            currentDestination = currentRoute,
                            isLandscape = isLandscape
                        ) {
                            azSettings(
                                isLoading = uiState.isLoading,
                                packRailButtons = true,
                                defaultShape = AzButtonShape.RECTANGLE,
                                headerIconShape = AzHeaderIconShape.ROUNDED,
                                infoScreen = showInfoScreen,
                                activeColor = activeHighlightColor,
                                onDismissInfoScreen = { showInfoScreen = false }
                            )

                            azRailHostItem(id = "mode_host", text = navStrings.modes, onClick = {})
                            azRailSubItem(id = "ar", hostId = "mode_host", text = navStrings.arMode, info = navStrings.arModeInfo, onClick = { onModeSelected(EditorMode.AR) })
                            azRailSubItem(id = "ghost_mode", hostId = "mode_host", text = navStrings.overlay, info = navStrings.overlayInfo, onClick = { onModeSelected(EditorMode.OVERLAY) })
                            azRailSubItem(id = "mockup", hostId = "mode_host", text = navStrings.mockup, info = navStrings.mockupInfo, onClick = { onModeSelected(EditorMode.STATIC) })
                            azRailSubItem(id = "trace_mode", hostId = "mode_host", text = navStrings.trace, info = navStrings.traceInfo, onClick = { onModeSelected(EditorMode.TRACE) })

                            azDivider()

                            if (uiState.editorMode == EditorMode.AR) {
                                azRailHostItem(id = "target_host", text = navStrings.grid, onClick = {})

                                // NEW: Neural Scan Item
                                azRailSubItem(
                                    id = "neural_scan",
                                    hostId = "target_host",
                                    text = if (uiState.isMappingMode) "Stop Scan" else "Scan Space",
                                    info = "Map the area for persistence",
                                    onClick = { viewModel.toggleMappingMode() }
                                )

                                azRailSubItem(id = "surveyor", hostId = "target_host", text = navStrings.surveyor, info = navStrings.surveyorInfo) {
                                    val intent = android.content.Intent(context, MappingActivity::class.java)
                                    context.startActivity(intent)
                                    resetDialogs()
                                }
                                azRailSubItem(id = "create_target", hostId = "target_host", text = navStrings.create, info = navStrings.createInfo, onClick = {
                                    viewModel.onCreateTargetClicked()
                                    resetDialogs()
                                })
                                azRailSubItem(id = "refine_target", hostId = "target_host", text = navStrings.refine, info = navStrings.refineInfo, onClick = {
                                    viewModel.onRefineTargetToggled()
                                    resetDialogs()
                                })
                                azRailSubItem(id = "mark_progress", hostId = "target_host", text = navStrings.update, info = navStrings.updateInfo, onClick = {
                                    viewModel.onMarkProgressToggled()
                                    resetDialogs()
                                })

                                azDivider()
                            }

                            azRailHostItem(id = "design_host", text = navStrings.design, onClick = {})

                            val openButtonText = if (uiState.layers.isNotEmpty()) "Add" else navStrings.open
                            val openButtonId = if (uiState.layers.isNotEmpty()) "add_layer" else "image"

                            azRailSubItem(id = openButtonId, text = openButtonText, hostId = "design_host", info = navStrings.openInfo) {
                                resetDialogs()
                                overlayImagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            }

                            // Dynamic Layers
                            val layers = uiState.layers
                            val visualLayers = layers.reversed()

                            visualLayers.forEach { layer ->
                                azRailRelocItem(
                                    id = "layer_${layer.id}",
                                    hostId = "design_host",
                                    text = layer.name,
                                    onClick = {
                                        if (uiState.activeLayerId != layer.id) {
                                            viewModel.onLayerActivated(layer.id)
                                        }
                                    },
                                    onRelocate = { _: Int, _: Int, newOrder: List<String> ->
                                        val rawIds = newOrder.map { it.removePrefix("layer_") }
                                        val logicalOrder = rawIds.reversed()
                                        viewModel.onLayerReordered(logicalOrder)
                                    }
                                ) {
                                    // Hidden Menu
                                    inputItem(
                                        hint = "Rename"
                                    ) { newName ->
                                        viewModel.onLayerRenamed(layer.id, newName)
                                    }

                                    listItem(text = "Duplicate") {
                                        viewModel.onLayerDuplicated(layer.id)
                                    }

                                    listItem(text = "Copy Mods") {
                                        viewModel.copyLayerModifications(layer.id)
                                    }

                                    listItem(text = "Paste Mods") {
                                        viewModel.pasteLayerModifications(layer.id)
                                    }

                                    listItem(text = "Remove") {
                                        viewModel.onLayerRemoved(layer.id)
                                    }
                                }
                            }

                            if (uiState.editorMode == EditorMode.STATIC) {
                                azRailSubItem(id = "background", hostId = "design_host", text = navStrings.wall, info = navStrings.wallInfo) {
                                    resetDialogs()
                                    backgroundImagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                }
                            }

                            if (uiState.overlayImageUri != null || uiState.layers.isNotEmpty()) {
                                azRailSubItem(id = "isolate", hostId = "design_host", text = navStrings.isolate, info = navStrings.isolateInfo, onClick = {
                                    viewModel.onRemoveBackgroundClicked()
                                    showSliderDialog = null; showColorBalanceDialog = false

                                    resetDialogs()
                                })
                                azRailSubItem(id = "outline", hostId = "design_host", text = navStrings.outline, info = navStrings.outlineInfo, onClick = {
                                    viewModel.onLineDrawingClicked()
                                    showSliderDialog = null; showColorBalanceDialog = false
                                    resetDialogs()
                                })
                                azDivider()

                                if (uiState.editorMode == EditorMode.STATIC) {
                                    azRailSubItem(id = "background", hostId = "design_host", text = "Wall") {
                                        showSliderDialog = null; showColorBalanceDialog = false
                                        backgroundImagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                    }
                                }
                                azRailSubItem(id = "adjust", hostId = "design_host", text = navStrings.adjust, info = navStrings.adjustInfo) {
                                    showSliderDialog = if (showSliderDialog == "Adjust") null else "Adjust"
                                    showColorBalanceDialog = false
                                }
                                azRailSubItem(id = "color_balance", hostId = "design_host", text = navStrings.balance, info = navStrings.balanceInfo) {
                                    showColorBalanceDialog = true
                                    showSliderDialog = null
                                }
                                azRailSubItem(id = "blending", hostId = "design_host", text = navStrings.build, info = navStrings.blendingInfo, onClick = {
                                    viewModel.onCycleBlendMode()
                                    showSliderDialog = null; showColorBalanceDialog = false
                                    resetDialogs()
                                })

                                azRailSubToggle(
                                    id = "lock_image",
                                    hostId = "design_host",
                                    isChecked = uiState.isImageLocked,
                                    toggleOnText = "Locked",
                                    toggleOffText = "Unlocked",
                                    info = "Prevent accidental moves",
                                    onClick = { viewModel.toggleImageLock() }
                                )
                            }

                            azDivider()

                            azRailHostItem(id = "project_host", text = navStrings.project, onClick = {})
                            azRailSubItem(id = "settings_sub", hostId = "project_host", text = navStrings.settings, info = "App Settings") {
                                showSettings = true
                                resetDialogs()
                            }
                            azRailSubItem(id = "new_project", hostId = "project_host", text = navStrings.new, info = navStrings.newInfo, onClick = {
                                viewModel.onNewProject()
                                resetDialogs()
                            })
                            azRailSubItem(id = "save_project", hostId = "project_host", text = navStrings.save, info = navStrings.saveInfo) {
                                createDocumentLauncher.launch("Project.gxr")
                                resetDialogs()
                            }
                            azRailSubItem(id = "load_project", hostId = "project_host", text = navStrings.load, info = navStrings.loadInfo) {
                                viewModel.loadAvailableProjects(context)
                                showProjectLibrary = true
                                resetDialogs()
                            }
                            azRailSubItem(id = "export_project", hostId = "project_host", text = navStrings.export, info = navStrings.exportInfo, onClick = {
                                viewModel.onSaveClicked()
                                resetDialogs()
                            })

                            azDivider()

                            azRailItem(id = "help", text = "Help", info = "Show Help") {
                                showInfoScreen = true
                                resetDialogs()
                            }

                            if (uiState.editorMode == EditorMode.AR || uiState.editorMode == EditorMode.OVERLAY) {
                                azRailItem(id = "light", text = navStrings.light, info = navStrings.lightInfo, onClick = {
                                    viewModel.onToggleFlashlight()
                                    resetDialogs()
                                })
                            }

                            if (uiState.editorMode == EditorMode.TRACE) {
                                azRailItem(id = "lock_trace", text = navStrings.lock, info = navStrings.lockInfo, onClick = {
                                    viewModel.setTouchLocked(true)
                                    resetDialogs()
                                })
                            }
                        }
                    }
                }

                TouchLockOverlay(uiState.isTouchLocked, viewModel::showUnlockInstructions)

                UnlockInstructionsPopup(visible = uiState.showUnlockInstructions)

                if (showInfoScreen) {
                    CustomHelpOverlay(
                        uiState = uiState,
                        navStrings = navStrings,
                        onDismiss = { showInfoScreen = false }
                    )
                }

                if (uiState.isMarkingProgress) {
                    DrawingCanvas(
                        paths = uiState.drawingPaths,
                        onPathFinished = viewModel::onDrawingPathFinished
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(2f),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    AdjustmentsPanel(
                        uiState = uiState,
                        showKnobs = showSliderDialog == "Adjust",
                        showColorBalance = showColorBalanceDialog,
                        isLandscape = isLandscape,
                        screenHeight = screenHeight,
                        onOpacityChange = viewModel::onOpacityChanged,
                        onBrightnessChange = viewModel::onBrightnessChanged,
                        onContrastChange = viewModel::onContrastChanged,
                        onSaturationChange = viewModel::onSaturationChanged,
                        onColorBalanceRChange = viewModel::onColorBalanceRChanged,
                        onColorBalanceGChange = viewModel::onColorBalanceGChanged,
                        onColorBalanceBChange = viewModel::onColorBalanceBChanged,
                        onUndo = viewModel::onUndoClicked,
                        onRedo = viewModel::onRedoClicked,
                        onMagicAlign = viewModel::onMagicClicked
                    )
                }

                uiState.showOnboardingDialogForMode?.let { mode ->
                    OnboardingDialog(
                        editorMode = mode,
                        onDismiss = {
                            viewModel.onOnboardingComplete(mode)
                        }
                    )
                }

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
            }
        }
    }
}

@Composable
private fun MainContentLayer(
    uiState: UiState,
    viewModel: MainViewModel,
    gestureInProgress: Boolean,
    onGestureToggle: (Boolean) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(1f),
        contentAlignment = Alignment.Center
    ) {
        val onScaleChanged: (Float) -> Unit = viewModel::onScaleChanged
        val onOffsetChanged: (Offset) -> Unit = viewModel::onOffsetChanged
        val onRotationZChanged: (Float) -> Unit = viewModel::onRotationZChanged
        val onRotationXChanged: (Float) -> Unit = viewModel::onRotationXChanged
        val onRotationYChanged: (Float) -> Unit = viewModel::onRotationYChanged
        val onCycleRotationAxis: () -> Unit = viewModel::onCycleRotationAxis
        val onGestureStart: () -> Unit = {
            viewModel.onGestureStart()
            onGestureToggle(true)
        }
        val onGestureEnd: () -> Unit = {
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
                onScaleChanged = onScaleChanged,
                onOffsetChanged = onOffsetChanged,
                onRotationZChanged = onRotationZChanged,
                onRotationXChanged = onRotationXChanged,
                onRotationYChanged = onRotationYChanged,
                onCycleRotationAxis = onCycleRotationAxis,
                onGestureStart = onGestureStart,
                onGestureEnd = onGestureEnd
            )
            TRACE -> TraceScreen(
                uiState = uiState,
                onOverlayImageSelected = viewModel::onOverlayImageSelected,
                onScaleChanged = onScaleChanged,
                onOffsetChanged = onOffsetChanged,
                onRotationZChanged = onRotationZChanged,
                onRotationXChanged = onRotationXChanged,
                onRotationYChanged = onRotationYChanged,
                onCycleRotationAxis = onCycleRotationAxis,
                onGestureStart = onGestureStart,
                onGestureEnd = onGestureEnd
            )
            OVERLAY -> OverlayScreen(
                uiState = uiState,
                onScaleChanged = onScaleChanged,
                onOffsetChanged = onOffsetChanged,
                onRotationZChanged = onRotationZChanged,
                onRotationXChanged = onRotationXChanged,
                onRotationYChanged = onRotationYChanged,
                onCycleRotationAxis = onCycleRotationAxis,
                onGestureStart = onGestureStart,
                onGestureEnd = onGestureEnd
            )
            AR -> {
                ArView(
                    viewModel = viewModel,
                    uiState = uiState
                )
            }

            // Fallback for tool modes to ensure content is visible
            CROP, ADJUST, DRAW, ISOLATE, BALANCE, OUTLINE -> OverlayScreen(
                uiState = uiState,
                onScaleChanged = onScaleChanged,
                onOffsetChanged = onOffsetChanged,
                onRotationZChanged = onRotationZChanged,
                onRotationXChanged = onRotationXChanged,
                onRotationYChanged = onRotationYChanged,
                onCycleRotationAxis = onCycleRotationAxis,
                onGestureStart = onGestureStart,
                onGestureEnd = onGestureEnd
            )
            PROJECT -> {
                // Should be handled by showProjectList, but as a fallback:
                Box(modifier = Modifier.fillMaxSize().background(Color.Black))
            }
        }
    }
}

@Composable
private fun TargetCreationFlow(
    uiState: UiState,
    viewModel: MainViewModel,
    context: Context
) {
    if (!uiState.isCapturingTarget) return

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
                        com.hereliesaz.graffitixr.utils.ImageUtils.loadBitmapFromUri(context, maskUri)
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
        } else if (uiState.captureStep == CaptureStep.RECTIFY) {
            val uri = uiState.capturedTargetUris.firstOrNull()
            val imageBitmap by produceState<Bitmap?>(initialValue = null, uri, uiState.capturedTargetImages) {
                if (uiState.capturedTargetImages.isNotEmpty()) {
                    value = uiState.capturedTargetImages.first()
                } else if (uri != null) {
                    value = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        val source = ImageDecoder.createSource(context.contentResolver, uri)
                        ImageDecoder.decodeBitmap(source)
                    } else {
                        @Suppress("DEPRECATION")
                        android.provider.MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
                    }
                }
            }

            UnwarpScreen(
                targetImage = imageBitmap,
                onConfirm = viewModel::unwarpImage,
                onRetake = viewModel::onRetakeCapture
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

@Composable
private fun TouchLockOverlay(isLocked: Boolean, onUnlockRequested: () -> Unit) {
    if (!isLocked) return

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
                                onUnlockRequested()
                                tapCount = 0
                            }
                        }
                        event.changes.forEach { it.consume() }
                    }
                }
            }
    )
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
