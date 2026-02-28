package com.hereliesaz.graffitixr.feature.ar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.common.model.ArUiState

@Composable
fun MappingScreen(
    viewModel: ArViewModel,
    hasCameraPermission: Boolean,
    modifier: Modifier = Modifier
) {
    val uiState: ArUiState by viewModel.uiState.collectAsState()

    Box(modifier = modifier.fillMaxSize()) {
        // Live camera feed as background
        if (hasCameraPermission) {
            CameraPreview(
                controller = rememberCameraController(),
                onPhotoCaptured = {},
                modifier = Modifier.fillMaxSize()
            )
        }
        // Status overlay — top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .align(Alignment.TopCenter),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TrackingStatusChip(
                state = uiState.trackingState,
                isScanning = uiState.isScanning
            )
            Text(
                text = "${uiState.pointCloudCount} pts",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White
            )
        }

        // Control buttons — bottom
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
                .align(Alignment.BottomCenter),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Scan toggle
                Button(
                    onClick = {
                        if (uiState.isScanning) viewModel.stopScanning()
                        else viewModel.startScanning()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (uiState.isScanning) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(
                        imageVector = if (uiState.isScanning) Icons.Filled.Stop
                        else Icons.Filled.FiberManualRecord,
                        contentDescription = if (uiState.isScanning) "Stop scanning" else "Start scanning"
                    )
                    Text(
                        text = if (uiState.isScanning) "Stop" else "Scan",
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }

                // Keyframe capture
                FilledTonalIconButton(
                    onClick = { viewModel.captureKeyframe() },
                    enabled = uiState.isScanning
                ) {
                    Icon(
                        imageVector = Icons.Filled.CameraAlt,
                        contentDescription = "Capture keyframe"
                    )
                }

                // Flashlight toggle
                FilledTonalIconButton(onClick = { viewModel.toggleFlashlight() }) {
                    Icon(
                        imageVector = if (uiState.isFlashlightOn) Icons.Filled.FlashOn
                        else Icons.Filled.FlashOff,
                        contentDescription = if (uiState.isFlashlightOn) "Flashlight on" else "Flashlight off"
                    )
                }
            }
        }
    }
}

@Composable
private fun TrackingStatusChip(state: String, isScanning: Boolean) {
    val (chipColor, textColor) = when {
        !isScanning -> Pair(Color.Gray.copy(alpha = 0.6f), Color.White)
        state == "Tracking" -> Pair(Color(0xFF4CAF50).copy(alpha = 0.8f), Color.White)
        state == "Initializing" -> Pair(Color(0xFFFF9800).copy(alpha = 0.8f), Color.White)
        else -> Pair(Color(0xFFF44336).copy(alpha = 0.8f), Color.White)
    }
    Box(
        modifier = Modifier
            .background(chipColor, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(text = state, style = MaterialTheme.typography.labelSmall, color = textColor)
    }
}
