package com.hereliesaz.graffitixr.feature.ar.anchor

import com.hereliesaz.graffitixr.common.model.HybridKpmOutcome
import com.hereliesaz.sphereslam.SphereSlamPoseMath
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class HybridKpmCorrectionTest {
    private val geometry = SphereSlamPoseMath.pageGeometry(1000, 1000, 25.4f)

    @Test
    fun `zero drift observation reproduces the solve-time artwork anchor`() {
        val history = HybridPoseHistory()
        val backbone = translated(0f, 0f, -2f)
        history.add(1_000L, identity(), backbone)
        val decision = solve(
            observation = frontObservation(timestampNs = 1_000L, zMm = 2_000f),
            pageGeometry = geometry,
            physicallyMetric = true,
            pageFromAnchor = identity(),
            poseHistory = history,
            currentFrameTimestampNs = 1_050L,
        )
        assertNotNull(decision.accepted)
        assertNull(decision.reject)
        assertArrayEquals(backbone, decision.accepted!!.correctedAnchorWorld, 1e-4f)
    }

    @Test
    fun `known ARCore translation drift produces the physical KPM anchor`() {
        val history = HybridPoseHistory()
        val driftedBackbone = translated(0f, 0f, -2.3f)
        history.add(2_000L, identity(), driftedBackbone)
        val accepted = solve(
            frontObservation(2_000L, 2_000f),
            geometry,
            true,
            identity(),
            history,
            2_100L,
        ).accepted!!
        assertEquals(-2f, accepted.correctedAnchorWorld[14], 1e-4f)
        assertEquals(-2.3f, accepted.backboneAtObservation[14], 1e-4f)
    }

    @Test
    fun `non metric stale weak and high-error observations fail closed`() {
        val history = HybridPoseHistory()
        history.add(1_000_000_000L, identity(), translated(0f,0f,-2f))
        val good = frontObservation(1_000_000_000L, 2_000f)
        assertEquals(
            HybridKpmCorrection.Reject.NON_METRIC,
            solve(good, geometry, false, identity(), history, 1_000_000_010L).reject,
        )
        assertEquals(
            HybridKpmCorrection.Reject.STALE,
            solve(good, geometry, true, identity(), history, 1_600_000_001L).reject,
        )
        assertEquals(
            HybridKpmCorrection.Reject.TOO_FEW_INLIERS,
            solve(good.copy(inliers = 3), geometry, true, identity(), history, 1_000_000_010L).reject,
        )
        assertEquals(
            HybridKpmCorrection.Reject.BAD_REPROJECTION,
            solve(good.copy(error = 4.1f), geometry, true, identity(), history, 1_000_000_010L).reject,
        )
    }

    @Test
    fun `missing timestamp pair is refused rather than using render-time camera pose`() {
        val history = HybridPoseHistory()
        history.add(1_000L, identity(), translated(0f,0f,-2f))
        val decision = solve(
            frontObservation(100_000_000L, 2_000f),
            geometry,
            true,
            identity(),
            history,
            100_000_010L,
        )
        assertEquals(HybridKpmCorrection.Reject.NO_POSE_PAIR, decision.reject)
    }

    @Test
    fun `accepted decision carries the full diagnostic payload`() {
        val history = HybridPoseHistory()
        history.add(2_000_000L, identity(), translated(0f, 0f, -2.3f))
        val decision = solve(frontObservation(2_000_000L, 2_000f), geometry, true, identity(), history, 12_000_000L)
        val d = decision.diagnostics
        assertEquals(HybridKpmOutcome.ACCEPTED, d.outcome)
        assertEquals(2_000_000L, d.observationTimestampNs)
        assertEquals(10f, d.ageMs, 1e-4f)          // (12_000_000 - 2_000_000) ns
        assertEquals(24, d.inliers)
        assertEquals(1f, d.reprojectionPx, 1e-6f)
        assertEquals(300f, d.correctionMm, 0.1f)   // backbone at -2.3 m, KPM page at -2.0 m
        assertEquals(0f, d.correctionDeg, 0.1f)
    }

    @Test
    fun `an implausibly large correction is refused outright`() {
        val history = HybridPoseHistory()
        // KPM says -2.0 m, ARCore's anchor sits at -3.5 m: a 1.5 m "drift" is a wrong wall.
        history.add(1_000L, identity(), translated(0f, 0f, -3.5f))
        val decision = solve(frontObservation(1_000L, 2_000f), geometry, true, identity(), history, 1_050L)
        assertNull(decision.accepted)
        assertEquals(HybridKpmCorrection.Reject.CORRECTION_TOO_LARGE, decision.reject)
        assertEquals(HybridKpmOutcome.CORRECTION_TOO_LARGE, decision.diagnostics.outcome)
        assertEquals(1500f, decision.diagnostics.correctionMm, 0.1f)
    }

    @Test
    fun `a correction just under the ceiling is still accepted`() {
        val history = HybridPoseHistory()
        history.add(1_000L, identity(), translated(0f, 0f, -2.99f))  // 0.99 m
        val decision = solve(frontObservation(1_000L, 2_000f), geometry, true, identity(), history, 1_050L)
        assertNotNull(decision.accepted)
    }

    @Test
    fun `an early reject reports what was known and leaves later fields unset`() {
        val history = HybridPoseHistory()
        history.add(1_000L, identity(), translated(0f, 0f, -2f))
        val d = solve(frontObservation(1_000L, 2_000f).copy(inliers = 3), geometry, true, identity(), history, 1_050L)
            .diagnostics
        assertEquals(HybridKpmOutcome.TOO_FEW_INLIERS, d.outcome)
        assertEquals(3, d.inliers)
        assertEquals(-1f, d.correctionMm, 0f)
        assertEquals(-1f, d.correctionDeg, 0f)
    }

    private data class TestObservation(
        val timestampNs: Long,
        val error: Float,
        val inliers: Int,
        val cameraFromPage3x4: FloatArray,
    )

    private fun frontObservation(timestampNs: Long, zMm: Float) = TestObservation(
        timestampNs = timestampNs,
        error = 1f,
        inliers = 24,
        cameraFromPage3x4 = floatArrayOf(
            1f, 0f, 0f, -500f,
            0f, -1f, 0f, 500f,
            0f, 0f, -1f, zMm,
        ),
    )

    private fun solve(
        observation: TestObservation,
        pageGeometry: SphereSlamPoseMath.PageGeometry?,
        physicallyMetric: Boolean,
        pageFromAnchor: FloatArray?,
        poseHistory: HybridPoseHistory,
        currentFrameTimestampNs: Long,
    ) = HybridKpmCorrection.solveRaw(
        timestampNs = observation.timestampNs,
        inliers = observation.inliers,
        reprojectionError = observation.error,
        cameraFromPage3x4 = observation.cameraFromPage3x4,
        pageGeometry = pageGeometry,
        physicallyMetric = physicallyMetric,
        pageFromAnchor = pageFromAnchor,
        poseHistory = poseHistory,
        currentFrameTimestampNs = currentFrameTimestampNs,
    )

    private fun identity() = floatArrayOf(
        1f,0f,0f,0f, 0f,1f,0f,0f, 0f,0f,1f,0f, 0f,0f,0f,1f,
    )
    private fun translated(x: Float, y: Float, z: Float) = identity().also {
        it[12] = x; it[13] = y; it[14] = z
    }
}
