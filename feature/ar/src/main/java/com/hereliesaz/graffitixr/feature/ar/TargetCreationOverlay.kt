package com.hereliesaz.graffitixr.feature.ar

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.common.model.ArUiState
import com.hereliesaz.graffitixr.common.model.CaptureStep

@Composable
fun TargetCreationOverlayBackground(
    uiState: ArUiState,
    step: CaptureStep
) {
    Box(modifier = Modifier.fillMaxSize()) {
        when (step) {
            CaptureStep.CAPTURE -> {
                Box(
                    modifier = Modifier
                        .size(300.dp)
                        .background(Color.White.copy(alpha = 0.2f))
                        .align(Alignment.Center)
                ) {
                    Text(
                        text = "Align Target Here",
                        color = Color.White,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
            }
            CaptureStep.RECTIFY -> {
                uiState.tempCaptureBitmap?.let { bitmap ->
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Captured Target",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                }
            }
            CaptureStep.REVIEW -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.8f))
                ) {
                    uiState.tempCaptureBitmap?.let { bitmap ->
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "Review Extracted Target",
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(32.dp),
                            contentScale = ContentScale.Fit
                        )
                    }
                }
            }
            else -> {}
        }
    }
}

@Composable
fun TargetCreationOverlayUi(
    uiState: ArUiState,
    step: CaptureStep,
    onPrimaryAction: () -> Unit,
    onCancel: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // Titles/Instructions
        when (step) {
            CaptureStep.RECTIFY -> {
                Text(
                    text = "Drag corners to unwarp",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 16.dp)
                        .background(Color.Black.copy(alpha = 0.5f), MaterialTheme.shapes.small)
                        .padding(8.dp)
                )
            }
            CaptureStep.REVIEW -> {
                Text(
                    text = "Review Extracted Markings",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 16.dp)
                        .background(Color.Black.copy(alpha = 0.5f), MaterialTheme.shapes.small)
                        .padding(8.dp)
                )
            }
            else -> {}
        }

        // Bottom Controls
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilledTonalButton(
                onClick = onCancel,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Close, contentDescription = "Cancel")
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (step == CaptureStep.CAPTURE) "Cancel" else "Retake")
            }

            Spacer(modifier = Modifier.width(16.dp))

            Button(
                onClick = onPrimaryAction,
                modifier = Modifier.weight(1f)
            ) {
                when (step) {
                    CaptureStep.CAPTURE -> {
                        Icon(Icons.Default.Camera, contentDescription = "Capture")
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Capture")
                    }
                    CaptureStep.RECTIFY -> {
                        Icon(Icons.Default.Check, contentDescription = "Next")
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Next")
                    }
                    CaptureStep.REVIEW -> {
                        Icon(Icons.Default.Check, contentDescription = "Confirm")
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Save")
                    }
                    else -> {}
                }
            }
        }
    }
}