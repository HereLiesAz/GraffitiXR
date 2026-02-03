package com.hereliesaz.graffitixr.feature.ar

import com.hereliesaz.graffitixr.common.model.ArState

data class ArUiState(
    val arState: ArState = ArState.SEARCHING,
    val isArPlanesDetected: Boolean = false,
    val isArTargetCreated: Boolean = false,
    val showPointCloud: Boolean = false,
    val isFlashlightOn: Boolean = false,
    val mappingQualityScore: Float = 0f,
    val isMappingMode: Boolean = false,
    val qualityWarning: String? = null
)
