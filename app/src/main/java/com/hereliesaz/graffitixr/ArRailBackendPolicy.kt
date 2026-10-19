package com.hereliesaz.graffitixr

import androidx.annotation.StringRes
import com.hereliesaz.graffitixr.design.R as DesignR

internal data class ArRailBackendPolicy(
    val backendResolved: Boolean,
    val standalone: Boolean,
    val doodleRailEnabled: Boolean,
    val coopCalibrationAvailable: Boolean,
    val modePreviewExportAvailable: Boolean,
    /**
     * Localized string resource explaining why AR-mode Export is unavailable, or null when
     * [modePreviewExportAvailable]. The rail shows it when Export is tapped in that state.
     */
    @StringRes val exportDisabledReason: Int?,
)

/**
 * Capability policy for AR-only rail tools.
 *
 * Target uses the shared capture flow (MainViewModel / DoodleCreationUi) on both backends; standalone
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
            doodleRailEnabled = false,
            coopCalibrationAvailable = false,
            modePreviewExportAvailable = false,
            exportDisabledReason = DesignR.string.rail_checking_ar_backend,
        )
    }
    if (arCoreAvailable) {
        return ArRailBackendPolicy(
            backendResolved = true,
            standalone = false,
            doodleRailEnabled = true,
            coopCalibrationAvailable = true,
            modePreviewExportAvailable = true,
            exportDisabledReason = null,
        )
    }
    val standaloneRuntimeReady =
        sphereSlamAvailabilityResolved && sphereSlamAvailable
    return ArRailBackendPolicy(
        backendResolved = true,
        standalone = true,
        doodleRailEnabled = standaloneRuntimeReady,
        coopCalibrationAvailable = standaloneRuntimeReady,
        modePreviewExportAvailable = false,
        exportDisabledReason = DesignR.string.export_standalone_ar_unavailable,
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
