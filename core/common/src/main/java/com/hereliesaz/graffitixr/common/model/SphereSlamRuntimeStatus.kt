package com.hereliesaz.graffitixr.common.model

/**
 * Live SphereSLAM runtime selection and activity.
 *
 * Availability answers whether the packaged native runtime can create a calibrated KPM session.
 * Mode answers which of SphereSLAM's two public operating modes GraffitiXR selected for this AR
 * session. Active answers whether that selected matcher is actually armed with a reference and able
 * to process frames. [trackingData] is deliberately stricter: in sidecar mode it means a fresh KPM
 * observation exists; in standalone mode it means the current CameraX frame produced a wall pose.
 */
enum class SphereSlamRuntimeMode {
    UNRESOLVED,
    ARCORE_SIDECAR,
    STANDALONE,
    UNAVAILABLE,
}

data class SphereSlamRuntimeStatus(
    val available: Boolean = false,
    val mode: SphereSlamRuntimeMode = SphereSlamRuntimeMode.UNRESOLVED,
    val active: Boolean = false,
    val referenceReady: Boolean = false,
    val trackingData: Boolean = false,
) {
    fun diagnosticLine(): String = buildString {
        append("SphereSLAM available=")
        append(available)
        append(" mode=")
        append(mode.name)
        append(" active=")
        append(active)
        append(" referenceReady=")
        append(referenceReady)
        when (mode) {
            SphereSlamRuntimeMode.ARCORE_SIDECAR -> {
                append(" observations=")
                append(trackingData)
            }
            SphereSlamRuntimeMode.STANDALONE -> {
                append(" tracking=")
                append(trackingData)
            }
            SphereSlamRuntimeMode.UNRESOLVED,
            SphereSlamRuntimeMode.UNAVAILABLE -> Unit
        }
    }
}
