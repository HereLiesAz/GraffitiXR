package com.hereliesaz.graffitixr.common.model

import android.graphics.Bitmap

data class EditorUiState(
    val activeLayerId: String? = null,
    val layers: List<Layer> = emptyList(),
    val editorMode: EditorMode = EditorMode.EDIT,
    val activeRotationAxis: RotationAxis = RotationAxis.Z,
    val isRightHanded: Boolean = true,
    val isImageLocked: Boolean = false,
    val hideUiForCapture: Boolean = false,
    val isLoading: Boolean = false,

    // NEW: Background / Mockup Fields required for 3D Viewer
    val mapPath: String? = null,
    val backgroundBitmap: Bitmap? = null,

    // NEW: Editor Tools State
    val activePanel: EditorPanel? = null
)

enum class EditorPanel {
    NONE, LAYERS, ADJUST, COLOR, BLEND
}