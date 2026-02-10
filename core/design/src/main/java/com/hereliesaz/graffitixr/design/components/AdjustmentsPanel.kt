package com.hereliesaz.graffitixr.design.components

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
import com.hereliesaz.graffitixr.common.model.OverlayLayer

data class AdjustmentsState(
    val hideUiForCapture: Boolean = false,
    val isTouchLocked: Boolean = false,
    val hasImage: Boolean = false,
    val isArMode: Boolean = false,
    val hasHistory: Boolean = false,
    val isRightHanded: Boolean = true,
    val activeLayer: OverlayLayer? = null
)

/**
 * Integrated panel for image adjustments, color balance, and undo/redo controls.
 * This panel handles the visibility of the adjustment knobs and the persistent 
 * action row (Undo, Redo, Magic Wand).
 */
@Composable
fun AdjustmentsPanel(
    state: AdjustmentsState,
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
    onAdjustmentStart: () -> Unit,
    onAdjustmentEnd: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Hide entirely during capture or if touch is locked
    if (state.hideUiForCapture || state.isTouchLocked) return

    val hasImage = state.hasImage
    val isArMode = state.isArMode
    val hasHistory = state.hasHistory

    // The panel should be visible if we are adjusting an image, or if we have an image active,
    // or if we are in AR mode (to provide access to the Magic Wand for anchoring),
    // or if there's any history to undo/redo.
    val isVisible = showKnobs || showColorBalance || hasImage || isArMode || hasHistory

    if (!isVisible) return

    // Layout Constants matching the project's UI design system
    val portraitBottomKeepoutPercentage = 0.0f
    val landscapeBottomPadding = 16.dp
    val landscapeStartPadding = 80.dp
    val portraitStartPadding = 0.dp
    
    val bottomPadding = if (isLandscape) landscapeBottomPadding else (screenHeight * portraitBottomKeepoutPercentage)

    val isRightHanded = state.isRightHanded
    val startPadding = if (isLandscape && isRightHanded) landscapeStartPadding else portraitStartPadding
    val endPadding = if (isLandscape && !isRightHanded) landscapeStartPadding else 0.dp

    // Resolve active layer properties
    val activeLayer = state.activeLayer
    val opacity = activeLayer?.opacity ?: 1f
    val brightness = activeLayer?.brightness ?: 0f
    val contrast = activeLayer?.contrast ?: 1f
    val saturation = activeLayer?.saturation ?: 1f
    val colorBalanceR = activeLayer?.colorBalanceR ?: 1f
    val colorBalanceG = activeLayer?.colorBalanceG ?: 1f
    val colorBalanceB = activeLayer?.colorBalanceB ?: 1f

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = bottomPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Image-specific adjustment knobs
        // These are only shown if an image is actually present to adjust.
        if (hasImage) {
            AnimatedVisibility(
                visible = showColorBalance,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
            ) {
                ColorBalanceKnobsRow(
                    colorBalanceR = colorBalanceR,
                    colorBalanceG = colorBalanceG,
                    colorBalanceB = colorBalanceB,
                    onColorBalanceRChange = onColorBalanceRChange,
                    onColorBalanceGChange = onColorBalanceGChange,
                    onColorBalanceBChange = onColorBalanceBChange,
                    onAdjustmentStart = onAdjustmentStart,
                    onAdjustmentEnd = onAdjustmentEnd,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            AnimatedVisibility(
                visible = showKnobs,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
            ) {
                AdjustmentsKnobsRow(
                    opacity = opacity,
                    brightness = brightness,
                    contrast = contrast,
                    saturation = saturation,
                    onOpacityChange = onOpacityChange,
                    onBrightnessChange = onBrightnessChange,
                    onContrastChange = onContrastChange,
                    onSaturationChange = onSaturationChange,
                    onAdjustmentStart = onAdjustmentStart,
                    onAdjustmentEnd = onAdjustmentEnd,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // Persistent Action Row: Undo, Redo, and Magic Wand (Magic Align)
        // These are visible as long as the panel itself is visible.
        UndoRedoRow(
            canUndo = true, // Simplified: Assume true or passed in state if we want fine-grained
            canRedo = true,
            onUndo = onUndo,
            onRedo = onRedo,
            onMagicClicked = onMagicAlign,
            modifier = Modifier
                .padding(start = startPadding, end = endPadding)
                .fillMaxWidth()
        )
    }
}