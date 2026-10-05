package com.hereliesaz.graffitixr.feature.ar

internal data class StandaloneCalibrationDiagnostics(
    val cameraId: String,
    val timestampSource: StandaloneCameraTimestampSource,
    val rawWidth: Int,
    val rawHeight: Int,
    val cropLeft: Int,
    val cropTop: Int,
    val cropWidth: Int,
    val cropHeight: Int,
    val displayWidth: Int,
    val displayHeight: Int,
    val rotationDegrees: Int,
    val fx: Float,
    val fy: Float,
    val cx: Float,
    val cy: Float,
)

internal data class StandaloneMatchDiagnostics(
    val pageNo: Int,
    val inliers: Int,
    val reprojectionError: Float,
    val observationAgeMs: Float?,
    val matchDurationMs: Float,
    val source: SphereSlamStandalonePoseSource,
)

/**
 * Sphere map Phase 4 (docs/SPHERESLAM_SPHERE_MAP.md): the live state of the spherical-coverage map,
 * for the on-device tuning instrument. All fields are present only when the feature-map flag is on;
 * [relocVisible]/[relocCorr] are -1 when the last attempt's map path did not run.
 */
internal data class SphereMapDiagnostics(
    val enabled: Boolean,
    val pointCount: Int,
    val revision: Long,
    val relocVisible: Int,
    val relocCorr: Int,
    val sweepCoverage: Float,
)

internal fun standaloneDiagnosticDump(
    calibration: StandaloneCalibrationDiagnostics?,
    trackingState: StandaloneTrackingState,
    match: StandaloneMatchDiagnostics?,
    physicallyMetric: Boolean,
    referenceWidthUnits: Float,
    failure: StandaloneFailureEvent?,
    sphereMap: SphereMapDiagnostics? = null,
): String {
    fun f(value: Float?): String =
        value?.takeIf { it.isFinite() }
            ?.let { java.lang.String.format(java.util.Locale.US, "%.3f", it) }
            ?: "unavailable"

    return buildString {
        appendLine("backend=SPHERESLAM_STANDALONE")
        appendLine("state=${trackingState.name}")
        appendLine("cameraId=${calibration?.cameraId ?: "unavailable"}")
        appendLine("timestampSource=${calibration?.timestampSource?.name ?: "unavailable"}")
        appendLine(
            "rawFrame=" +
                calibration?.let { "${it.rawWidth}x${it.rawHeight}" }.orEmpty()
                    .ifEmpty { "unavailable" },
        )
        appendLine(
            "crop=" +
                calibration?.let { "${it.cropLeft},${it.cropTop} ${it.cropWidth}x${it.cropHeight}" }
                    .orEmpty().ifEmpty { "unavailable" },
        )
        appendLine(
            "displayFrame=" +
                calibration?.let { "${it.displayWidth}x${it.displayHeight}" }.orEmpty()
                    .ifEmpty { "unavailable" },
        )
        appendLine("rotationDegrees=${calibration?.rotationDegrees ?: "unavailable"}")
        appendLine("fx=${f(calibration?.fx)} fy=${f(calibration?.fy)}")
        appendLine("cx=${f(calibration?.cx)} cy=${f(calibration?.cy)}")
        appendLine("pageId=${match?.pageNo ?: "unavailable"}")
        appendLine("poseSource=${match?.source?.name ?: "unavailable"}")
        appendLine("inliers=${match?.inliers ?: "unavailable"}")
        appendLine("reprojectionError=${f(match?.reprojectionError)}")
        appendLine("observationAgeMs=${f(match?.observationAgeMs)}")
        appendLine("matchDurationMs=${f(match?.matchDurationMs)}")
        appendLine("physicalScale=$physicallyMetric")
        appendLine("referenceWidthUnits=${f(referenceWidthUnits)}")
        appendLine("failure=${failure?.reason?.name ?: "none"}")
        if (failure != null && failure.diagnostic.isNotBlank()) {
            appendLine("failureDetail=${failure.diagnostic}")
        }
        if (sphereMap != null) {
            appendLine("sphereMap=${if (sphereMap.enabled) "on" else "off"}")
            appendLine("sphereMapPoints=${sphereMap.pointCount}")
            appendLine("sphereMapRevision=${sphereMap.revision}")
            // Map-carried reloc: visible=frustum-gated candidates, corr=correspondences fed to PnP.
            // corr>0 with the marks off-frame is the surrounding sphere holding the lock.
            appendLine("sphereMapRelocVisible=${sphereMap.relocVisible}")
            appendLine("sphereMapRelocCorr=${sphereMap.relocCorr}")
            appendLine("sphereSweepCoverage=${f(sphereMap.sweepCoverage)}")
        }
    }.trimEnd()
}
