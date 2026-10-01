package com.hereliesaz.graffitixr.feature.ar

import com.hereliesaz.graffitixr.common.model.CoopSpatialFrame
import com.hereliesaz.graffitixr.common.model.CoopSpatialScale
import com.hereliesaz.graffitixr.common.model.CoopTrackingBackend
import com.hereliesaz.graffitixr.feature.ar.anchor.MetricMarks
import com.hereliesaz.graffitixr.feature.ar.anchor.PoseMath
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
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

        val solved = assertNotNull(CoopPeerWallPoseSolver.solve(reloc, spatial(fingerprintFromWall)))
            .let { requireNotNull(it) }
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

    private fun identity() = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f,
    )
}
