package com.hereliesaz.graffitixr

internal data class ArRailBackendPolicy(
    val backendResolved: Boolean,
    val standalone: Boolean,
    val targetRailEnabled: Boolean,
    val targetDisabledReason: String?,
    val coopCalibrationAvailable: Boolean,
    val coopDisabledReason: String?,
    val modePreviewExportAvailable: Boolean,
    val exportDisabledReason: String?,
)

/**
 * Capability policy for AR-only rail tools.
 *
 * The standalone backend owns target capture inside SphereSlamStandaloneOverlay, so the legacy
 * ARCore Target rail action must not arm MainViewModel's ARCore tap-to-anchor flow. Co-op uses the
 * protocol-v3 explicit host wall-frame descriptor, so standalone Host/Join are available once the
 * backend itself is resolved; incompatible metric/normalized pairings are rejected at handshake.
 */
internal fun arRailBackendPolicy(
    arCoreAvailabilityResolved: Boolean,
    arCoreAvailable: Boolean,
    sphereSlamAvailabilityResolved: Boolean,
    sphereSlamAvailable: Boolean,
): ArRailBackendPolicy {
    if (!arCoreAvailabilityResolved) {
        return ArRailBackendPolicy(
            backendResolved = false,
            standalone = false,
            targetRailEnabled = false,
            targetDisabledReason = "Checking AR backend…",
            coopCalibrationAvailable = false,
            coopDisabledReason = "Checking AR backend…",
            modePreviewExportAvailable = false,
            exportDisabledReason = "Checking AR backend…",
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
            modePreviewExportAvailable = true,
            exportDisabledReason = null,
        )
    }
    val standaloneRuntimeReady =
        sphereSlamAvailabilityResolved && sphereSlamAvailable
    return ArRailBackendPolicy(
        backendResolved = true,
        standalone = true,
        targetRailEnabled = false,
        targetDisabledReason = "Use the on-screen Wall Target capture",
        coopCalibrationAvailable = standaloneRuntimeReady,
        coopDisabledReason = when {
            !sphereSlamAvailabilityResolved -> "Checking standalone wall tracker…"
            !sphereSlamAvailable -> "Standalone wall tracking is unavailable on this build"
            else -> null
        },
        modePreviewExportAvailable = false,
        exportDisabledReason = "Standalone camera + overlay export is not implemented yet",
    )
}
