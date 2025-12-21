package com.hereliesaz.graffitixr.composables

import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.CaptureStep

@Composable
fun TargetCreationOverlay(
    step: CaptureStep,
    qualityWarning: String?,
    captureFailureTimestamp: Long, // Used to trigger red glow
    onCaptureClick: () -> Unit,
    onCancelClick: () -> Unit
) {
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize()
    ) {
        val maxHeight = maxHeight
        val verticalMargin = maxHeight * 0.1f

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = verticalMargin, bottom = verticalMargin)
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
                    Box(
                        modifier = Modifier
                            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = qualityWarning,
                            color = Color(0xFFFF5252), // Lighter red for visibility
                            textAlign = TextAlign.Center,
                            fontWeight = FontWeight.Bold
                        )
                    }
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

            // Flashing Arrow Indicator
            val arrowIcon = getArrowForStep(step)
            val arrowDescription = getArrowDescriptionForStep(step)
            if (arrowIcon != null) {
                FlashingArrow(
                    icon = arrowIcon,
                    contentDescription = arrowDescription,
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            // Capture button with Red Glow Animation
            val glowColor = remember { androidx.compose.animation.Animatable(Color.White.copy(alpha = 0.3f)) }

            LaunchedEffect(captureFailureTimestamp) {
                if (captureFailureTimestamp > 0) {
                    glowColor.animateTo(
                        targetValue = Color.Red,
                        animationSpec = tween(durationMillis = 100, easing = LinearEasing)
                    )
                    glowColor.animateTo(
                        targetValue = Color.White.copy(alpha = 0.3f),
                        animationSpec = tween(durationMillis = 500, easing = FastOutLinearInEasing)
                    )
                }
            }

            IconButton(
                onClick = onCaptureClick,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 32.dp)
                    .size(80.dp)
                    .background(glowColor.value, CircleShape)
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
}

@Composable
fun FlashingArrow(
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "ArrowFlash")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "Alpha"
    )

    Icon(
        imageVector = icon,
        contentDescription = contentDescription,
        tint = Color.Yellow.copy(alpha = alpha),
        modifier = modifier.size(120.dp)
    )
}

private fun getArrowForStep(step: CaptureStep): ImageVector? {
    return when (step) {
        CaptureStep.FRONT -> null
        CaptureStep.LEFT -> Icons.Default.ArrowBack // Pointing Left
        CaptureStep.RIGHT -> Icons.Default.ArrowForward // Pointing Right
        CaptureStep.UP -> Icons.Default.ArrowUpward // Pointing Up
        CaptureStep.DOWN -> Icons.Default.ArrowDownward // Pointing Down
        CaptureStep.REVIEW -> null
    }
}

private fun getArrowDescriptionForStep(step: CaptureStep): String {
    return when (step) {
        CaptureStep.FRONT -> ""
        CaptureStep.LEFT -> "Move Left"
        CaptureStep.RIGHT -> "Move Right"
        CaptureStep.UP -> "Move Up"
        CaptureStep.DOWN -> "Move Down"
        CaptureStep.REVIEW -> ""
    }
}

private fun getInstructionText(step: CaptureStep): String {
    return when (step) {
        CaptureStep.FRONT -> "Stand directly in front of the target surface."
        CaptureStep.LEFT -> "Take a step to the LEFT."
        CaptureStep.RIGHT -> "Take a step to the RIGHT."
        CaptureStep.UP -> "Aim slightly DOWNWARD from above."
        CaptureStep.DOWN -> "Aim slightly UPWARD from below."
        CaptureStep.REVIEW -> "Processing..."
    }
}