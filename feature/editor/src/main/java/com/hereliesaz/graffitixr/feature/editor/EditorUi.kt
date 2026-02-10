package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.hereliesaz.graffitixr.common.model.EditorMode
import com.hereliesaz.graffitixr.common.model.UiState
import com.hereliesaz.graffitixr.feature.editor.ui.GestureFeedback
import com.hereliesaz.graffitixr.feature.editor.ui.RotationAxisFeedback
import com.hereliesaz.graffitixr.feature.editor.ui.StatusOverlay
import com.hereliesaz.graffitixr.design.components.DoubleTapHintDialog
import com.hereliesaz.graffitixr.design.components.OnboardingDialog
import com.hereliesaz.graffitixr.design.components.AdjustmentsPanel
import com.hereliesaz.graffitixr.design.components.AdjustmentsState

@Composable
fun EditorUi(
    actions: EditorActions,
    uiState: EditorUiState,
    isTouchLocked: Boolean,
    showUnlockInstructions: Boolean
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.screenWidthDp > configuration.screenHeightDp
    val topSafePadding = (configuration.screenHeightDp.dp * 0.05f).coerceAtLeast(16.dp)
    val bottomSafePadding = (configuration.screenHeightDp.dp * 0.05f).coerceAtLeast(16.dp)

    val activeLayer = uiState.activeLayer

    Box(modifier = Modifier.fillMaxSize()) {
        // 1. Status Overlays
        // Note: statusOverlay removed for now, as it requires AR state which is no longer in EditorUiState
        // If needed, it should be passed in as a separate parameter or provided via a different state.

        // 2. Gesture Feedback
        if (!isTouchLocked) {
            // Mapping EditorUiState to common UiState for GestureFeedback
            val bridgeState = UiState(
                layers = uiState.layers,
                activeLayerId = uiState.activeLayerId,
                activeRotationAxis = uiState.activeRotationAxis
            )
            GestureFeedback(
                uiState = bridgeState,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = topSafePadding + 20.dp).zIndex(3f),
                isVisible = uiState.gestureInProgress
            )
        }

        // 3. Drawing Canvas
        if (uiState.editorMode == EditorMode.DRAW) {
            DrawingCanvas(uiState.drawingPaths, actions::onDrawingPathFinished)
        }

        // 4. Adjustments Panel (Bottom)
        Box(
            Modifier.fillMaxSize().padding(bottom = bottomSafePadding).zIndex(2f),
            contentAlignment = Alignment.BottomCenter
        ) {
            val adjustmentsState = AdjustmentsState(
                hideUiForCapture = false, // Placeholder
                isTouchLocked = isTouchLocked,
                hasImage = activeLayer != null,
                isArMode = uiState.editorMode == EditorMode.AR,
                hasHistory = true, // Placeholder
                isRightHanded = uiState.isRightHanded,
                activeLayer = activeLayer
            )

            AdjustmentsPanel(
                state = adjustmentsState,
                showKnobs = uiState.activePanel == EditorPanel.ADJUST,
                showColorBalance = uiState.activePanel == EditorPanel.COLOR,
                isLandscape = isLandscape,
                screenHeight = configuration.screenHeightDp.dp,
                onOpacityChange = actions::onOpacityChanged,
                onBrightnessChange = actions::onBrightnessChanged,
                onContrastChange = actions::onContrastChanged,
                onSaturationChange = actions::onSaturationChanged,
                onColorBalanceRChange = actions::onColorBalanceRChanged,
                onColorBalanceGChange = actions::onColorBalanceGChanged,
                onColorBalanceBChange = actions::onColorBalanceBChanged,
                onUndo = actions::onUndoClicked,
                onRedo = actions::onRedoClicked,
                onMagicAlign = actions::onMagicClicked,
                onAdjustmentStart = actions::onAdjustmentStart,
                onAdjustmentEnd = actions::onAdjustmentEnd
            )
        }

        // 5. Dialogs & Helpers
        uiState.showOnboardingDialogForMode?.let { mode ->
            if (mode is EditorMode) {
                OnboardingDialog(mode) { actions.onOnboardingComplete(mode) }
            }
        }

        if (!isTouchLocked) {
            RotationAxisFeedback(
                uiState.activeRotationAxis,
                uiState.showRotationAxisFeedback,
                actions::onFeedbackShown,
                Modifier.align(Alignment.BottomCenter).padding(bottom = bottomSafePadding + 32.dp).zIndex(4f)
            )

            if (uiState.showDoubleTapHint) {
                DoubleTapHintDialog(actions::onDoubleTapHintDismissed)
            }

            if (uiState.editorMode == EditorMode.DRAW) {
                Text(
                    "Progress: %.2f%%".format(uiState.progressPercentage),
                    Modifier.align(Alignment.TopCenter).padding(top = topSafePadding).zIndex(3f)
                )
            }
        }
    }
}
