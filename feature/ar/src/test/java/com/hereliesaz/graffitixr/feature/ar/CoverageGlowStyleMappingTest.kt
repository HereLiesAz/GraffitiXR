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
    fun `full view geometry is two whole triangles covering NDC`() {
        val tri = CoverageGlowStyleMapping.FULL_VIEW_TRIANGLES
        assertEquals(0, tri.size % (3 * com.hereliesaz.sphereslam.CoverageGlowProjection.FLOATS_PER_REGION_VERTEX))
        assertEquals(12, tri.size)
        tri.forEach { assertEquals(1f, kotlin.math.abs(it), 0f) }
    }
}
