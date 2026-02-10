package com.hereliesaz.graffitixr

import com.hereliesaz.graffitixr.common.model.CaptureStep

data class MainUiState(
    val permissionsGranted: Boolean = false,
    val isArSessionReady: Boolean = false,
    val isCapturingTarget: Boolean = false,
    val captureStep: CaptureStep = CaptureStep.CAPTURE,
    val isArTargetCreated: Boolean = false
)
