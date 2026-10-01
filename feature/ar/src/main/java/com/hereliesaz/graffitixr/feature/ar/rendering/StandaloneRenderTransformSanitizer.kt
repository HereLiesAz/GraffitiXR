package com.hereliesaz.graffitixr.feature.ar.rendering

internal data class StandaloneRenderTransform(
    val panX: Float,
    val panY: Float,
    val scale: Float,
    val rotationZDeg: Float,
    val rotationXDeg: Float,
    val rotationYDeg: Float,
)

internal object StandaloneRenderTransformSanitizer {
    const val MIN_SCALE = 0.1f
    const val MAX_SCALE = 10f
    const val MAX_ABS_PAN = 10_000f

    fun sanitize(
        panX: Float,
        panY: Float,
        scale: Float,
        rotationZDeg: Float,
        rotationXDeg: Float,
        rotationYDeg: Float,
    ): StandaloneRenderTransform = StandaloneRenderTransform(
        panX = finitePan(panX),
        panY = finitePan(panY),
        scale = if (scale.isFinite()) scale.coerceIn(MIN_SCALE, MAX_SCALE) else 1f,
        rotationZDeg = normalizeDegrees(rotationZDeg),
        rotationXDeg = normalizeDegrees(rotationXDeg),
        rotationYDeg = normalizeDegrees(rotationYDeg),
    )

    private fun finitePan(value: Float): Float =
        if (value.isFinite()) value.coerceIn(-MAX_ABS_PAN, MAX_ABS_PAN) else 0f

    private fun normalizeDegrees(value: Float): Float {
        if (!value.isFinite()) return 0f
        var normalized = value % 360f
        if (normalized > 180f) normalized -= 360f
        if (normalized < -180f) normalized += 360f
        return normalized
    }
}
