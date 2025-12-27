package com.hereliesaz.graffitixr.composables

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

enum class HelpContext {
    INTRO,
    MODES,
    DESIGN,
    SETTINGS
}

@Composable
fun HelpOverlay(
    onDismiss: () -> Unit
) {
    var currentContext by remember { mutableStateOf(HelpContext.INTRO) }

    // Hardcoded estimated positions for the rail items (assuming ~80dp width rail)
    // Adjust these Y offsets based on the visual layout of AzNavRail
    val modesButtonY = 100.dp
    val designButtonY = 250.dp // Approximate
    val settingsButtonY = 400.dp // Approximate

    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(100f) // Ensure it's on top
    ) {
        // Transparent Detectors to intercept clicks over Rail areas
        // Modes Button Detector
        Box(
            modifier = Modifier
                .offset(y = modesButtonY)
                .size(width = 80.dp, height = 60.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { currentContext = HelpContext.MODES }
        )

        // Design Button Detector
        Box(
            modifier = Modifier
                .offset(y = designButtonY)
                .size(width = 80.dp, height = 60.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { currentContext = HelpContext.DESIGN }
        )

        // Settings Button Detector
        Box(
            modifier = Modifier
                .offset(y = settingsButtonY)
                .size(width = 80.dp, height = 60.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { currentContext = HelpContext.SETTINGS }
        )

        // Content Area
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 100.dp, end = 32.dp, top = 64.dp, bottom = 64.dp)
        ) {
            when (currentContext) {
                HelpContext.INTRO -> IntroHelp(
                    modesY = modesButtonY,
                    designY = designButtonY,
                    settingsY = settingsButtonY
                )
                HelpContext.MODES -> ModesHelp(modesY = modesButtonY)
                HelpContext.DESIGN -> DesignHelp(designY = designButtonY)
                HelpContext.SETTINGS -> SettingsHelp(settingsY = settingsButtonY, onGetStarted = onDismiss)
            }
        }
    }
}

@Composable
fun IntroHelp(modesY: Dp, designY: Dp, settingsY: Dp) {
    val density = LocalDensity.current
    val strokeWidth = 4.dp

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
    ) {
        Text(
            text = "Welcome to GraffitiXR!",
            style = MaterialTheme.typography.headlineLarge,
            color = Color.White,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Use the Navigation Rail on the left to switch tools.",
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White
        )
        Spacer(modifier = Modifier.height(32.dp))
        Text(
            text = "Tap any button to see details.",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.Gray
        )
    }

    // Draw Arrows
    Canvas(modifier = Modifier.fillMaxSize()) {
        val centerX = size.width / 2
        val startY = size.height / 2 + 50f

        val modesTargetY = with(density) { modesY.toPx() } + 40f
        val designTargetY = with(density) { designY.toPx() } + 40f
        val settingsTargetY = with(density) { settingsY.toPx() } + 40f

        // Helper to draw connector
        fun drawConnector(targetY: Float) {
            val path = Path().apply {
                moveTo(centerX, startY)
                lineTo(centerX, targetY)
                lineTo(0f, targetY) // Point to rail
            }

            drawPath(
                path = path,
                color = Color.Cyan,
                style = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Square)
            )

            // Arrowhead
            val arrowSize = 20f
            drawPath(
                path = Path().apply {
                    moveTo(0f, targetY)
                    lineTo(arrowSize, targetY - arrowSize)
                    lineTo(arrowSize, targetY + arrowSize)
                    close()
                },
                color = Color.Cyan
            )
        }

        drawConnector(modesTargetY)
        drawConnector(designTargetY)
        drawConnector(settingsTargetY)
    }
}

@Composable
fun ModesHelp(modesY: Dp) {
    val density = LocalDensity.current

    Column(modifier = Modifier.fillMaxSize()) {
        Spacer(modifier = Modifier.height(modesY))
        Text("Modes Menu", style = MaterialTheme.typography.titleLarge, color = Color.Cyan)
        Text("• AR Mode: View artwork on walls.", color = Color.White)
        Text("• Overlay: Use a static photo background.", color = Color.White)
        Text("• Mockup: Place art on a solid color.", color = Color.White)
        Text("• Trace: Lock screen for physical tracing.", color = Color.White)
    }

    // Specific arrow pointing to Modes button
    Canvas(modifier = Modifier.fillMaxSize()) {
        val targetY = with(density) { modesY.toPx() } + 40f
        drawLine(
            color = Color.Cyan,
            start = Offset(200f, targetY),
            end = Offset(0f, targetY),
            strokeWidth = 5f
        )
    }
}

@Composable
fun DesignHelp(designY: Dp) {
    val density = LocalDensity.current

    Column(modifier = Modifier.fillMaxSize()) {
        Spacer(modifier = Modifier.height(designY))
        Text("Design Tools", style = MaterialTheme.typography.titleLarge, color = Color.Cyan)
        Text("• Open: Import images.", color = Color.White)
        Text("• Isolate: Remove background (AI).", color = Color.White)
        Text("• Outline: Convert to line art.", color = Color.White)
        Text("• Adjust: Opacity, Scale, Color.", color = Color.White)
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
        val targetY = with(density) { designY.toPx() } + 40f
        drawLine(
            color = Color.Cyan,
            start = Offset(200f, targetY),
            end = Offset(0f, targetY),
            strokeWidth = 5f
        )
    }
}

@Composable
fun SettingsHelp(settingsY: Dp, onGetStarted: () -> Unit) {
    val density = LocalDensity.current

    Column(modifier = Modifier.fillMaxSize()) {
        Spacer(modifier = Modifier.height(settingsY))
        Text("Project & Settings", style = MaterialTheme.typography.titleLarge, color = Color.Cyan)
        Text("• Save/Load Projects", color = Color.White)
        Text("• Export to Zip", color = Color.White)
        Text("• Help & Permissions", color = Color.White)

        Spacer(modifier = Modifier.height(32.dp))
        Button(onClick = onGetStarted) {
            Text("Got it, let's start!")
        }
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
        val targetY = with(density) { settingsY.toPx() } + 40f
        drawLine(
            color = Color.Cyan,
            start = Offset(200f, targetY),
            end = Offset(0f, targetY),
            strokeWidth = 5f
        )
    }
}
