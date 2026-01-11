package com.hereliesaz.graffitixr.composables

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.UiState

/**
 * Integrated panel for image adjustments, color balance, and undo/redo controls.
 * This panel is now more inclusive, showing undo/redo/magic controls even if an image
 * isn't fully loaded, as long as there are actions to undo or a session to align.
 */
@Composable
fun AdjustmentsPanel(
    uiState: UiState,
    showKnobs: Boolean,
    showColorBalance: Boolean,
    isLandscape: Boolean,
    screenHeight: Dp,
    onOpacityChange: (Float) -> Unit,
    onBrightnessChange: (Float) -> Unit,
    onContrastChange: (Float) -> Unit,
    onSaturationChange: (Float) -> Unit,
    onColorBalanceRChange: (Float) -> Unit,
    onColorBalanceGChange: (Float) -> Unit,
    onColorBalanceBChange: (Float) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onMagicAlign: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Return early if UI should be hidden (capture mode or touch lock)
    if (uiState.hideUiForCapture || uiState.isTouchLocked) return

    val hasImage = uiState.overlayImageUri != null || uiState.layers.isNotEmpty()
    val hasHistory = uiState.canUndo || uiState.canRedo
    
    // We show the panel if there is an image OR if there is something to undo/redo
    if (!hasImage && !hasHistory) return

    // Layout Constants
    val portraitBottomKeepoutPercentage = 0.1f
    val landscapeBottomPadding = 32.dp
    val landscapeStartPadding = 80.dp
    val portraitStartPadding = 0.dp
    
    // Calculate adaptive padding based on orientation and screen dimensions
    val bottomPadding = if (isLandscape) landscapeBottomPadding else (screenHeight * portraitBottomKeepoutPercentage)
    val startPadding = if (isLandscape) landscapeStartPadding else portraitStartPadding

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = bottomPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Color Balance Knobs
        AnimatedVisibility(
            visible = showColorBalance && hasImage,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
        ) {
            ColorBalanceKnobsRow(
                colorBalanceR = uiState.colorBalanceR,
                colorBalanceG = uiState.colorBalanceG,
                colorBalanceB = uiState.colorBalanceB,
                onColorBalanceRChange = onColorBalanceRChange,
                onColorBalanceGChange = onColorBalanceGChange,
                onColorBalanceBChange = onColorBalanceBChange,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Image Adjustment Knobs (Opacity, Brightness, Contrast, Saturation)
        AnimatedVisibility(
            visible = showKnobs && hasImage,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
        ) {
            AdjustmentsKnobsRow(
                opacity = uiState.opacity,
                brightness = uiState.brightness,
                contrast = uiState.contrast,
                saturation = uiState.saturation,
                onOpacityChange = onOpacityChange,
                onBrightnessChange = onBrightnessChange,
                onContrastChange = onContrastChange,
                onSaturationChange = onSaturationChange,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Action Row: Undo, Redo, and Magic Wand (Magic Align)
        // This row is visible as long as the panel is active
        UndoRedoRow(
            canUndo = uiState.canUndo,
            canRedo = uiState.canRedo,
            onUndo = onUndo,
            onRedo = onRedo,
            onMagicClicked = onMagicAlign,
            modifier = Modifier
                .padding(start = startPadding)
                .fillMaxWidth()
        )
    }
}
