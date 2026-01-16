package com.hereliesaz.graffitixr.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.EditorMode

@Composable
fun Knob(
    value: Float,
    onValueChange: (Float) -> Unit,
    text: String,
    range: ClosedFloatingPointRange<Float>? = null
) {
    var angle by remember { mutableStateOf(0f) }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(60.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(50.dp)
                .background(Color.DarkGray, CircleShape)
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        val sensitivity = 0.005f
                        val delta = -dragAmount.y * sensitivity

                        val newValue = if (range != null) {
                            (value + delta).coerceIn(range)
                        } else {
                            value + delta
                        }
                        onValueChange(newValue)
                        angle += dragAmount.x + dragAmount.y
                    }
                }
        ) {
            Box(
                modifier = Modifier
                    .size(4.dp, 16.dp)
                    .offset(y = (-12).dp)
                    .rotate(angle)
                    .background(Color.Cyan, CircleShape)
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            maxLines = 1
        )
    }
}

@Composable
fun AzNavRail(
    currentMode: EditorMode,
    onModeSelected: (EditorMode) -> Unit,
    onCapture: () -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier
) {
    NavigationRail(
        modifier = modifier,
        containerColor = Color.Black.copy(alpha = 0.8f),
        contentColor = Color.White
    ) {
        NavigationRailItem(
            selected = false,
            onClick = onMenu,
            icon = { Icon(Icons.Default.Menu, "Menu") }
        )

        Spacer(Modifier.weight(1f))

        EditorMode.values().forEach { mode ->
            val icon = when(mode) {
                EditorMode.AR -> Icons.Default.ViewInAr
                EditorMode.CROP -> Icons.Default.Crop
                EditorMode.ADJUST -> Icons.Default.Tune
                EditorMode.DRAW -> Icons.Default.Brush
                EditorMode.PROJECT -> Icons.Default.PermMedia
                // Ensure exhaustive when is handled
                else -> Icons.Default.Circle
            }

            NavigationRailItem(
                selected = currentMode == mode,
                onClick = { onModeSelected(mode) },
                icon = { Icon(icon, mode.name) },
                label = { Text(mode.name.take(1)) }
            )
        }

        Spacer(Modifier.weight(1f))

        FloatingActionButton(
            onClick = onCapture,
            containerColor = Color.White,
            contentColor = Color.Black,
            modifier = Modifier.padding(bottom = 16.dp)
        ) {
            Icon(Icons.Default.Camera, "Capture")
        }
    }
}