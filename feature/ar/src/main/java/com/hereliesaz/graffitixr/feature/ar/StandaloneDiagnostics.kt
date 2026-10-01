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

internal fun standaloneDiagnosticDump(
    calibration: StandaloneCalibrationDiagnostics?,
    trackingState: StandaloneTrackingState,
    match: StandaloneMatchDiagnostics?,
    physicallyMetric: Boolean,
    referenceWidthUnits: Float,
    failure: StandaloneFailureEvent?,
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
    }.trimEnd()
}
