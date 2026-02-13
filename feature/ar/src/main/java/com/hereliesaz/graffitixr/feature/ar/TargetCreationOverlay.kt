package com.hereliesaz.graffitixr.feature.ar

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.common.model.ArUiState
import com.hereliesaz.graffitixr.common.model.CaptureStep

/**
 * An overlay UI for the Target Creation process.
 * Displays different controls based on the current [CaptureStep].
 *
 * @param uiState The current AR UI state.
 * @param step The current step in the target creation flow.
 * @param onPrimaryAction The primary button action (Capture, Rectify, Confirm).
 * @param onCancel The cancel/back button action.
 */
@Composable
fun TargetCreationOverlay(
    uiState: ArUiState,
    step: CaptureStep,
    onPrimaryAction: () -> Unit,
    onCancel: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Step-specific Content (Center/Background)
        when (step) {
            CaptureStep.CAPTURE -> {
                // Camera Reticle or Guidance
                Box(
                    modifier = Modifier
                        .size(200.dp)
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
                // Show captured bitmap if available
                uiState.tempCaptureBitmap?.let { bitmap ->
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Captured Target",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                }
                Text(
                    text = "Drag corners to unwarp",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 40.dp)
                        .background(Color.Black.copy(alpha = 0.5f))
                        .padding(8.dp)
                )
            }
            CaptureStep.REVIEW -> {
                // Show processed target
                uiState.tempCaptureBitmap?.let { bitmap ->
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Review Target",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                }
            }
            else -> {}
        }

        // Bottom Controls
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = 32.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Cancel / Retake Button
            FilledTonalButton(
                onClick = onCancel,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Close, contentDescription = "Cancel")
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (step == CaptureStep.CAPTURE) "Cancel" else "Retake")
            }

            Spacer(modifier = Modifier.width(16.dp))

            // Primary Action Button
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
