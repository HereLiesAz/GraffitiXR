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
import com.hereliesaz.graffitixr.dialogs.DoubleTapHintDialog
import com.hereliesaz.graffitixr.design.components.OnboardingDialog
import com.hereliesaz.graffitixr.ui.GestureFeedback
import com.hereliesaz.graffitixr.ui.RotationAxisFeedback
import com.hereliesaz.graffitixr.design.components.TapFeedbackEffect
import com.hereliesaz.graffitixr.design.components.AdjustmentsPanel
import com.hereliesaz.graffitixr.design.components.AdjustmentsState

@Composable
fun EditorUi(
    uiState: EditorUiState,
    actions: EditorActions,
    showSliderDialog: String?,
    showColorBalanceDialog: Boolean,
    gestureInProgress: Boolean
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.screenWidthDp > configuration.screenHeightDp
    val topSafePadding = (configuration.screenHeightDp.dp * 0.05f).coerceAtLeast(16.dp)
    val bottomSafePadding = (configuration.screenHeightDp.dp * 0.05f).coerceAtLeast(16.dp)

    Box(modifier = Modifier.fillMaxSize()) {
        // StatusOverlay removed (handled by MainScreen)

        // 2. Gesture Feedback
        // TODO: Update GestureFeedback to take EditorUiState or extract params
        // if (!uiState.isTouchLocked && !uiState.hideUiForCapture) {
        //    GestureFeedback(
        //        uiState,
        //        Modifier.align(Alignment.TopCenter).padding(top = topSafePadding + 20.dp).zIndex(3f),
        //        gestureInProgress
        //    )
        // }

        // 3. Drawing Canvas
        if (uiState.isMarkingProgress) {
            DrawingCanvas(uiState.drawingPaths, actions::onDrawingPathFinished)
        }

        // 4. Adjustments Panel (Bottom)
        Box(
            Modifier.fillMaxSize().padding(bottom = bottomSafePadding).zIndex(2f),
            contentAlignment = Alignment.BottomCenter
        ) {
            val adjustmentsState = AdjustmentsState(
                hideUiForCapture = uiState.hideUiForCapture,
                isTouchLocked = uiState.isTouchLocked,
                hasImage = uiState.layers.isNotEmpty(),
                isArMode = uiState.editorMode == EditorMode.AR,
                hasHistory = true, // TODO: Add canUndo/canRedo to EditorUiState
                isRightHanded = uiState.isRightHanded,
                activeLayer = uiState.activeLayer
            )

            AdjustmentsPanel(
                state = adjustmentsState,
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
            if (mode is EditorMode) {
                OnboardingDialog(mode) { actions.onOnboardingComplete(mode) }
            }
        }

        if (!uiState.hideUiForCapture && !uiState.isTouchLocked) {
            RotationAxisFeedback(
                uiState.activeRotationAxis,
                uiState.showRotationAxisFeedback,
                actions::onFeedbackShown,
                Modifier.align(Alignment.BottomCenter).padding(bottom = bottomSafePadding + 32.dp).zIndex(4f)
            )
            TapFeedbackEffect(uiState.tapFeedback)

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
