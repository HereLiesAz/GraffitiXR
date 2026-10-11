package com.hereliesaz.graffitixr.feature.ar

import com.hereliesaz.graffitixr.common.model.CoverageGlowStyle
import com.hereliesaz.sphereslam.SphereCoverage
import com.hereliesaz.sphereslam.overlay.CoverageGlowRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class CoverageGlowStyleMappingTest {

    private val current = listOf(SphereCoverage.TileRegion(0f, 30f, 0f, 30f))
    private val needing = listOf(SphereCoverage.TileRegion(30f, 60f, 0f, 30f))

    @Test
    fun `every offered style resolves to a fill mode the pinned SphereSLAM actually has`() {
        // Guards "don't expose a non-working option": a style naming a mode the library lacks fails here.
        CoverageGlowStyle.entries.forEach { style ->
            assertEquals(style, CoverageGlowStyleMapping.effectiveStyle(style))
            assertEquals(style.rendererFillMode, CoverageGlowStyleMapping.fillModeFor(style).name)
        }
    }

    @Test
    fun `wash is the complement of the current tiles`() {
        assertEquals(
            CoverageGlowRenderer.FillMode.COMPLEMENT,
            CoverageGlowStyleMapping.fillModeFor(CoverageGlowStyle.WASH),
        )
        assertSame(current, CoverageGlowStyleMapping.regionsFor(CoverageGlowStyle.WASH, current, needing))
        // Nothing current yet: no holes, i.e. a full wash.
        assertEquals(
            emptyList<SphereCoverage.TileRegion>(),
            CoverageGlowStyleMapping.regionsFor(CoverageGlowStyle.WASH, emptyList(), emptyList()),
        )
    }

    @Test
    fun `missing tiles fills the regions needing update`() {
        assertEquals(
            CoverageGlowRenderer.FillMode.TRIANGLES,
            CoverageGlowStyleMapping.fillModeFor(CoverageGlowStyle.MISSING_TILES),
        )
        assertSame(needing, CoverageGlowStyleMapping.regionsFor(CoverageGlowStyle.MISSING_TILES, current, needing))
    }

    @Test
    fun `missing tiles glows over the whole view before the photosphere is anchored`() {
        assertNull(CoverageGlowStyleMapping.regionsFor(CoverageGlowStyle.MISSING_TILES, emptyList(), emptyList()))
    }

    @Test
    fun `missing tiles draws nothing once every tile is current`() {
        assertEquals(
            emptyList<SphereCoverage.TileRegion>(),
            CoverageGlowStyleMapping.regionsFor(CoverageGlowStyle.MISSING_TILES, current, emptyList()),
        )
    }

    @Test
    fun `edge glow outlines the current tiles`() {
        assertEquals(
            CoverageGlowRenderer.FillMode.OUTLINE,
            CoverageGlowStyleMapping.fillModeFor(CoverageGlowStyle.OUTLINE),
        )
        assertSame(current, CoverageGlowStyleMapping.regionsFor(CoverageGlowStyle.OUTLINE, current, needing))
        // Nothing captured yet: no edge to draw (never the full-view fallback, which would be a wash).
        assertEquals(
            emptyList<SphereCoverage.TileRegion>(),
            CoverageGlowStyleMapping.regionsFor(CoverageGlowStyle.OUTLINE, emptyList(), emptyList()),
        )
    }

    @Test
    fun `wash plus edge is the outlined complement of the current tiles`() {
        assertEquals(
            CoverageGlowRenderer.FillMode.COMPLEMENT_OUTLINED,
            CoverageGlowStyleMapping.fillModeFor(CoverageGlowStyle.WASH_OUTLINED),
        )
        assertSame(current, CoverageGlowStyleMapping.regionsFor(CoverageGlowStyle.WASH_OUTLINED, current, needing))
        assertEquals(
            emptyList<SphereCoverage.TileRegion>(),
            CoverageGlowStyleMapping.regionsFor(CoverageGlowStyle.WASH_OUTLINED, emptyList(), emptyList()),
        )
    }

    @Test
    fun `edge alpha follows the intensity setting and stays a valid renderer alpha`() {
        // The shipped 14% wash maps to about the library's own 0.5 default edge.
        assertEquals(0.5f, CoverageGlowStyleMapping.outlineAlphaFor(0.14f), 0.005f)
        assertEquals(0f, CoverageGlowStyleMapping.outlineAlphaFor(0f), 0f)
        assertEquals(1f, CoverageGlowStyleMapping.outlineAlphaFor(0.6f), 1e-6f)
        // Out-of-range / non-finite settings are sanitized like the wash intensity.
        assertEquals(1f, CoverageGlowStyleMapping.outlineAlphaFor(5f), 1e-6f)
        assertEquals(0.5f, CoverageGlowStyleMapping.outlineAlphaFor(Float.NaN), 0.005f)
        var previous = -1f
        for (step in 0..60) {
            val alpha = CoverageGlowStyleMapping.outlineAlphaFor(step / 100f)
            org.junit.Assert.assertTrue(alpha in 0f..1f && alpha >= previous)
            previous = alpha
        }
    }

    @Test
    fun `the whole slider range drives the edge alpha - no dead top half`() {
        // Strictly increasing over every whole-percent step of 0..60%: the old linear 3.5x gain
        // saturated at about 29%, so 29..60% all drew the same edge.
        var previous = CoverageGlowStyleMapping.outlineAlphaFor(0f)
        for (step in 1..60) {
            val alpha = CoverageGlowStyleMapping.outlineAlphaFor(step / 100f)
            org.junit.Assert.assertTrue("step=$step alpha=$alpha previous=$previous", alpha > previous)
            previous = alpha
        }
        org.junit.Assert.assertTrue(CoverageGlowStyleMapping.outlineAlphaFor(0.59f) < 0.995f)
        org.junit.Assert.assertTrue(CoverageGlowStyleMapping.outlineAlphaFor(0.30f) < 0.8f)
    }

    @Test
    fun `edge width is density scaled and inside the renderer range`() {
        assertEquals(30f, CoverageGlowStyleMapping.outlineWidthPxFor(3f), 1e-4f)
        assertEquals(10f, CoverageGlowStyleMapping.outlineWidthPxFor(1f), 1e-4f)
        assertEquals(10f, CoverageGlowStyleMapping.outlineWidthPxFor(Float.NaN), 1e-4f)
        assertEquals(10f, CoverageGlowStyleMapping.outlineWidthPxFor(0f), 1e-4f)
        assertEquals(
            CoverageGlowRenderer.MAX_OUTLINE_WIDTH_PX,
            CoverageGlowStyleMapping.outlineWidthPxFor(1000f),
            0f,
        )
        // The renderer accepts every value the mapping produces.
        CoverageGlowRenderer().outlineWidthPx = CoverageGlowStyleMapping.outlineWidthPxFor(4f)
        CoverageGlowRenderer().outlineAlpha = CoverageGlowStyleMapping.outlineAlphaFor(0.6f)
    }

    @Test
    fun `full view geometry is two whole triangles covering NDC`() {
        val tri = CoverageGlowStyleMapping.FULL_VIEW_TRIANGLES
        assertEquals(0, tri.size % (3 * com.hereliesaz.sphereslam.CoverageGlowProjection.FLOATS_PER_REGION_VERTEX))
        assertEquals(12, tri.size)
        tri.forEach { assertEquals(1f, kotlin.math.abs(it), 0f) }
    }
}
