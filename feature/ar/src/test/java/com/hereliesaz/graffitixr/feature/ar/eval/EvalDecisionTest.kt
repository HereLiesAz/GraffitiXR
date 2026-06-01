package com.hereliesaz.graffitixr.feature.ar.eval

import org.junit.Assert.assertEquals
import org.junit.Test

class EvalDecisionTest {
    @Test
    fun `aggregate averages error and max stage time per mechanism`() {
        val rows = listOf(
            MechanismRun(MechanismId.SURFACE_MESH, errMm = 2f, jitterMm = 1f, availability = 1f, recoveryMs = 100, stageMs = 4f, uniqueCoverage = true),
            MechanismRun(MechanismId.CLOUD_OFFSET, errMm = 50f, jitterMm = 20f, availability = 1f, recoveryMs = null, stageMs = 0.1f, uniqueCoverage = false),
        )
        val report = EvalDecision.decide(rows)
        // Accuracy-first: redundant + no unique coverage -> drop, regardless of low cost.
        assertEquals(Verdict.DROP, report.first { it.id == MechanismId.CLOUD_OFFSET }.verdict)
        // Unique coverage -> keep, even though it's the costliest.
        assertEquals(Verdict.KEEP, report.first { it.id == MechanismId.SURFACE_MESH }.verdict)
    }

    @Test
    fun `cost never flips an accurate uniquely-covering mechanism to drop`() {
        val rows = listOf(
            MechanismRun(MechanismId.VOXEL_RELOC, errMm = 1f, jitterMm = 0.5f, availability = 1f, recoveryMs = 80, stageMs = 999f, uniqueCoverage = true),
        )
        assertEquals(Verdict.KEEP, EvalDecision.decide(rows).single().verdict)
    }
}
