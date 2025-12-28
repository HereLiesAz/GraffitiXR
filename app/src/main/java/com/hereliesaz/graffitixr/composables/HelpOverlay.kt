package com.hereliesaz.graffitixr.composables

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

/**
 * An overlay that highlights specific UI elements (Navigation Rail items)
 * and displays help text next to them.
 *
 * @param itemPositions A map of rail item IDs to their screen-space bounding boxes (Rect).
 * @param onDismiss Callback invoked when the user taps the FAB to dismiss the help mode.
 */
@Composable
fun HelpOverlay(
    itemPositions: Map<String, Rect>,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.7f))
    ) {
        val density = LocalDensity.current

        // Draw highlights/connectors
        Canvas(modifier = Modifier.fillMaxSize()) {
            itemPositions.forEach { (_, rect) ->
                val center = rect.center

                // Draw a circle highlight around the rail item
                drawCircle(
                    color = Color.White,
                    radius = rect.width / 2f + 4.dp.toPx(),
                    center = center,
                    style = Stroke(width = 2.dp.toPx())
                )

                // Draw connecting line (Arrow)
                val textStartX = rect.right + 16.dp.toPx()
                val textStartY = rect.center.y

                drawLine(
                    color = Color.White,
                    start = Offset(rect.right + 4.dp.toPx(), rect.center.y),
                    end = Offset(textStartX, textStartY),
                    strokeWidth = 2.dp.toPx()
                )
            }
        }

        // Render text descriptions
        itemPositions.forEach { (id, rect) ->
            val description = when(id) {
                "mode_host" -> "Modes\nSwitch between AR, Overlay, Mockup, and Trace modes."
                "design_host" -> "Design\nAccess tools to edit, adjust, and manipulate your image."
                "project_host" -> "Project\nSave, load, export, and manage your projects."
                else -> null
            }

            if (description != null) {
                // Position text to the right of the rail item
                 Box(
                    modifier = Modifier.offset {
                        IntOffset(
                            x = (rect.right + 20.dp.toPx()).toInt(),
                            y = (rect.top).toInt()
                        )
                    }
                    .padding(end = 16.dp) // Right margin
                ) {
                   Text(
                       text = description,
                       color = Color.White,
                       style = MaterialTheme.typography.bodyLarge
                   )
                }
            }
        }

        // FAB to exit
        FloatingActionButton(
            onClick = onDismiss,
            modifier = Modifier
                .align(androidx.compose.ui.Alignment.BottomEnd)
                .padding(32.dp),
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        ) {
            Icon(Icons.Filled.Close, contentDescription = "Close Help")
        }

        // FAB to exit
        FloatingActionButton(
            onClick = onDismiss,
            modifier = Modifier
                .align(androidx.compose.ui.Alignment.TopEnd)
                .padding(16.dp),
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        ) {
            Icon(Icons.Filled.Close, contentDescription = "Close Help")
        }
    }
}
