package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.hereliesaz.graffitixr.common.model.EditorMode
import com.hereliesaz.graffitixr.domain.state.UiState
import com.hereliesaz.graffitixr.common.model.TapFeedback
import com.hereliesaz.graffitixr.feature.editor.ui.GestureFeedback
import com.hereliesaz.graffitixr.feature.editor.ui.RotationAxisFeedback
import com.hereliesaz.graffitixr.feature.editor.ui.StatusOverlay
import com.hereliesaz.graffitixr.dialogs.DoubleTapHintDialog
import com.hereliesaz.graffitixr.dialogs.OnboardingDialog
import com.hereliesaz.graffitixr.design.components.TapFeedbackEffect

@Composable
fun EditorUi(
    actions: EditorActions,
    uiState: UiState,
    tapFeedback: TapFeedback?,
    showSliderDialog: String?,
    showColorBalanceDialog: Boolean,
    gestureInProgress: Boolean
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.screenWidthDp > configuration.screenHeightDp
    val topSafePadding = (configuration.screenHeightDp.dp * 0.05f).coerceAtLeast(16.dp)
    val bottomSafePadding = (configuration.screenHeightDp.dp * 0.05f).coerceAtLeast(16.dp)

    Box(modifier = Modifier.fillMaxSize()) {
        // 1. Status Overlays
        if (uiState.editorMode == EditorMode.AR && !uiState.isCapturingTarget && !uiState.hideUiForCapture) {
            StatusOverlay(
                uiState.qualityWarning,
                uiState.arState,
                uiState.isArPlanesDetected,
                uiState.isArTargetCreated,
                Modifier.align(Alignment.TopCenter).padding(top = topSafePadding).zIndex(10f)
            )
        }

        // 2. Gesture Feedback
        if (!uiState.isTouchLocked && !uiState.hideUiForCapture) {
            GestureFeedback(
                uiState,
                Modifier.align(Alignment.TopCenter).padding(top = topSafePadding + 20.dp).zIndex(3f),
                gestureInProgress
            )
        }

        // 3. Drawing Canvas
        if (uiState.isMarkingProgress) {
            DrawingCanvas(uiState.drawingPaths, actions::onDrawingPathFinished)
        }

        // 4. Adjustments Panel (Bottom)
        Box(
            Modifier.fillMaxSize().padding(bottom = bottomSafePadding).zIndex(2f),
            contentAlignment = Alignment.BottomCenter
        ) {
            AdjustmentsPanel(
                uiState = uiState,
                showKnobs = showSliderDialog == "Adjust",
                showColorBalance = showColorBalanceDialog,
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
                onMagicAlign = actions::onMagicClicked
            )
        }

        // 5. Dialogs & Helpers
        uiState.showOnboardingDialogForMode?.let { mode ->
            OnboardingDialog(mode) { actions.onOnboardingComplete(mode) }
        }

        if (!uiState.hideUiForCapture && !uiState.isTouchLocked) {
            RotationAxisFeedback(
                uiState.activeRotationAxis,
                uiState.showRotationAxisFeedback,
                actions::onFeedbackShown,
                Modifier.align(Alignment.BottomCenter).padding(bottom = bottomSafePadding + 32.dp).zIndex(4f)
            )
            TapFeedbackEffect(tapFeedback)

            if (uiState.showDoubleTapHint) {
                DoubleTapHintDialog(actions::onDoubleTapHintDismissed)
            }

            if (uiState.isMarkingProgress) {
                Text(
                    "Progress: %.2f%%".format(uiState.progressPercentage),
                    Modifier.align(Alignment.TopCenter).padding(top = topSafePadding).zIndex(3f)
                )
            }
        }
    }
}
