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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import com.hereliesaz.graffitixr.TargetCreationMode

@Composable
fun TargetCreationOverlay(
    step: CaptureStep,
    targetCreationMode: TargetCreationMode = TargetCreationMode.CAPTURE,
    gridRows: Int = 2,
    gridCols: Int = 2,
    qualityWarning: String?,
    captureFailureTimestamp: Long, // Used to trigger red glow
    onCaptureClick: () -> Unit,
    onCancelClick: () -> Unit,
    onMethodSelected: (TargetCreationMode) -> Unit = {},
    onGridConfigChanged: (Int, Int) -> Unit = { _, _ -> },
    onGpsDecision: (Boolean) -> Unit = {},
    onFinishPhotoSequence: () -> Unit = {}
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
                    text = "Target Creation",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = getInstructionText(step),
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.semantics {
                        liveRegion = LiveRegionMode.Polite
                    }
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

            // Center Content (Method Selection)
            if (step == CaptureStep.CHOOSE_METHOD) {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    MethodButton(text = "Capture Existing Marks", onClick = { onMethodSelected(TargetCreationMode.CAPTURE) })
                    MethodButton(text = "Rectify Image (Planar Target)", onClick = { onMethodSelected(TargetCreationMode.RECTIFY) })
                    MethodButton(text = "Create Guided Grid", onClick = { onMethodSelected(TargetCreationMode.GUIDED_GRID) })
                    MethodButton(text = "Create Guided Points", onClick = { onMethodSelected(TargetCreationMode.GUIDED_POINTS) })
                    MethodButton(text = "Multi-Point Calibration", onClick = { onMethodSelected(TargetCreationMode.MULTI_POINT_CALIBRATION) })
                }
            }

            // Ask GPS
            if (step == CaptureStep.ASK_GPS) {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .background(Color.Black.copy(alpha = 0.8f), RoundedCornerShape(16.dp))
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "Mark location with GPS?",
                        color = Color.White,
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = "This helps with relocalization outdoors.",
                        color = Color.LightGray,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Button(onClick = { onGpsDecision(true) }) {
                            Text("Yes")
                        }
                        Button(
                            onClick = { onGpsDecision(false) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
                        ) {
                            Text("No")
                        }
                    }
                }
            }

            // Grid Config (Number Selectors)
            if (step == CaptureStep.GRID_CONFIG) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 100.dp)
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Grid Size", color = Color.White, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        NumberSelector(value = gridRows, onValueChange = { onGridConfigChanged(it, gridCols) }, label = "Rows")
                        Text("X", color = Color.White, fontWeight = FontWeight.Bold)
                        NumberSelector(value = gridCols, onValueChange = { onGridConfigChanged(gridRows, it) }, label = "Cols")
                    }
                }
            }

            // Photo Sequence Finish Button
            if (step == CaptureStep.PHOTO_SEQUENCE) {
                Button(
                    onClick = onFinishPhotoSequence,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(bottom = 32.dp, end = 32.dp)
                ) {
                    Text("Done")
                }
            }

            // Flashing Arrow Indicator (For Capture Steps)
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
            // Show only for relevant steps
            if (step != CaptureStep.ADVICE && step != CaptureStep.CHOOSE_METHOD) {
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
                    val icon = when(step) {
                         CaptureStep.GRID_CONFIG -> Icons.Default.Check
                         CaptureStep.GUIDED_CAPTURE -> Icons.Default.CameraAlt
                         CaptureStep.INSTRUCTION -> null // Uses text "START"
                         CaptureStep.PHOTO_SEQUENCE -> Icons.Default.CameraAlt
                         else -> Icons.Default.CameraAlt
                    }

                    if (step == CaptureStep.INSTRUCTION) {
                         Text(
                            text = "START",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelLarge
                        )
                    } else if (icon != null) {
                        Icon(
                            imageVector = icon,
                            contentDescription = "Capture",
                            tint = Color.White,
                            modifier = Modifier.size(40.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MethodButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.2f)),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(0.7f).semantics {
            contentDescription = "Select method: $text"
        }
    ) {
        Text(text, color = Color.White, modifier = Modifier.padding(8.dp))
    }
}

@Composable
fun NumberSelector(value: Int, onValueChange: (Int) -> Unit, label: String) {
    val canDecrease = value > 1
    val canIncrease = value < 10

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            color = Color.Gray,
            style = MaterialTheme.typography.labelSmall
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = { if (canDecrease) onValueChange(value - 1) },
                enabled = canDecrease
            ) {
                Icon(
                    imageVector = Icons.Default.Remove,
                    contentDescription = "Decrease $label",
                    tint = if (canDecrease) Color.White else Color.White.copy(alpha = 0.38f)
                )
            }
            Text(
                text = "$value",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .width(32.dp)
                    .semantics {
                        contentDescription = "$value $label"
                    },
                textAlign = TextAlign.Center
            )
            IconButton(
                onClick = { if (canIncrease) onValueChange(value + 1) },
                enabled = canIncrease
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Increase $label",
                    tint = if (canIncrease) Color.White else Color.White.copy(alpha = 0.38f)
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
        CaptureStep.LEFT -> Icons.AutoMirrored.Filled.ArrowBack // Pointing Left
        CaptureStep.RIGHT -> Icons.AutoMirrored.Filled.ArrowForward // Pointing Right
        CaptureStep.UP -> Icons.Default.ArrowUpward // Pointing Up
        CaptureStep.DOWN -> Icons.Default.ArrowDownward // Pointing Down
        else -> null
    }
}

private fun getArrowDescriptionForStep(step: CaptureStep): String {
    return when (step) {
        CaptureStep.LEFT -> "Move Left"
        CaptureStep.RIGHT -> "Move Right"
        CaptureStep.UP -> "Move Up"
        CaptureStep.DOWN -> "Move Down"
        else -> ""
    }
}

private fun getInstructionText(step: CaptureStep): String {
    return when (step) {
        CaptureStep.ADVICE -> "Find a well-lit surface.\nUse the 'Light' button if needed.\nTap the center of the wall to place an anchor."
        CaptureStep.CHOOSE_METHOD -> "Choose how to create your target."
        CaptureStep.GRID_CONFIG -> "Configure the grid size."
        CaptureStep.GUIDED_CAPTURE -> "Draw the marks on the wall following the guide.\nPress SCAN when done."
        CaptureStep.INSTRUCTION -> "Move device slowly to scan surfaces.\nPress START when ready."
        CaptureStep.FRONT -> "Stand directly in front of the target surface."
        CaptureStep.LEFT -> "Take a step to the LEFT."
        CaptureStep.RIGHT -> "Take a step to the RIGHT."
        CaptureStep.UP -> "Aim slightly DOWNWARD from above."
        CaptureStep.DOWN -> "Aim slightly UPWARD from below."
        CaptureStep.REVIEW -> "Processing..."
        CaptureStep.ASK_GPS -> "Select GPS preference."
        CaptureStep.PHOTO_SEQUENCE -> "Take multiple photos of the area (min 3).\nMove around to capture different angles."
        CaptureStep.CALIBRATION_POINT_1 -> "Hold phone flat against wall at Spot 1.\nWait for vibration."
        CaptureStep.CALIBRATION_POINT_2 -> "Hold phone flat against wall at Spot 2.\nWait for vibration."
        CaptureStep.CALIBRATION_POINT_3 -> "Hold phone flat against wall at Spot 3.\nWait for vibration."
        CaptureStep.CALIBRATION_POINT_4 -> "Hold phone flat against wall at Spot 4.\nWait for vibration."
        CaptureStep.RECTIFY -> "Rectify the image."
    }
}
