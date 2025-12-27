package com.hereliesaz.graffitixr.composables

import androidx.compose.foundation.Canvas
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

/**
 * Constants for the Navigation Rail layout used for Help Overlay alignment.
 * These match the visual style of the AzNavRail library.
 */
object RailConstants {
    val Width = 80.dp
    val HeaderHeight = 110.dp // Approximate height of Logo/Header area
    val ItemHeight = 65.dp // Approximate height of a packed rail item
    val DividerHeight = 0.dp // No divider in packed mode
}

@Composable
fun HelpOverlay(
    railTop: Float, // The Y position of the Rail container in pixels
    onDismiss: () -> Unit
) {
    var currentContext by remember { mutableStateOf(HelpContext.INTRO) }
    val density = LocalDensity.current

    // Convert pixel railTop to DP
    val railTopDp = with(density) { railTop.toDp() }

    // Calculate dynamic positions relative to the screen top
    // Since we force the rail to be packed in Help Mode (Modes, Design, Settings),
    // we can predict their positions relative to the rail top.

    // Modes is the first item after the header
    val modesButtonY = railTopDp + RailConstants.HeaderHeight

    // Design is immediately after Modes
    val designButtonY = modesButtonY + RailConstants.ItemHeight

    // Settings is immediately after Design
    val settingsButtonY = designButtonY + RailConstants.ItemHeight

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
                .size(width = RailConstants.Width, height = RailConstants.ItemHeight)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { currentContext = HelpContext.MODES }
        )

        // Design Button Detector
        Box(
            modifier = Modifier
                .offset(y = designButtonY)
                .size(width = RailConstants.Width, height = RailConstants.ItemHeight)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { currentContext = HelpContext.DESIGN }
        )

        // Settings Button Detector
        Box(
            modifier = Modifier
                .offset(y = settingsButtonY)
                .size(width = RailConstants.Width, height = RailConstants.ItemHeight)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { currentContext = HelpContext.SETTINGS }
        )

        // Content Area
        // We remove the strict padding here to allow full screen placement
        Box(
            modifier = Modifier
                .fillMaxSize()
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
    // Main Welcome Title
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 120.dp, top = 64.dp), // Offset from rail
        contentAlignment = Alignment.TopStart
    ) {
        Column {
            Text(
                text = "WELCOME TO GRAFFITIXR!",
                style = MaterialTheme.typography.headlineLarge,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Tap the navigation buttons to learn more.",
                style = MaterialTheme.typography.bodyLarge,
                color = Color.Gray
            )
        }
    }

    // Individual Callouts
    HelpCallout(
        targetY = modesY,
        text = "CHOOSE MODE (AR, OVERLAY...)"
    )

    HelpCallout(
        targetY = designY,
        text = "DESIGN & EDIT TOOLS"
    )

    HelpCallout(
        targetY = settingsY,
        text = "PROJECT SETTINGS"
    )
}

@Composable
fun HelpCallout(
    targetY: Dp,
    text: String,
    railWidth: Dp = RailConstants.Width,
    itemHeight: Dp = RailConstants.ItemHeight
) {
    val density = LocalDensity.current
    val strokeWidth = 3.dp

    // Calculate the center Y of the target button
    val buttonCenterY = targetY + (itemHeight / 2)

    // Layout the text to the right of the rail
    Box(
        modifier = Modifier
            .offset(x = railWidth + 60.dp, y = buttonCenterY - 12.dp) // Align text vertically roughly center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            color = Color.Cyan,
            fontWeight = FontWeight.Bold
        )
    }

    // Draw Arrow
    Canvas(modifier = Modifier.fillMaxSize()) {
        val targetYPx = with(density) { buttonCenterY.toPx() }
        val startXPx = with(density) { (railWidth + 50.dp).toPx() } // Start near text
        val endXPx = with(density) { railWidth.toPx() } // End at rail edge

        // Draw horizontal line
        drawLine(
            color = Color.Cyan,
            start = Offset(startXPx, targetYPx),
            end = Offset(endXPx, targetYPx),
            strokeWidth = strokeWidth.toPx(),
            cap = StrokeCap.Round
        )

        // Draw Arrowhead at the rail end
        val arrowSize = 15f
        val path = Path().apply {
            moveTo(endXPx, targetYPx)
            lineTo(endXPx + arrowSize, targetYPx - arrowSize)
            lineTo(endXPx + arrowSize, targetYPx + arrowSize)
            close()
        }
        drawPath(path, Color.Cyan)
    }
}

@Composable
fun ModesHelp(modesY: Dp) {
    val density = LocalDensity.current
    val buttonCenterY = modesY + (RailConstants.ItemHeight / 2)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = RailConstants.Width + 40.dp, top = modesY),
        contentAlignment = Alignment.TopStart
    ) {
        Column {
            Text("Modes Menu", style = MaterialTheme.typography.headlineMedium, color = Color.Cyan)
            Spacer(modifier = Modifier.height(8.dp))
            Text("• AR Mode: View artwork on walls.", color = Color.White)
            Text("• Overlay: Use a static photo background.", color = Color.White)
            Text("• Mockup: Place art on a solid color.", color = Color.White)
            Text("• Trace: Lock screen for physical tracing.", color = Color.White)
        }
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
        val targetY = with(density) { buttonCenterY.toPx() }
        val startX = with(density) { (RailConstants.Width + 30.dp).toPx() }
        val endX = with(density) { RailConstants.Width.toPx() }

        drawLine(
            color = Color.Cyan,
            start = Offset(startX, targetY),
            end = Offset(endX, targetY),
            strokeWidth = 5f,
            cap = StrokeCap.Round
        )
    }
}

@Composable
fun DesignHelp(designY: Dp) {
    val density = LocalDensity.current
    val buttonCenterY = designY + (RailConstants.ItemHeight / 2)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = RailConstants.Width + 40.dp, top = designY),
        contentAlignment = Alignment.TopStart
    ) {
        Column {
            Text("Design Tools", style = MaterialTheme.typography.headlineMedium, color = Color.Cyan)
            Spacer(modifier = Modifier.height(8.dp))
            Text("• Open: Import images.", color = Color.White)
            Text("• Isolate: Remove background (AI).", color = Color.White)
            Text("• Outline: Convert to line art.", color = Color.White)
            Text("• Adjust: Opacity, Scale, Color.", color = Color.White)
        }
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
        val targetY = with(density) { buttonCenterY.toPx() }
        val startX = with(density) { (RailConstants.Width + 30.dp).toPx() }
        val endX = with(density) { RailConstants.Width.toPx() }

        drawLine(
            color = Color.Cyan,
            start = Offset(startX, targetY),
            end = Offset(endX, targetY),
            strokeWidth = 5f,
            cap = StrokeCap.Round
        )
    }
}

@Composable
fun SettingsHelp(settingsY: Dp, onGetStarted: () -> Unit) {
    val density = LocalDensity.current
    val buttonCenterY = settingsY + (RailConstants.ItemHeight / 2)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = RailConstants.Width + 40.dp, top = settingsY),
        contentAlignment = Alignment.TopStart
    ) {
        Column {
            Text("Project & Settings", style = MaterialTheme.typography.headlineMedium, color = Color.Cyan)
            Spacer(modifier = Modifier.height(8.dp))
            Text("• Save/Load Projects", color = Color.White)
            Text("• Export to Zip", color = Color.White)
            Text("• Help & Permissions", color = Color.White)

            Spacer(modifier = Modifier.height(32.dp))
            Button(onClick = onGetStarted) {
                Text("Got it, let's start!")
            }
        }
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
        val targetY = with(density) { buttonCenterY.toPx() }
        val startX = with(density) { (RailConstants.Width + 30.dp).toPx() }
        val endX = with(density) { RailConstants.Width.toPx() }

        drawLine(
            color = Color.Cyan,
            start = Offset(startX, targetY),
            end = Offset(endX, targetY),
            strokeWidth = 5f,
            cap = StrokeCap.Round
        )
    }
}
