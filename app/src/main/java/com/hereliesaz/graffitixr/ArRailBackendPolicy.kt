package com.hereliesaz.graffitixr

internal data class ArRailBackendPolicy(
    val backendResolved: Boolean,
    val standalone: Boolean,
    val targetRailEnabled: Boolean,
    val targetDisabledReason: String?,
    val coopCalibrationAvailable: Boolean,
    val coopDisabledReason: String?,
)

/**
 * Capability policy for AR-only rail tools.
 *
 * The standalone backend owns target capture inside SphereSlamStandaloneOverlay, so the legacy
 * ARCore Target rail action must not arm MainViewModel's ARCore tap-to-anchor flow. Co-op is more
 * strict: until a standalone page frame can be explicitly calibrated to a peer frame, Host/Join
 * must stay disabled rather than silently treating unrelated coordinate systems as equivalent.
 */
internal fun arRailBackendPolicy(
    arCoreAvailabilityResolved: Boolean,
    arCoreAvailable: Boolean,
): ArRailBackendPolicy {
    if (!arCoreAvailabilityResolved) {
        return ArRailBackendPolicy(
            backendResolved = false,
            standalone = false,
            targetRailEnabled = false,
            targetDisabledReason = "Checking AR backend…",
            coopCalibrationAvailable = false,
            coopDisabledReason = "Checking AR backend…",
        )
    }
    if (arCoreAvailable) {
        return ArRailBackendPolicy(
            backendResolved = true,
            standalone = false,
            targetRailEnabled = true,
            targetDisabledReason = null,
            coopCalibrationAvailable = true,
            coopDisabledReason = null,
        )
    }
    return ArRailBackendPolicy(
        backendResolved = true,
        standalone = true,
        targetRailEnabled = false,
        targetDisabledReason = "Use the on-screen Wall Target capture",
        coopCalibrationAvailable = false,
        coopDisabledReason = "ARCore calibration required for co-op",
    )
}
