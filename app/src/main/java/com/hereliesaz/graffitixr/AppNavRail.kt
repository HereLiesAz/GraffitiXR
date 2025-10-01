package com.hereliesaz.graffitixr

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Brightness5
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable

/**
 * A navigation rail composable that provides the main actions for the application.
 *
 * @param onSelectImage A callback for when the user wants to select an image.
 * @param onRemoveBg A callback for when the user wants to remove the background of the image.
 * @param onClearMarkers A callback for when the user wants to clear the placed markers.
 * @param onLockMural A callback for when the user wants to lock the mural's position.
 * @param onResetMural A callback for when the user wants to reset the mural.
 * @param onSliderSelected A callback for when the user selects a slider for image adjustment.
 */
@Composable
fun AppNavRail(
    onSelectImage: () -> Unit,
    onRemoveBg: () -> Unit,
    onClearMarkers: () -> Unit,
    onLockMural: () -> Unit,
    onResetMural: () -> Unit,
    onSliderSelected: (SliderType) -> Unit
) {
    NavigationRail {
        IconButton(onClick = onSelectImage) {
            Icon(Icons.Default.AddAPhoto, contentDescription = "Select Image")
        }
        IconButton(onClick = onRemoveBg) {
            Icon(Icons.Default.AutoAwesome, contentDescription = "Remove Background")
        }
        IconButton(onClick = onClearMarkers) {
            Icon(Icons.Default.Delete, contentDescription = "Clear Markers")
        }
        IconButton(onClick = onLockMural) {
            Icon(Icons.Default.Lock, contentDescription = "Lock Mural")
        }
        IconButton(onClick = onResetMural) {
            Icon(Icons.Default.Refresh, contentDescription = "Reset Mural")
        }
        IconButton(onClick = { onSliderSelected(SliderType.Opacity) }) {
            Icon(Icons.Default.Colorize, contentDescription = "Opacity")
        }
        IconButton(onClick = { onSliderSelected(SliderType.Contrast) }) {
            Icon(Icons.Default.Contrast, contentDescription = "Contrast")
        }
        IconButton(onClick = { onSliderSelected(SliderType.Saturation) }) {
            Icon(Icons.Default.AutoAwesome, contentDescription = "Saturation")
        }
        IconButton(onClick = { onSliderSelected(SliderType.Brightness) }) {
            Icon(Icons.Default.Brightness5, contentDescription = "Brightness")
        }
    }
}
