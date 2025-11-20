package com.hereliesaz.graffitixr

import androidx.compose.ui.geometry.Offset

sealed class TapFeedback {
    data class Success(val position: Offset) : TapFeedback()
    data class Failure(val position: Offset) : TapFeedback()
}
