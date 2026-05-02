package com.hereliesaz.graffitixr.feature.ar

sealed class GlassesSessionState {
    object Idle : GlassesSessionState()
    object PairingPrompt : GlassesSessionState()
    data class CalibrationPrompt(val progress: Float) : GlassesSessionState()
    object Active : GlassesSessionState()
    data class Fallback(val reason: String) : GlassesSessionState()
}
