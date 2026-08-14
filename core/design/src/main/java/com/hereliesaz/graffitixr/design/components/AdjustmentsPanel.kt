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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.common.model.OverlayLayer
import com.hereliesaz.graffitixr.design.theme.AppStrings
import com.hereliesaz.graffitixr.design.theme.contrastColorFor

data class AdjustmentsState(
    val hideUiForCapture: Boolean = false,
    val isTouchLocked: Boolean = false,
    val hasImage: Boolean = false,
    val isArMode: Boolean = false,
    val hasHistory: Boolean = false,
    val undoCount: Int = 0,
    val redoCount: Int = 0,
    /** True once a Reset press has flattened placement, so the next press restores it. */
    val isResetActive: Boolean = false,
    val isRightHanded: Boolean = true,
    val isCapturingTarget: Boolean = false,
    val activeLayer: OverlayLayer? = null,
    // Undo/redo belongs to the Design screen only; Modes show the finished design (no history controls).
    val showUndoRedo: Boolean = true,
    /**
     * The canvas/wall background currently behind these controls, if known. When set, knob labels
     * and undo/redo counts switch to black or white for contrast (see [contrastColorFor]) instead
     * of always rendering white -- which otherwise vanishes against light canvas-background presets
     * (e.g. the app's own "White" preset). Left null (falling back to white, the prior behavior)
     * until a caller threads the real canvas background through.
     */
    val canvasBackground: Color? = null
)

/**
 * Integrated panel for image adjustments, color balance, and undo/redo controls.
 * This panel handles the visibility of the adjustment knobs and the persistent
 * action row (Undo, Reset, Redo).
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
    onReset: () -> Unit,
    onAdjustmentStart: () -> Unit,
    onAdjustmentEnd: () -> Unit,
    strings: AppStrings,
    // When non-null (i.e. in a Mode), the knobs reflect the whole-design mode adjustment instead of
    // the active layer's values.
    modeOpacity: Float? = null,
    modeBrightness: Float? = null,
    modeContrast: Float? = null,
    modeSaturation: Float? = null,
    modifier: Modifier = Modifier
) {
    // Hide entirely during capture or if touch is locked
    if (state.hideUiForCapture || state.isTouchLocked) return

    val hasImage = state.hasImage
    val isArMode = state.isArMode
    val hasHistory = state.hasHistory

    // The panel should be visible if we are adjusting an image, or if we have an image active,
    // or if we are in AR mode (to provide access to the action row while anchoring),
    // or if there's any history to undo/redo.
    // HOWEVER, we hide the action row (Undo, Reset, Redo) during Target Creation.
    val canShowActionRow = !state.isCapturingTarget
    val isVisible = showKnobs || showColorBalance || (canShowActionRow && (hasImage || isArMode || hasHistory))

    if (!isVisible) return

    val bottomPadding = if (isLandscape) 16.dp else (screenHeight * 0.0f)

    // Resolve active layer properties
    val activeLayer = state.activeLayer
    val opacity = modeOpacity ?: activeLayer?.opacity ?: 1f
    val brightness = modeBrightness ?: activeLayer?.brightness ?: 0f
    val contrast = modeContrast ?: activeLayer?.contrast ?: 1f
    val saturation = modeSaturation ?: activeLayer?.saturation ?: 1f
    val colorBalanceR = activeLayer?.colorBalanceR ?: 1f
    val colorBalanceG = activeLayer?.colorBalanceG ?: 1f
    val colorBalanceB = activeLayer?.colorBalanceB ?: 1f

    // Luminance-aware label color: falls back to white (the historical hardcoded value) when the
    // caller hasn't threaded a real canvas background through yet.
    val labelColor = state.canvasBackground?.let { contrastColorFor(it) } ?: Color.White

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
                    strings = strings,
                    labelColor = labelColor,
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
                    labelColor = labelColor,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        if (canShowActionRow && state.showUndoRedo) {
            UndoRedoRow(
                canUndo = state.undoCount > 0,
                canRedo = state.redoCount > 0,
                undoCount = state.undoCount,
                redoCount = state.redoCount,
                onUndo = onUndo,
                onRedo = onRedo,
                strings = strings,
                modifier = Modifier.fillMaxWidth(),
                canReset = hasImage,
                isResetActive = state.isResetActive,
                onReset = onReset,
                countColor = labelColor,
            )
        }
    }
}

