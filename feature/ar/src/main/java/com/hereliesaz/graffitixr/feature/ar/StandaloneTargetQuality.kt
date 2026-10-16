@file:OptIn(com.hereliesaz.sphereslam.reloc.ExperimentalSphereSlamRelocApi::class)

package com.hereliesaz.graffitixr.feature.ar

import com.hereliesaz.sphereslam.reloc.TargetQuality
import com.hereliesaz.sphereslam.reloc.TargetQualityIssue
import com.hereliesaz.sphereslam.reloc.TargetQualityReport

/*
 * The target quality gate (size, contrast, sharpness, clipping) and the KPM feature floor are
 * SphereSLAM's (`reloc.TargetQuality`, ported from this file). The library reports issues only; the
 * app's on-screen wording is a typed [StandaloneTextKey], localized in `core/design`.
 */
typealias StandaloneTargetQualityIssue = TargetQualityIssue
typealias StandaloneTargetQualityConfig = com.hereliesaz.sphereslam.reloc.TargetQualityConfig
typealias StandaloneTargetQualityReport = TargetQualityReport
typealias StandaloneReferenceTooWeakException = com.hereliesaz.sphereslam.reloc.ReferenceTooWeakException

internal object StandaloneTargetQuality {
    fun analyze(
        luma: ByteArray,
        width: Int,
        height: Int,
        config: StandaloneTargetQualityConfig = StandaloneTargetQualityConfig(),
    ): StandaloneTargetQualityReport = TargetQuality.analyze(luma, width, height, config)

    /** The text for the first blocking issue, or null when the target is acceptable. */
    fun blockingText(report: StandaloneTargetQualityReport): StandaloneText? {
        if (report.acceptable) return null
        return StandaloneText(
            when {
                TargetQualityIssue.TOO_SMALL in report.blockingIssues -> StandaloneTextKey.QUALITY_TOO_SMALL
                TargetQualityIssue.LOW_CONTRAST in report.blockingIssues -> StandaloneTextKey.QUALITY_LOW_CONTRAST
                TargetQualityIssue.BLURRY in report.blockingIssues -> StandaloneTextKey.QUALITY_BLURRY
                else -> StandaloneTextKey.QUALITY_NOT_STRONG_ENOUGH
            }
        )
    }

    fun warningText(report: StandaloneTargetQualityReport): StandaloneText? = when {
        TargetQualityIssue.TOO_DARK in report.warnings -> StandaloneText(StandaloneTextKey.QUALITY_TOO_DARK)
        TargetQualityIssue.TOO_BRIGHT in report.warnings -> StandaloneText(StandaloneTextKey.QUALITY_TOO_BRIGHT)
        else -> null
    }
}
