package com.hereliesaz.graffitixr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.common.model.EditorMode
import kotlinx.coroutines.delay

private data class WelcomeContent(val title: String, val bullets: List<String>)

private fun contentFor(mode: EditorMode): WelcomeContent = when (mode) {
    EditorMode.AR -> WelcomeContent(
        title = "AR Projection",
        bullets = listOf(
            "Point at a wall with visible texture (paint, brick, posters)",
            "Just tap the screen — it captures and locks automatically",
            "Blank walls and smooth concrete won't track well"
        )
    )
    EditorMode.OVERLAY -> WelcomeContent(
        title = "Live Overlay",
        bullets = listOf(
            "Your design floats over the live camera view",
            "Tap Design \u2192 Image to add your artwork",
            "Pinch and drag to scale and position it"
        )
    )
    EditorMode.MOCKUP -> WelcomeContent(
        title = "Photo Mockup",
        bullets = listOf(
            "Preview your design on a photo before painting",
            "Tap Design \u2192 Wall to add a wall photo",
            "Then tap Design \u2192 Image to add your artwork on top"
        )
    )
    EditorMode.TRACE -> WelcomeContent(
        title = "Lightbox Mode",
        bullets = listOf(
            "The screen lights up bright \u2014 lay paper over it and trace",
            "Triple-tap anywhere to exit when you're done",
            "Brightness is controlled by your device's display brightness"
        )
    )
    else -> WelcomeContent(
        title = "Getting Started",
        bullets = listOf(
            "Tap Design \u2192 Image to add artwork",
            "Use the rail on the side to switch modes",
            "Tap the Help button for more details on any tool"
        )
    )
}

@Composable
fun ModeWelcomeCard(
    mode: EditorMode,
    onShowHelp: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val content = contentFor(mode)

    LaunchedEffect(mode) {
        delay(12_000L)
        onDismiss()
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.Black.copy(alpha = 0.82f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = content.title,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(8.dp))
            content.bullets.forEach { bullet ->
                Text(
                    text = "\u2022 $bullet",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.85f)
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                OutlinedButton(onClick = {
                    onShowHelp()
                    onDismiss()
                }) {
                    Text("Show Help")
                }
                Spacer(modifier = Modifier.padding(horizontal = 8.dp))
                Button(onClick = onDismiss) {
                    Text("Got it")
                }
            }
        }
    }
}
