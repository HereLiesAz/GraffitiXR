package com.hereliesaz.graffitixr.composables

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.geometry.Rect
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

@Composable
fun HelpOverlay(
    itemPositions: Map<String, Rect>,
    onDismiss: () -> Unit
) {
    var currentContext by remember { mutableStateOf(HelpContext.INTRO) }

    // Fallback if positions aren't ready
    val modesRect = itemPositions["mode_host"] ?: Rect.Zero
    val designRect = itemPositions["design_host"] ?: Rect.Zero
    val settingsRect = itemPositions["settings_host"] ?: Rect.Zero

    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(100f) // Ensure it's on top
    ) {
        // Transparent Detectors to intercept clicks over Rail areas
        if (!modesRect.isEmpty) {
            ClickableRect(rect = modesRect) { currentContext = HelpContext.MODES }
        }
        if (!designRect.isEmpty) {
            ClickableRect(rect = designRect) { currentContext = HelpContext.DESIGN }
        }
        if (!settingsRect.isEmpty) {
            ClickableRect(rect = settingsRect) { currentContext = HelpContext.SETTINGS }
        }

        // Content Area
        Box(
            modifier = Modifier
                .fillMaxSize()
        ) {
            when (currentContext) {
                HelpContext.INTRO -> IntroHelp(
                    modesRect = modesRect,
                    designRect = designRect,
                    settingsRect = settingsRect
                )
                HelpContext.MODES -> ModesHelp(targetRect = modesRect)
                HelpContext.DESIGN -> DesignHelp(targetRect = designRect)
                HelpContext.SETTINGS -> SettingsHelp(targetRect = settingsRect, onGetStarted = onDismiss)
            }
        }
    }
}

@Composable
fun ClickableRect(rect: Rect, onClick: () -> Unit) {
    val density = LocalDensity.current
    val topDp = with(density) { rect.top.toDp() }
    val leftDp = with(density) { rect.left.toDp() }
    val widthDp = with(density) { rect.width.toDp() }
    val heightDp = with(density) { rect.height.toDp() }

    Box(
        modifier = Modifier
            .offset(x = leftDp, y = topDp)
            .width(widthDp)
            .height(heightDp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
    )
}

@Composable
fun IntroHelp(modesRect: Rect, designRect: Rect, settingsRect: Rect) {
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

    if (!modesRect.isEmpty) {
        HelpCallout(targetRect = modesRect, text = "CHOOSE MODE")
    }
    if (!designRect.isEmpty) {
        HelpCallout(targetRect = designRect, text = "DESIGN TOOLS")
    }
    if (!settingsRect.isEmpty) {
        HelpCallout(targetRect = settingsRect, text = "SETTINGS")
    }
}

@Composable
fun HelpCallout(
    targetRect: Rect,
    text: String
) {
    val density = LocalDensity.current

    // Layout the text to the right of the button
    val textLeftDp = with(density) { (targetRect.right + 60f).toDp() }
    val textTopDp = with(density) { (targetRect.center.y - 12f).toDp() } // Approximate centering

    Box(
        modifier = Modifier
            .offset(x = textLeftDp, y = textTopDp)
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
        val startX = targetRect.right + 50f // Near text
        val endX = targetRect.right
        val y = targetRect.center.y

        // Draw horizontal line
        drawLine(
            color = Color.Cyan,
            start = Offset(startX, y),
            end = Offset(endX, y),
            strokeWidth = 3.dp.toPx(),
            cap = StrokeCap.Round
        )

        // Draw Arrowhead
        val arrowSize = 15f
        val path = Path().apply {
            moveTo(endX, y)
            lineTo(endX + arrowSize, y - arrowSize)
            lineTo(endX + arrowSize, y + arrowSize)
            close()
        }
        drawPath(path, Color.Cyan)
    }
}

@Composable
fun ModesHelp(targetRect: Rect) {
    if (targetRect.isEmpty) return
    val density = LocalDensity.current
    val topDp = with(density) { targetRect.top.toDp() }
    val leftDp = with(density) { (targetRect.right + 40f).toDp() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = leftDp, top = topDp),
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

    DrawSelectionIndicator(targetRect)
}

@Composable
fun DesignHelp(targetRect: Rect) {
    if (targetRect.isEmpty) return
    val density = LocalDensity.current
    val topDp = with(density) { targetRect.top.toDp() }
    val leftDp = with(density) { (targetRect.right + 40f).toDp() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = leftDp, top = topDp),
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

    DrawSelectionIndicator(targetRect)
}

@Composable
fun SettingsHelp(targetRect: Rect, onGetStarted: () -> Unit) {
    if (targetRect.isEmpty) return
    val density = LocalDensity.current
    val topDp = with(density) { targetRect.top.toDp() }
    val leftDp = with(density) { (targetRect.right + 40f).toDp() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = leftDp, top = topDp),
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

    DrawSelectionIndicator(targetRect)
}

@Composable
fun DrawSelectionIndicator(targetRect: Rect) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val startX = targetRect.right + 30f
        val endX = targetRect.right
        val y = targetRect.center.y

        drawLine(
            color = Color.Cyan,
            start = Offset(startX, y),
            end = Offset(endX, y),
            strokeWidth = 5f,
            cap = StrokeCap.Round
        )
    }
}
