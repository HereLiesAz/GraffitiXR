package com.hereliesaz.graffitixr.feature.ar

import android.graphics.Bitmap
import android.net.Uri
import com.hereliesaz.graffitixr.common.model.ArState

data class ArUiState(
    val arState: ArState = ArState.SEARCHING,
    val isArPlanesDetected: Boolean = false,
    val isArTargetCreated: Boolean = false,
    val showPointCloud: Boolean = false,
    val isFlashlightOn: Boolean = false,
    val mappingQualityScore: Float = 0f,
    val isMappingMode: Boolean = false,
    val qualityWarning: String? = null,
    val capturedTargetUris: List<Uri> = emptyList(),
    val capturedTargetImages: List<Bitmap> = emptyList()
)
