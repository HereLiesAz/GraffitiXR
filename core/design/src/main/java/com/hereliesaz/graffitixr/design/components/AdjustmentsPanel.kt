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
    val isRightHanded: Boolean = true,
    val activeLayer: OverlayLayer? = null
)

/**
 * Panel for image adjustments (opacity, brightness, contrast, saturation, color balance).
 * Only visible when the user explicitly opens Adjust or Balance from the rail.
 * Undo/Redo/Magic Align live in the rail itself.
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
    onAdjustmentStart: () -> Unit,
    onAdjustmentEnd: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (state.hideUiForCapture || state.isTouchLocked) return
    if (!showKnobs && !showColorBalance) return

    val bottomPadding = if (isLandscape) 16.dp else (screenHeight * 0.0f)

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
        if (state.hasImage) {
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
    }
}
