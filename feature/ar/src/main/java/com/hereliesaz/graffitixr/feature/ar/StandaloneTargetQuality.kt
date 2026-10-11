@file:OptIn(com.hereliesaz.sphereslam.reloc.ExperimentalSphereSlamRelocApi::class)

package com.hereliesaz.graffitixr.feature.ar

import com.hereliesaz.sphereslam.reloc.TargetQuality
import com.hereliesaz.sphereslam.reloc.TargetQualityIssue
import com.hereliesaz.sphereslam.reloc.TargetQualityReport

/*
 * The target quality gate (size, contrast, sharpness, clipping) and the KPM feature floor are
 * SphereSLAM's (`reloc.TargetQuality`, ported from this file). The library reports issues only; the
 * app's on-screen wording stays here.
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

    fun blockingMessage(report: StandaloneTargetQualityReport): String? {
        if (report.acceptable) return null
        return when {
            TargetQualityIssue.TOO_SMALL in report.blockingIssues ->
                "That wall target is too small after rectification. Capture a larger patch."
            TargetQualityIssue.LOW_CONTRAST in report.blockingIssues ->
                "That wall target has too little visual contrast. Include more texture or detail."
            TargetQualityIssue.BLURRY in report.blockingIssues ->
                "That wall target is too blurry for reliable tracking. Hold steady and recapture."
            else -> "That wall target is not strong enough for reliable tracking."
        }
    }

    fun warningMessage(report: StandaloneTargetQualityReport): String? = when {
        TargetQualityIssue.TOO_DARK in report.warnings ->
            "This target is heavily underexposed; tracking may be less reliable."
        TargetQualityIssue.TOO_BRIGHT in report.warnings ->
            "This target is heavily overexposed; tracking may be less reliable."
        else -> null
    }
}
