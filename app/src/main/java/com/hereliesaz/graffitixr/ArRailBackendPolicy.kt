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
 * Target uses the shared capture flow (MainViewModel / TargetCreationUi) on both backends; standalone
 * enables it once its runtime is ready — without it, a stale or wrong target had no way out (the
 * current target keeps tracking until the replacement is saved). A standalone co-op guest gets no
 * Target (MainActivity), since it must keep the session's frame. Co-op uses the
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
