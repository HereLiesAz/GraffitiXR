package com.hereliesaz.graffitixr.feature.ar.eval

enum class Verdict { KEEP, DROP }

/** Aggregated metrics for one mechanism across a session/playback run. */
data class MechanismRun(
    val id: MechanismId,
    val errMm: Float,
    val jitterMm: Float,
    val availability: Float,
    val recoveryMs: Long?,
    val stageMs: Float,
    val uniqueCoverage: Boolean, // does it cover a failure mode no kept mechanism does?
)

data class MechanismVerdict(val id: MechanismId, val verdict: Verdict, val rationale: String)

/**
 * Accuracy-paramount rubric (spec "Guiding principle"): a mechanism is KEPT if it is effective OR
 * provides unique failure-mode coverage. It is DROPPED only when it is BOTH redundant (no unique
 * coverage) AND not meaningfully more accurate than the survivors. Cost is reported but never flips
 * an accurate or uniquely-covering mechanism to DROP.
 */
object EvalDecision {
    private const val GOOD_ERR_MM = 10f // <=1 cm error is "effective" at mural scale

    fun decide(runs: List<MechanismRun>): List<MechanismVerdict> = runs.map { r ->
        val effective = r.errMm in 0f..GOOD_ERR_MM && r.availability > 0f
        val keep = r.uniqueCoverage || effective
        MechanismVerdict(
            id = r.id,
            verdict = if (keep) Verdict.KEEP else Verdict.DROP,
            rationale = buildString {
                append("err=${r.errMm}mm jitter=${r.jitterMm}mm avail=${r.availability} ")
                append("recovery=${r.recoveryMs ?: "n/a"}ms cost=${r.stageMs}ms ")
                append(if (r.uniqueCoverage) "uniqueCoverage " else "redundant ")
                append(if (keep) "-> KEEP" else "-> DROP (redundant & not more accurate)")
            },
        )
    }
}
