package com.hereliesaz.graffitixr.feature.ar.anchor

import com.hereliesaz.sphereslam.SphereSlamPoseMath
import com.hereliesaz.sphereslam.SphereSlamTracker
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
        val decision = HybridKpmCorrection.solve(
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
        val accepted = HybridKpmCorrection.solve(
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
            HybridKpmCorrection.solve(good, geometry, false, identity(), history, 1_000_000_010L).reject,
        )
        assertEquals(
            HybridKpmCorrection.Reject.STALE,
            HybridKpmCorrection.solve(good, geometry, true, identity(), history, 1_600_000_001L).reject,
        )
        assertEquals(
            HybridKpmCorrection.Reject.TOO_FEW_INLIERS,
            HybridKpmCorrection.solve(good.copy(inliers = 3), geometry, true, identity(), history, 1_000_000_010L).reject,
        )
        assertEquals(
            HybridKpmCorrection.Reject.BAD_REPROJECTION,
            HybridKpmCorrection.solve(good.copy(error = 4.1f), geometry, true, identity(), history, 1_000_000_010L).reject,
        )
    }

    @Test
    fun `missing timestamp pair is refused rather than using render-time camera pose`() {
        val history = HybridPoseHistory()
        history.add(1_000L, identity(), translated(0f,0f,-2f))
        val decision = HybridKpmCorrection.solve(
            frontObservation(100_000_000L, 2_000f),
            geometry,
            true,
            identity(),
            history,
            100_000_010L,
        )
        assertEquals(HybridKpmCorrection.Reject.NO_POSE_PAIR, decision.reject)
    }

    private fun frontObservation(timestampNs: Long, zMm: Float) = SphereSlamTracker.Observation(
        timestampNs = timestampNs,
        pageNo = 0,
        error = 1f,
        inliers = 24,
        pageToCamera3x4 = floatArrayOf(
            1f, 0f, 0f, -500f,
            0f, -1f, 0f, 500f,
            0f, 0f, -1f, zMm,
        ),
    )

    private fun identity() = floatArrayOf(
        1f,0f,0f,0f, 0f,1f,0f,0f, 0f,0f,1f,0f, 0f,0f,0f,1f,
    )
    private fun translated(x: Float, y: Float, z: Float) = identity().also {
        it[12] = x; it[13] = y; it[14] = z
    }
}
