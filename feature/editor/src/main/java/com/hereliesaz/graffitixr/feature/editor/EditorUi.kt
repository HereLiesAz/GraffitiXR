// FILE: feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/EditorUi.kt
package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.common.model.*
import com.hereliesaz.graffitixr.design.components.AdjustmentsPanel
import com.hereliesaz.graffitixr.design.components.AdjustmentsState
import com.hereliesaz.graffitixr.feature.editor.ui.GestureFeedback
import com.hereliesaz.graffitixr.design.theme.AppStrings

@Composable
fun EditorUi(
    actions: EditorViewModel,
    uiState: EditorUiState,
    isTouchLocked: Boolean,
    showUnlockInstructions: Boolean,
    strings: AppStrings,
    isCapturingTarget: Boolean = false
) {
    val configuration = LocalConfiguration.current
    val screenHeight = configuration.screenHeightDp.dp
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    Box(modifier = Modifier.fillMaxSize()) {

        GestureFeedback(
            state = uiState,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 32.dp)
        )

        Column(
            modifier = Modifier.align(Alignment.BottomCenter),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Integrated Adjustments Panel (Knobs + Undo/Redo)
            val overlayLayer = uiState.design?.let {
                OverlayLayer(
                    id = it.id,
                    name = it.name,
                    uri = it.uri ?: android.net.Uri.EMPTY,
                    opacity = it.opacity,
                    brightness = it.brightness,
                    contrast = it.contrast,
                    saturation = it.saturation,
                    colorBalanceR = it.colorBalanceR,
                    colorBalanceG = it.colorBalanceG,
                    colorBalanceB = it.colorBalanceB,
                    isImageLocked = it.isImageLocked,
                    isInverted = it.isInverted
                )
            }

            // Modes (anything but the Design screen) show the finished design with off-rail adjustment
            // knobs; undo/redo sit beneath those knobs here too, so every mode keeps history controls.
            val inMode = uiState.editorMode != EditorMode.DESIGN
            // In a Mode the adjust knobs drive the whole-design mode adjustment (which always exists),
            // not the active layer — so they work without a selected layer and tone the whole design.
            val modeAdj = if (inMode) uiState.modeAdjustments[uiState.editorMode] ?: ModeAdjustment() else null
            AdjustmentsPanel(
                state = AdjustmentsState(
                    hideUiForCapture = uiState.hideUiForCapture,
                    isTouchLocked = isTouchLocked,
                    hasImage = uiState.design != null,
                    isArMode = uiState.editorMode == EditorMode.AR,
                    hasHistory = uiState.undoCount > 0 || uiState.redoCount > 0,
                    undoCount = uiState.undoCount,
                    redoCount = uiState.redoCount,
                    isResetActive = uiState.transformStash != null,
                    isRightHanded = uiState.isRightHanded,
                    isCapturingTarget = isCapturingTarget,
                    activeLayer = overlayLayer,
                    showUndoRedo = true
                ),
                // In a Mode the adjust knobs are the off-rail tools (opacity/saturation/…); in Design
                // they're rail-triggered via the Adjust panel. Hidden while capturing a target so they
                // don't overlap the target-creation dialog.
                showKnobs = !isCapturingTarget &&
                    (uiState.activePanel == EditorPanel.ADJUST || (inMode && uiState.design != null)),
                showColorBalance = uiState.activePanel == EditorPanel.COLOR,
                isLandscape = isLandscape,
                screenHeight = screenHeight,
                onOpacityChange = actions::onOpacityChanged,
                onBrightnessChange = actions::onBrightnessChanged,
                onContrastChange = actions::onContrastChanged,
                onSaturationChange = actions::onSaturationChanged,
                onColorBalanceRChange = actions::onColorBalanceRChanged,
                onColorBalanceGChange = actions::onColorBalanceGChanged,
                onColorBalanceBChange = actions::onColorBalanceBChanged,
                onUndo = actions::onUndoClicked,
                onRedo = actions::onRedoClicked,
                onReset = actions::onResetClicked,
                onAdjustmentStart = actions::onAdjustmentStart,
                onAdjustmentEnd = actions::onAdjustmentEnd,
                strings = strings,
                modeOpacity = modeAdj?.opacity,
                modeBrightness = modeAdj?.brightness,
                modeContrast = modeAdj?.contrast,
                modeSaturation = modeAdj?.saturation,
            )
        }
    }
}
