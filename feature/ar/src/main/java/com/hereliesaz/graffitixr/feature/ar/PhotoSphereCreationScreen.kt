package com.hereliesaz.graffitixr.feature.ar

import android.app.Activity
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.ar.core.Session.FeatureMapQuality
import com.hereliesaz.aznavrail.AzButton
import com.hereliesaz.aznavrail.model.AzButtonShape

@Composable
fun PhotoSphereCreationScreen(
    isRightHanded: Boolean,
    currentQuality: FeatureMapQuality,
    isHosting: Boolean,
    onCaptureComplete: () -> Unit,
    onExit: () -> Unit
) {
    // Postmodern UI: Dark, minimal, informative.
    Box(modifier = Modifier.fillMaxSize()) {

        // The HUD
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth(0.8f)
                .background(Color(0xCC000000), RoundedCornerShape(12.dp))
                .padding(24.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "NEURAL SCANNING",
                    color = Color.White,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Orbit the target. Feed the algorithm.\nWait for quality to reach SUFFICIENT.",
                    color = Color.LightGray,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 24.dp)
                )

                // Quality Meter
                val qualityFloat = when (currentQuality) {
                    FeatureMapQuality.INSUFFICIENT -> 0.1f
                    FeatureMapQuality.SUFFICIENT -> 0.6f
                    FeatureMapQuality.GOOD -> 1.0f
                    else -> 0f
                }

                val animatedProgress by animateFloatAsState(targetValue = qualityFloat, label = "Quality")

                LinearProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                    color = if (currentQuality == FeatureMapQuality.GOOD) Color.Green else Color.Yellow,
                    trackColor = Color.DarkGray,
                )

                Text(
                    text = "MAP QUALITY: $currentQuality",
                    color = if (currentQuality == FeatureMapQuality.GOOD) Color.Green else Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }

        // Controls
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp)
                .fillMaxWidth()
                .padding(horizontal = 32.dp),
            horizontalArrangement = if (isRightHanded) Arrangement.spacedBy(16.dp, Alignment.Start) else Arrangement.spacedBy(16.dp, Alignment.End)
        ) {
            if (isRightHanded) {
                // Left-handed layout (User holds with Left hand, controls on Left)
                // Finalize first (Leftmost -> Thumb)
                if (currentQuality != FeatureMapQuality.INSUFFICIENT || isHosting) {
                    AzButton(
                        text = if (isHosting) "Uploading..." else "Finalize Map",
                        shape = AzButtonShape.RECTANGLE,
                        onClick = onCaptureComplete,
                    )
                }
                AzButton(
                    text = "Abort",
                    shape = AzButtonShape.RECTANGLE,
                    onClick = onExit
                )
            } else {
                // Right-handed layout (User holds with Right hand, controls on Right)
                // Abort first (Leftmost), Finalize last (Rightmost -> Thumb)
                AzButton(
                    text = "Abort",
                    shape = AzButtonShape.RECTANGLE,
                    onClick = onExit
                )
                if (currentQuality != FeatureMapQuality.INSUFFICIENT || isHosting) {
                    AzButton(
                        text = if (isHosting) "Uploading..." else "Finalize Map",
                        shape = AzButtonShape.RECTANGLE,
                        onClick = onCaptureComplete,
                    )
                }
            }
        }
    }
}