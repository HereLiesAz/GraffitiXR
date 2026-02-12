package com.hereliesaz.graffitixr.feature.ar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun TargetCreationOverlay(
    uiState: ArUiState,
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
                    text = "Capture target",
                    color = Color.White
                )
            }
            // Spacer to balance the row
            Spacer(modifier = Modifier.size(48.dp))
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
        }
    }
}
