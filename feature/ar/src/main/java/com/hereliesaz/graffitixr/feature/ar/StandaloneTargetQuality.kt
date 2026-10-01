package com.hereliesaz.graffitixr.feature.ar

enum class StandaloneTargetQualityIssue {
    TOO_SMALL,
    LOW_CONTRAST,
    BLURRY,
    TOO_DARK,
    TOO_BRIGHT,
}

data class StandaloneTargetQualityConfig(
    val minDimensionPx: Int = 128,
    val minPixels: Int = 65_536,
    val minLumaStdDev: Float = 12f,
    val minLaplacianVariance: Float = 40f,
    val maxClippedFraction: Float = 0.60f,
    val minKpmFeatures: Int = 16,
) {
    init {
        require(minDimensionPx > 0)
        require(minPixels > 0)
        require(minLumaStdDev > 0f)
        require(minLaplacianVariance > 0f)
        require(maxClippedFraction in 0f..1f)
        require(minKpmFeatures >= 4)
    }
}

data class StandaloneTargetQualityReport(
    val blockingIssues: Set<StandaloneTargetQualityIssue>,
    val warnings: Set<StandaloneTargetQualityIssue>,
    val lumaStdDev: Float,
    val laplacianVariance: Float,
    val darkFraction: Float,
    val brightFraction: Float,
) {
    val acceptable: Boolean get() = blockingIssues.isEmpty()
}

class StandaloneReferenceTooWeakException(
    val featureCount: Int,
    val minimumFeatureCount: Int,
) : IllegalStateException(
    "KPM generated only $featureCount reference features; need at least $minimumFeatureCount",
)

internal object StandaloneTargetQuality {
    fun analyze(
        luma: ByteArray,
        width: Int,
        height: Int,
        config: StandaloneTargetQualityConfig = StandaloneTargetQualityConfig(),
    ): StandaloneTargetQualityReport {
        require(width > 0 && height > 0)
        require(luma.size == width * height)

        val blocking = linkedSetOf<StandaloneTargetQualityIssue>()
        val warnings = linkedSetOf<StandaloneTargetQualityIssue>()
        if (
            width < config.minDimensionPx ||
            height < config.minDimensionPx ||
            width.toLong() * height.toLong() < config.minPixels
        ) {
            blocking += StandaloneTargetQualityIssue.TOO_SMALL
        }

        var sum = 0.0
        var sumSq = 0.0
        var dark = 0
        var bright = 0
        for (b in luma) {
            val v = b.toInt() and 0xff
            sum += v
            sumSq += v.toDouble() * v.toDouble()
            if (v <= 8) dark++
            if (v >= 247) bright++
        }
        val n = luma.size.toDouble()
        val mean = sum / n
        val variance = (sumSq / n - mean * mean).coerceAtLeast(0.0)
        val stdDev = kotlin.math.sqrt(variance).toFloat()

        var lapSum = 0.0
        var lapSq = 0.0
        var lapCount = 0
        if (width >= 3 && height >= 3) {
            for (y in 1 until height - 1) {
                val row = y * width
                for (x in 1 until width - 1) {
                    val i = row + x
                    val c = luma[i].toInt() and 0xff
                    val lap =
                        4 * c -
                            (luma[i - 1].toInt() and 0xff) -
                            (luma[i + 1].toInt() and 0xff) -
                            (luma[i - width].toInt() and 0xff) -
                            (luma[i + width].toInt() and 0xff)
                    lapSum += lap
                    lapSq += lap.toDouble() * lap.toDouble()
                    lapCount++
                }
            }
        }
        val lapMean = if (lapCount > 0) lapSum / lapCount else 0.0
        val lapVariance = if (lapCount > 0) {
            (lapSq / lapCount - lapMean * lapMean).coerceAtLeast(0.0).toFloat()
        } else {
            0f
        }

        val darkFraction = dark.toFloat() / luma.size.toFloat()
        val brightFraction = bright.toFloat() / luma.size.toFloat()

        if (stdDev < config.minLumaStdDev) blocking += StandaloneTargetQualityIssue.LOW_CONTRAST
        if (lapVariance < config.minLaplacianVariance) blocking += StandaloneTargetQualityIssue.BLURRY
        if (darkFraction > config.maxClippedFraction) warnings += StandaloneTargetQualityIssue.TOO_DARK
        if (brightFraction > config.maxClippedFraction) warnings += StandaloneTargetQualityIssue.TOO_BRIGHT

        return StandaloneTargetQualityReport(
            blockingIssues = blocking,
            warnings = warnings,
            lumaStdDev = stdDev,
            laplacianVariance = lapVariance,
            darkFraction = darkFraction,
            brightFraction = brightFraction,
        )
    }

    fun blockingMessage(report: StandaloneTargetQualityReport): String? {
        if (report.acceptable) return null
        return when {
            StandaloneTargetQualityIssue.TOO_SMALL in report.blockingIssues ->
                "That wall target is too small after rectification. Capture a larger patch."
            StandaloneTargetQualityIssue.LOW_CONTRAST in report.blockingIssues ->
                "That wall target has too little visual contrast. Include more texture or detail."
            StandaloneTargetQualityIssue.BLURRY in report.blockingIssues ->
                "That wall target is too blurry for reliable tracking. Hold steady and recapture."
            else -> "That wall target is not strong enough for reliable tracking."
        }
    }

    fun warningMessage(report: StandaloneTargetQualityReport): String? = when {
        StandaloneTargetQualityIssue.TOO_DARK in report.warnings ->
            "This target is heavily underexposed; tracking may be less reliable."
        StandaloneTargetQualityIssue.TOO_BRIGHT in report.warnings ->
            "This target is heavily overexposed; tracking may be less reliable."
        else -> null
    }
}
