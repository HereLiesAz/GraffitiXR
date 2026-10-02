package com.hereliesaz.graffitixr.feature.ar

import com.hereliesaz.graffitixr.common.model.CoopSpatialFrame
import com.hereliesaz.graffitixr.common.model.CoopSpatialScale
import com.hereliesaz.graffitixr.common.model.CoopTrackingBackend
import com.hereliesaz.graffitixr.feature.ar.anchor.MetricMarks
import com.hereliesaz.graffitixr.feature.ar.anchor.PoseMath
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoopPeerWallPoseTest {
    private fun spatial(bridge: FloatArray = identity()) = CoopSpatialFrame(
        hostBackend = CoopTrackingBackend.ARCORE,
        fingerprintFrameVersion = 1,
        scale = CoopSpatialScale.METRIC,
        fingerprintAvailable = true,
        anchorRevision = 7L,
        referenceWidthUnits = 2f,
        fingerprintFromWall = bridge.toList(),
    )

    @Test
    fun `peer PnP composes through fingerprint-from-wall bridge`() {
        val pnpCv = identity().also {
            it[12] = 0.25f
            it[13] = -0.4f
            it[14] = 2.0f
        }
        val fingerprintFromWall = identity().also {
            it[12] = 1.0f
            it[13] = 0.5f
        }
        val reloc = FloatArray(19)
        pnpCv.copyInto(reloc, 0)
        reloc[16] = 24f
        reloc[17] = 30f
        reloc[18] = 3f

        val maybeSolved = CoopPeerWallPoseSolver.solve(reloc, spatial(fingerprintFromWall))
        assertNotNull(maybeSolved)
        val solved = requireNotNull(maybeSolved)
        val expected = PoseMath.multiply(
            MetricMarks.glViewToCv(pnpCv),
            fingerprintFromWall,
        )
        assertArrayEquals(expected, solved.viewMatrix, 1e-6f)
    }

    @Test
    fun `weak peer PnP is rejected before it can move the wall`() {
        val reloc = FloatArray(19)
        identity().copyInto(reloc, 0)
        reloc[16] = 4f
        reloc[17] = 20f
        reloc[18] = 1f
        assertNull(CoopPeerWallPoseSolver.solve(reloc, spatial()))
    }

    @Test
    fun `peer pose hold expires at standalone visual bridge budget`() {
        assertTrue(shouldHoldCoopPeerPose(lastFreshSolveMs = 1_000L, nowMs = 1_400L))
        assertFalse(shouldHoldCoopPeerPose(lastFreshSolveMs = 1_000L, nowMs = 1_401L))
        assertFalse(shouldHoldCoopPeerPose(lastFreshSolveMs = Long.MIN_VALUE, nowMs = 1_000L))
        assertFalse(shouldHoldCoopPeerPose(lastFreshSolveMs = 2_000L, nowMs = 1_999L))
    }

    @Test
    fun `ARCore host peer fingerprint selection ignores stale local page state`() {
        val frame = spatial()
        assertTrue(shouldUseCoopPeerFingerprint(frame, byteArrayOf(1, 2, 3)))
        assertFalse(shouldUseCoopPeerFingerprint(frame, byteArrayOf()))
        assertFalse(
            shouldUseCoopPeerFingerprint(
                frame.copy(hostBackend = CoopTrackingBackend.SPHERESLAM),
                byteArrayOf(1),
            )
        )
    }

    private fun identity() = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f,
    )
}
