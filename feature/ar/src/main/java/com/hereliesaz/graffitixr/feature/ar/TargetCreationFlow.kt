// FILE: feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/TargetCreationFlow.kt
package com.hereliesaz.graffitixr.feature.ar

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.common.model.ArUiState
import com.hereliesaz.graffitixr.common.model.CaptureStep

/**
 * Orchestrates the multi-step UI flow for capturing, rectifying, and masking physical targets.
 */
@Composable
fun TargetCreationUi(
    uiState: ArUiState,
    isRightHanded: Boolean,
    captureStep: CaptureStep,
    isLoading: Boolean,
    onConfirm: (Bitmap?) -> Unit,
    onRetake: () -> Unit,
    onCancel: () -> Unit,
    onUnwarpConfirm: (List<Offset>) -> Unit,
    onMaskConfirmed: (Bitmap) -> Unit,
    onRequestCapture: () -> Unit,
    onUpdateUnwarpPoints: (List<Offset>) -> Unit,
    onSetActiveUnwarpPoint: (Int) -> Unit,
    onSetMagnifierPosition: (Offset) -> Unit,
    onUpdateMaskPath: (Path?) -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        when (captureStep) {
            CaptureStep.CAPTURE -> {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 48.dp)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    IconButton(
                        onClick = onCancel,
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .padding(start = 32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Clear,
                            contentDescription = "Cancel",
                            tint = Color.White,
                            modifier = Modifier.size(48.dp)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .border(4.dp, Color.White, CircleShape)
                            .padding(8.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                            .clickable { onRequestCapture() }
                    )
                }
            }
            CaptureStep.RECTIFY -> {
                UnwarpUi(
                    isRightHanded = isRightHanded,
                    targetImage = uiState.tempCaptureBitmap,
                    points = uiState.unwarpPoints,
                    activePointIndex = uiState.activeUnwarpPointIndex,
                    magnifierPosition = uiState.magnifierPosition,
                    onPointIndexChanged = onSetActiveUnwarpPoint,
                    onUpdateUnwarpPoints = onUpdateUnwarpPoints,
                    onMagnifierPositionChanged = onSetMagnifierPosition,
                    onConfirm = onUnwarpConfirm,
                    onRetake = onRetake
                )
            }
            CaptureStep.MASK -> {
                Box(modifier = Modifier.fillMaxSize()) {
                    uiState.tempCaptureBitmap?.let { bmp ->
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "Mask Target",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    }
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(32.dp)
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        FloatingActionButton(
                            onClick = onRetake,
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "Retake")
                        }
                        FloatingActionButton(
                            onClick = { uiState.tempCaptureBitmap?.let { onMaskConfirmed(it) } },
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Icon(Icons.Default.Check, contentDescription = "Confirm Mask")
                        }
                    }
                }
            }
            CaptureStep.REVIEW -> {
                Box(modifier = Modifier.fillMaxSize()) {
                    // Show the keypoint-annotated version while it's ready, so the artist
                    // can judge texture quality before confirming. Falls back to the plain
                    // capture bitmap while annotation is still computing.
                    val displayBmp = uiState.annotatedCaptureBitmap ?: uiState.tempCaptureBitmap
                    displayBmp?.let { bmp ->
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "Review Target",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    }
                    if (uiState.annotatedCaptureBitmap == null && uiState.tempCaptureBitmap != null) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 16.dp),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(32.dp)
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        FloatingActionButton(
                            onClick = onRetake,
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "Retake")
                        }
                        FloatingActionButton(
                            onClick = { onConfirm(uiState.tempCaptureBitmap) },
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Icon(Icons.Default.Check, contentDescription = "Finish")
                        }
                    }
                }
            }
            else -> {}
        }

        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .pointerInput(Unit) {},
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/**
 * Handles underlying background processes or visual indicators during target creation.
 */
@Composable
fun TargetCreationBackground(
    uiState: ArUiState,
    captureStep: CaptureStep,
    onInitUnwarpPoints: (List<Offset>) -> Unit
) {
    // Scaffold for background guide elements if needed
}