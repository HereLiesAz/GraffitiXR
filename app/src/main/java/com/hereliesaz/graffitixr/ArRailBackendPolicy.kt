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
 * The standalone backend owns target capture inside SphereSlamStandaloneOverlay, so its Target rail
 * action starts a new target there (StandaloneOverlayUiHost) instead of arming MainViewModel's ARCore
 * tap-to-anchor flow. It is enabled once the standalone runtime is ready — without it, a stale or
 * wrong target had no way out. Co-op uses the
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
        targetRailEnabled = standaloneRuntimeReady,
        targetDisabledReason = when {
            !sphereSlamAvailabilityResolved -> "Checking standalone wall tracker…"
            !sphereSlamAvailable -> "Standalone wall tracking is unavailable on this build"
            else -> null
        },
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

internal fun coopHostReady(
    policy: ArRailBackendPolicy,
    arCoreAnchorEstablished: Boolean,
    arCorePointCount: Int,
    standaloneReferenceRegistered: Boolean,
): Boolean =
    if (policy.standalone) {
        policy.coopCalibrationAvailable && standaloneReferenceRegistered
    } else {
        policy.coopCalibrationAvailable && arCoreAnchorEstablished && arCorePointCount > 0
    }
