package com.hereliesaz.graffitixr.composables

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

/**
 * An overlay that highlights specific UI elements (Navigation Rail items)
 * and displays help text next to them.
 *
 * @param itemPositions A map of rail item IDs to their screen-space bounding boxes (Rect).
 * @param onDismiss Callback invoked when the user taps anywhere on the overlay to dismiss it.
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
            .clickable { onDismiss() }
    ) {
        val density = LocalDensity.current

        // Draw highlights/connectors
        Canvas(modifier = Modifier.fillMaxSize()) {
            itemPositions.forEach { (_, rect) ->
                val center = rect.center
                val radius = rect.width / 2f + 8.dp.toPx()

                // Draw a circle highlight around the rail item
                drawCircle(
                    color = Color.White.copy(alpha = 0.3f),
                    radius = radius,
                    center = center
                )
            }
        }

        // Render text descriptions
        itemPositions.forEach { (id, rect) ->
            val description = when(id) {
                "mode_host" -> "Modes: Switch between AR, Overlay, Mockup, and Trace modes."
                "design_host" -> "Design: Access tools to edit, adjust, and manipulate your image."
                "project_host" -> "Project: Save, load, export, and manage your projects."
                else -> null
            }

            if (description != null) {
                // Position text to the right of the rail item
                 Box(
                    modifier = Modifier.offset {
                        IntOffset(
                            x = (rect.right + 16.dp.toPx()).toInt(),
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

        // Tap to dismiss hint
        Box(
            modifier = Modifier
                .align(androidx.compose.ui.Alignment.BottomCenter)
                .padding(bottom = 48.dp)
        ) {
            Text(
                text = "Tap anywhere to close",
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}
