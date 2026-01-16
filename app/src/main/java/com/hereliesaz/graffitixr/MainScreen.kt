package com.hereliesaz.graffitixr

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset // Added Import
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.hereliesaz.graffitixr.composables.AdjustmentsPanel
import com.hereliesaz.graffitixr.ui.components.AzNavRail
import com.hereliesaz.graffitixr.ui.components.Knob
import kotlinx.coroutines.launch

@Composable
fun MainScreen(viewModel: MainViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val screenHeight = configuration.screenHeightDp.dp

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {

        // AR View
        AndroidView(
            factory = { ctx ->
                val renderer = ArRenderer(
                    context = ctx,
                    onPlanesDetected = { viewModel.setArPlanesDetected(it) },
                    onFrameCaptured = { viewModel.onFrameCaptured(it) },
                    onAnchorCreated = { viewModel.onArImagePlaced() },
                    onProgressUpdated = { p, b -> viewModel.onProgressUpdate(p, b) },
                    onTrackingFailure = { viewModel.onTrackingFailure(it) },
                    onBoundsUpdated = { viewModel.updateArtworkBounds(it) }
                )
                viewModel.arRenderer = renderer
                android.opengl.GLSurfaceView(ctx).apply {
                    preserveEGLContextOnPause = true
                    setEGLContextClientVersion(3)
                    setEGLConfigChooser(8, 8, 8, 8, 16, 0)
                    setRenderer(renderer)
                    renderMode = android.opengl.GLSurfaceView.RENDERMODE_CONTINUOUSLY
                }
            },
            modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        if (!uiState.isImageLocked && uiState.overlayImageUri != null) {
                            viewModel.onCycleRotationAxis()
                        }
                    },
                    onTap = {
                        if (uiState.targetCreationMode == TargetCreationMode.GUIDED_POINTS && uiState.isCapturingTarget) {
                            viewModel.onCalibrationPointCaptured()
                        } else if (!uiState.isImageLocked) {
                            viewModel.arRenderer?.queueTap(it.x, it.y)
                        }
                    }
                )
            }.pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    if (!uiState.isImageLocked && uiState.overlayImageUri != null) {
                        viewModel.onGestureStart()
                        if (zoom != 1f) viewModel.onArObjectScaleChanged(zoom)
                        if (pan != Offset.Zero) viewModel.onOffsetChanged(pan)
                        viewModel.onGestureEnd()
                    }
                }
            }
        )

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

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                if (uiState.qualityWarning != null) {
                    Text(
                        text = uiState.qualityWarning ?: "",
                        color = Color.Red,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 16.dp)
                            .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                            .padding(8.dp)
                    )
                }

                // Explicitly use the androidx animation visibility
                androidx.compose.animation.AnimatedVisibility(
                    visible = uiState.showRotationAxisFeedback,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.align(Alignment.Center)
                ) {
                    Box(
                        modifier = Modifier
                            .size(100.dp)
                            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(16.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = uiState.activeRotationAxis.name,
                            color = Color.Cyan,
                            style = MaterialTheme.typography.displayMedium
                        )
                    }
                    LaunchedEffect(Unit) { viewModel.onFeedbackShown() }
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
        }
    }
}