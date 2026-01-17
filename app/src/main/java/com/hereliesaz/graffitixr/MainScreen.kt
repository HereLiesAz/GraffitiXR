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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.hereliesaz.graffitixr.composables.AdjustmentsPanel
import com.hereliesaz.graffitixr.ui.components.AzNavRail
import com.hereliesaz.graffitixr.ui.components.Knob
import kotlinx.coroutines.launch

/**
 * The top-level UI composable for the GraffitiXR application.
 */
@Composable
fun MainScreen(viewModel: MainViewModel, navController: NavController) {
    val uiState by viewModel.uiState.collectAsState()
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val screenHeight = configuration.screenHeightDp.dp

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

    // Pre-load strings to avoid Composable calls in lambdas
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

        Row(modifier = Modifier.fillMaxSize()) {
            AzNavRail(
                currentMode = uiState.editorMode,
                onModeSelected = { mode ->
                    when (mode) {
                        EditorMode.ISOLATE -> viewModel.onRemoveBackgroundClicked()
                        EditorMode.OUTLINE -> viewModel.onLineDrawingClicked()
                        else -> viewModel.onEditorModeChanged(mode)
                    }
                },
                onCapture = { viewModel.onCaptureShutterClicked() },
                onMenu = { /* Open Menu */ }
            )

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

                if (uiState.layers.isNotEmpty() && !uiState.isImageLocked) {
                    if (uiState.editorMode == EditorMode.AR) {
                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(24.dp)
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(32.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Knob(
                                    value = when (uiState.activeRotationAxis) {
                                        RotationAxis.X -> uiState.rotationX
                                        RotationAxis.Y -> uiState.rotationY
                                        RotationAxis.Z -> uiState.rotationZ
                                    },
                                    onValueChange = { delta ->
                                        when (uiState.activeRotationAxis) {
                                            RotationAxis.X -> viewModel.onRotationXChanged(delta * 360f)
                                            RotationAxis.Y -> viewModel.onRotationYChanged(delta * 360f)
                                            RotationAxis.Z -> viewModel.onRotationZChanged(delta * 360f)
                                        }
                                    },
                                    text = "Rot ${uiState.activeRotationAxis.name}"
                                )

                                Knob(
                                    value = uiState.scale,
                                    onValueChange = { viewModel.onArObjectScaleChanged(it) },
                                    text = "Scale"
                                )

                                Knob(
                                    value = uiState.opacity,
                                    onValueChange = { viewModel.onOpacityChanged(it) },
                                    range = 0f..1f,
                                    text = "Opacity"
                                )
                            }

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(32.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .background(Color.Black.copy(alpha = 0.7f), CircleShape)
                                    .border(1.dp, Color.DarkGray, CircleShape)
                                    .padding(horizontal = 24.dp, vertical = 12.dp)
                            ) {
                                IconButton(
                                    onClick = { viewModel.onUndoClicked() },
                                    enabled = uiState.canUndo
                                ) {
                                    Icon(Icons.Default.Undo, "Undo", tint = if (uiState.canUndo) Color.White else Color.Gray)
                                }

                                IconButton(onClick = { viewModel.onMagicClicked() }) {
                                    Icon(Icons.Default.AutoFixHigh, "Align", tint = Color.Cyan)
                                }

                                IconButton(
                                    onClick = { viewModel.onRedoClicked() },
                                    enabled = uiState.canRedo
                                ) {
                                    Icon(Icons.Default.Redo, "Redo", tint = if (uiState.canRedo) Color.White else Color.Gray)
                                }
                            }
                        }
                    } else if (uiState.editorMode == EditorMode.ADJUST || uiState.editorMode == EditorMode.BALANCE) {
                        AdjustmentsPanel(
                            uiState = uiState,
                            showKnobs = uiState.editorMode == EditorMode.ADJUST,
                            showColorBalance = uiState.editorMode == EditorMode.BALANCE,
                            isLandscape = isLandscape,
                            screenHeight = screenHeight,
                            onOpacityChange = { viewModel.onOpacityChanged(it) },
                            onBrightnessChange = { viewModel.onBrightnessChanged(it) },
                            onContrastChange = { viewModel.onContrastChanged(it) },
                            onSaturationChange = { viewModel.onSaturationChanged(it) },
                            onColorBalanceRChange = { viewModel.onColorBalanceRChanged(it) },
                            onColorBalanceGChange = { viewModel.onColorBalanceGChanged(it) },
                            onColorBalanceBChange = { viewModel.onColorBalanceBChanged(it) },
                            onUndo = { viewModel.onUndoClicked() },
                            onRedo = { viewModel.onRedoClicked() },
                            onMagicAlign = { viewModel.onMagicClicked() },
                            modifier = Modifier.align(Alignment.BottomCenter)
                        )
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
