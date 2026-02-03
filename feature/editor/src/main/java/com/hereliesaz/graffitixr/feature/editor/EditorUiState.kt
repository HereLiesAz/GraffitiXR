package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.ui.geometry.Offset
import com.hereliesaz.graffitixr.common.model.EditorMode
import com.hereliesaz.graffitixr.common.model.OverlayLayer
import com.hereliesaz.graffitixr.common.model.RotationAxis

import com.hereliesaz.graffitixr.common.model.TapFeedback

data class EditorUiState(
    val editorMode: EditorMode = EditorMode.AR,
    val layers: List<OverlayLayer> = emptyList(),
    val activeLayerId: String? = null,
    val isImageLocked: Boolean = false,
    val isTouchLocked: Boolean = false,
    val showUnlockInstructions: Boolean = false,
    val isRightHanded: Boolean = true,
    
    // Feedback / Hints
    val showDoubleTapHint: Boolean = false,
    val activeRotationAxis: RotationAxis = RotationAxis.Z,
    val showRotationAxisFeedback: Boolean = false,
    val tapFeedback: TapFeedback? = null,
    val showOnboardingDialogForMode: Any? = null,
    val gestureInProgress: Boolean = false,
    
    // Drawing
    val isMarkingProgress: Boolean = false,
    val drawingPaths: List<List<Offset>> = emptyList(),
    val progressPercentage: Float = 0f,
    
    // Dialogs
    val sliderDialogType: String? = null,
    val showColorBalanceDialog: Boolean = false,
    
    // Capture state needed for UI hiding
    val hideUiForCapture: Boolean = false
) {
    val activeLayer: OverlayLayer?
        get() = layers.find { it.id == activeLayerId } ?: layers.firstOrNull()
}
