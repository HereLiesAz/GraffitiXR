package com.hereliesaz.graffitixr.feature.ar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.common.model.CaptureStep
import com.hereliesaz.graffitixr.common.model.TargetCreationMode
import com.hereliesaz.graffitixr.common.model.UiState

@Composable
fun TargetCreationOverlay(
    uiState: UiState,
    onCapture: () -> Unit,
    onConfirm: () -> Unit,
    onRetake: () -> Unit,
    onCancel: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // Top Bar: Instructions & Cancel
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .align(Alignment.TopCenter),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onCancel) {
                Icon(Icons.Default.Close, contentDescription = "Cancel", tint = Color.White)
            }

            Box(
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.6f), MaterialTheme.shapes.medium)
                    .padding(8.dp)
            ) {
                Text(
                    text = when (uiState.targetCreationMode) {
                        TargetCreationMode.SINGLE_IMAGE -> "Capture flat target"
                        TargetCreationMode.GUIDED_GRID -> "Align grid with wall"
                        else -> "Calibrate target"
                    },
                    color = Color.White
                )
            }
            // Spacer to balance the row
            Spacer(modifier = Modifier.size(48.dp))
        }

        // Guide Overlay (viewfinder box)
        if (uiState.captureStep == CaptureStep.CAPTURE) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(48.dp)
                    .align(Alignment.Center)
                    .background(Color.Transparent)
            ) {
                // In a future update, draw a viewfinder outline here.
                // Currently relies on the AR camera feed behind this overlay.
            }
        }

        // Bottom Bar: Controls
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp)
                .align(Alignment.BottomCenter),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (uiState.captureStep == CaptureStep.PREVIEW || uiState.captureStep == CaptureStep.CAPTURE) {
                // Shutter Button
                Button(
                    onClick = onCapture,
                    shape = CircleShape,
                    modifier = Modifier.size(80.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White)
                ) {
                    Icon(
                        imageVector = Icons.Default.Camera,
                        contentDescription = "Capture",
                        tint = Color.Black,
                        modifier = Modifier.size(40.dp)
                    )
                }
            } else if (uiState.captureStep == CaptureStep.CONFIRM) {
                // Confirm/Retake
                IconButton(onClick = onRetake, modifier = Modifier.size(64.dp)) {
                    Icon(Icons.Default.Refresh, "Retake", tint = Color.White, modifier = Modifier.size(32.dp))
                }

                Button(onClick = onConfirm) {
                    Text("Confirm Target")
                }
            }
        }
    }
}