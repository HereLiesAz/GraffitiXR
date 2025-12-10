package com.hereliesaz.graffitixr.composables

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.CaptureStep

@Composable
fun TargetCreationOverlay(
    step: CaptureStep,
    qualityWarning: String?,
    onCaptureClick: () -> Unit,
    onCancelClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Top instruction bar
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.6f), shape = MaterialTheme.shapes.medium)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Target Creation: Step ${step.ordinal + 1}/5",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = getInstructionText(step),
                color = Color.White,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyLarge
            )
            if (qualityWarning != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = qualityWarning,
                    color = Color.Red,
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Cancel button
        IconButton(
            onClick = onCancelClick,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
        ) {
            Icon(Icons.Default.Close, contentDescription = "Cancel", tint = Color.White)
        }

        // Capture button
        IconButton(
            onClick = onCaptureClick,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp)
                .size(80.dp)
                .background(Color.White.copy(alpha = 0.3f), CircleShape)
                .border(2.dp, Color.White, CircleShape)
        ) {
            Icon(
                imageVector = Icons.Default.CameraAlt,
                contentDescription = "Capture",
                tint = Color.White,
                modifier = Modifier.size(40.dp)
            )
        }
    }
}

private fun getInstructionText(step: CaptureStep): String {
    return when (step) {
        CaptureStep.FRONT -> "Stand directly in front of the target surface."
        CaptureStep.LEFT -> "Take a step to the LEFT, keeping the target in view."
        CaptureStep.RIGHT -> "Take a step to the RIGHT from the center."
        CaptureStep.UP -> "Aim slightly DOWNWARD from a higher angle."
        CaptureStep.DOWN -> "Aim slightly UPWARD from a lower angle."
        CaptureStep.REVIEW -> "Processing..."
    }
}