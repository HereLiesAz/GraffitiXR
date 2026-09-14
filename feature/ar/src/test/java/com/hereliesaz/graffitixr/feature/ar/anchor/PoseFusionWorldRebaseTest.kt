package com.hereliesaz.graffitixr.feature.ar.anchor

import org.junit.Assert.assertEquals
import org.junit.Test

class PoseFusionWorldRebaseTest {

    private fun identity() = floatArrayOf(
        1f,0f,0f,0f,
        0f,1f,0f,0f,
        0f,0f,1f,0f,
        0f,0f,0f,1f,
    )

    private fun trans(x: Float, y: Float, z: Float) = floatArrayOf(
        1f,0f,0f,0f,
        0f,1f,0f,0f,
        0f,0f,1f,0f,
        x,y,z,1f,
    )

    private fun rotZ90() = floatArrayOf(
        0f,1f,0f,0f,
        -1f,0f,0f,0f,
        0f,0f,1f,0f,
        0f,0f,0f,1f,
    )

    private fun captureAtOrigin() = floatArrayOf(
        1f,0f,0f,0f,
        0f,-1f,0f,0f,
        0f,0f,-1f,0f,
        0f,0f,0f,1f,
    )

    private fun reloc(target: FloatArray, seq: Float) = FloatArray(19).also {
        System.arraycopy(target, 0, it, 0, 16)
        it[16] = 90f
        it[17] = 100f
        it[18] = seq
    }

    @Test
    fun `standing correction follows an ARCore world-frame rebase instead of staying world-fixed`() {
        val fusion = PoseFusion()

        // Current backbone is identity. A confident relock says the corrected physical pose is +1 m
        // along the anchor's local X, so the stored local correction becomes T(+1,0,0).
        val first = fusion.currentAnchor(
            backbone = identity(),
            vCurrent = identity(),
            reloc = reloc(trans(1f, 0f, 0f), seq = 1f),
            captureAnchorCam = captureAtOrigin(),
            confGlobal = 1f,
        )
        assertEquals(1f, first[12], 1e-4f)
        assertEquals(0f, first[13], 1e-4f)

        // ARCore then rebases the numerical world frame by +90° about Z. No new PnP result arrives.
        // The physical +X correction must rotate WITH the backbone into world +Y: Rz90 * Tx(1).
        // The old world-space left correction did Tx(1) * Rz90 and incorrectly stayed at world +X.
        val rebased = fusion.currentAnchor(
            backbone = rotZ90(),
            vCurrent = identity(),
            reloc = FloatArray(19),
            captureAnchorCam = captureAtOrigin(),
            confGlobal = 1f,
        )

        assertEquals(0f, rebased[12], 1e-4f)
        assertEquals(1f, rebased[13], 1e-4f)
        assertEquals(0f, rebased[14], 1e-4f)
    }
}
