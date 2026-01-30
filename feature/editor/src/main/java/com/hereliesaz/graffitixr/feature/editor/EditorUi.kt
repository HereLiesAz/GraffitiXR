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
import com.hereliesaz.graffitixr.EditorMode
import com.hereliesaz.graffitixr.MainViewModel
import com.hereliesaz.graffitixr.StatusOverlay
import com.hereliesaz.graffitixr.UiState
import com.hereliesaz.graffitixr.dialogs.DoubleTapHintDialog
import com.hereliesaz.graffitixr.dialogs.OnboardingDialog

@Composable
fun EditorUi(
    viewModel: MainViewModel,
    uiState: UiState,
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
            DrawingCanvas(uiState.drawingPaths, viewModel::onDrawingPathFinished)
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
                onOpacityChange = viewModel::onOpacityChanged,
                onBrightnessChange = viewModel::onBrightnessChanged,
                onContrastChange = viewModel::onContrastChanged,
                onSaturationChange = viewModel::onSaturationChanged,
                onColorBalanceRChange = viewModel::onColorBalanceRChanged,
                onColorBalanceGChange = viewModel::onColorBalanceGChanged,
                onColorBalanceBChange = viewModel::onColorBalanceBChanged,
                onUndo = viewModel::onUndoClicked,
                onRedo = viewModel::onRedoClicked,
                onMagicAlign = viewModel::onMagicClicked
            )
        }

        // 5. Dialogs & Helpers
        uiState.showOnboardingDialogForMode?.let { mode ->
            OnboardingDialog(mode) { viewModel.onOnboardingComplete(mode) }
        }

        if (!uiState.hideUiForCapture && !uiState.isTouchLocked) {
            RotationAxisFeedback(
                uiState.activeRotationAxis,
                uiState.showRotationAxisFeedback,
                viewModel::onFeedbackShown,
                Modifier.align(Alignment.BottomCenter).padding(bottom = bottomSafePadding + 32.dp).zIndex(4f)
            )
            TapFeedbackEffect(viewModel.tapFeedback.collectAsState().value)

            if (uiState.showDoubleTapHint) {
                DoubleTapHintDialog(viewModel::onDoubleTapHintDismissed)
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