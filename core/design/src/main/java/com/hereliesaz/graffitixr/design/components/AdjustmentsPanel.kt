package com.hereliesaz.graffitixr.design.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.common.model.OverlayLayer
import com.hereliesaz.graffitixr.design.theme.HotPink
import com.hereliesaz.graffitixr.design.theme.AppStrings

data class AdjustmentsState(
    val hideUiForCapture: Boolean = false,
    val isTouchLocked: Boolean = false,
    val hasImage: Boolean = false,
    val isArMode: Boolean = false,
    val hasHistory: Boolean = false,
    val undoCount: Int = 0,
    val redoCount: Int = 0,
    val isRightHanded: Boolean = true,
    val isCapturingTarget: Boolean = false,
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
    strings: AppStrings,
    showSegmentationSlider: Boolean = false,
    segmentationInfluence: Float = 0.5f,
    onSegmentationInfluenceChange: (Float) -> Unit = {},
    onSegmentationDismiss: () -> Unit = {},
    onSegmentationCancel: () -> Unit = {},
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
    // HOWEVER, we hide the action row (Undo, Redo, Magic) during Target Creation.
    val canShowActionRow = !state.isCapturingTarget
    val isVisible = showKnobs || showColorBalance || showSegmentationSlider || (canShowActionRow && (hasImage || isArMode || hasHistory))

    if (!isVisible) return

    val bottomPadding = if (isLandscape) 16.dp else (screenHeight * 0.0f)

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
                visible = showSegmentationSlider,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
            ) {
                SegmentationInfluenceRow(
                    influence = segmentationInfluence,
                    onInfluenceChange = onSegmentationInfluenceChange,
                    onDismiss = onSegmentationDismiss,
                    onCancel = onSegmentationCancel,
                    strings = strings,
                    modifier = Modifier.fillMaxWidth()
                )
            }

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
                    strings = strings,
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
                    strings = strings,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        if (canShowActionRow) {
            UndoRedoRow(
                canUndo = true, // Logic handled by ViewModel, but we can pass state if needed
                canRedo = true, // Logic handled by ViewModel
                undoCount = state.undoCount,
                redoCount = state.redoCount,
                onUndo = onUndo,
                onRedo = onRedo,
                onMagicClicked = onMagicAlign,
                strings = strings,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
fun SegmentationInfluenceRow(
    influence: Float,
    onInfluenceChange: (Float) -> Unit,
    onDismiss: () -> Unit,
    onCancel: () -> Unit,
    strings: AppStrings,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Cancel button
            Surface(
                shape = CircleShape,
                color = HotPink,
                shadowElevation = 4.dp
            ) {
                IconButton(onClick = onCancel) {
                    Icon(Icons.Default.Close, contentDescription = strings.common.cancel, tint = Color.White)
                }
            }

            // Confirm button
            Surface(
                shape = CircleShape,
                color = HotPink,
                shadowElevation = 4.dp
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Check, contentDescription = strings.common.done, tint = Color.White)
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                strings.adj.detail,
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.width(36.dp)
            )
            Slider(
                value = influence,
                onValueChange = onInfluenceChange,
                valueRange = 0f..1f,
                modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(
                    thumbColor = HotPink,
                    activeTrackColor = HotPink,
                    inactiveTrackColor = Color.DarkGray
                )
            )
        }
    }
}
