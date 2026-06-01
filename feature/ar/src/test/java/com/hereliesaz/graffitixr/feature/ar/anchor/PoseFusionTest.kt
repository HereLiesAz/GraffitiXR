package com.hereliesaz.graffitixr.feature.ar.anchor

import org.junit.Assert.assertEquals
import org.junit.Test

class PoseFusionTest {
    private fun identity() = floatArrayOf(1f,0f,0f,0f, 0f,1f,0f,0f, 0f,0f,1f,0f, 0f,0f,0f,1f)
    private fun trans(x: Float, y: Float, z: Float) =
        floatArrayOf(1f,0f,0f,0f, 0f,1f,0f,0f, 0f,0f,1f,0f, x,y,z,1f)

    @Test fun `composeCorrected with V=pnp and fpAnchor=I yields identity`() {
        val v = trans(2f, 0f, 0f)
        val r = PoseFusion.composeCorrected(vCurrent = v, pnpMat = v, fpAnchor = identity())
        identity().forEachIndexed { i, e -> assertEquals(e, r[i], 1e-4f) }
    }

    @Test fun `blend alpha 0 returns current, alpha 1 returns target`() {
        val cur = trans(0f,0f,0f); val tgt = trans(10f,0f,0f)
        assertEquals(0f, PoseFusion.blend(cur, tgt, 0f)[12], 1e-4f)
        assertEquals(10f, PoseFusion.blend(cur, tgt, 1f)[12], 1e-4f)
        assertEquals(5f, PoseFusion.blend(cur, tgt, 0.5f)[12], 1e-4f)
    }

    @Test fun `fusion returns backbone when no new reloc result`() {
        val f = PoseFusion()
        val backbone = trans(1f,1f,1f)
        val out = f.currentAnchor(backbone, identity(), reloc = FloatArray(19), fpAnchor = identity(), confGlobal = 1f)
        backbone.forEachIndexed { i, e -> assertEquals(e, out[i], 1e-4f) }
    }

    @Test fun `fusion ignores low-inlier-ratio snaps`() {
        val f = PoseFusion()
        val backbone = trans(0f,0f,0f)
        val reloc = FloatArray(19).also {
            System.arraycopy(trans(99f,0f,0f), 0, it, 0, 16); it[16] = 1f; it[17] = 100f; it[18] = 1f
        }
        val out = f.currentAnchor(backbone, identity(), reloc, fpAnchor = identity(), confGlobal = 1f)
        assertEquals(0f, out[12], 1e-3f)
    }

    @Test fun `fusion moves toward correction on a confident new snap`() {
        val f = PoseFusion()
        val backbone = trans(0f,0f,0f)
        val reloc = FloatArray(19).also {
            System.arraycopy(trans(10f,0f,0f), 0, it, 0, 16); it[16] = 90f; it[17] = 100f; it[18] = 1f
        }
        val out = f.currentAnchor(backbone, identity(), reloc, fpAnchor = identity(), confGlobal = 1f)
        assert(out[12] > 0f && out[12] < 10f) { "expected partial move, got ${out[12]}" }
    }
}
