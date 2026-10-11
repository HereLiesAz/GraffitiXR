package com.hereliesaz.graffitixr.common.model

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CoverageGlowSettingsTest {

    @Test
    fun `defaults are a hot pink 14 percent wash`() {
        val d = CoverageGlowAppearance.DEFAULT
        assertEquals(0xFFFF69B4.toInt(), d.colorArgb)
        assertEquals(0.14f, d.intensity, 0f)
        assertEquals(CoverageGlowStyle.WASH, d.style)
        assertEquals("COMPLEMENT", d.style.rendererFillMode)
        assertEquals(CoverageGlowGeometry.CURRENT_TILES, d.style.geometry)
    }

    @Test
    fun `missing-tiles style fills the regions needing update`() {
        assertEquals("TRIANGLES", CoverageGlowStyle.MISSING_TILES.rendererFillMode)
        assertEquals(CoverageGlowGeometry.TILES_NEEDING_UPDATE, CoverageGlowStyle.MISSING_TILES.geometry)
    }

    @Test
    fun `outline styles cut from the current tiles`() {
        assertEquals("OUTLINE", CoverageGlowStyle.OUTLINE.rendererFillMode)
        assertEquals(CoverageGlowGeometry.CURRENT_TILES, CoverageGlowStyle.OUTLINE.geometry)
        assertEquals("outline", CoverageGlowStyle.OUTLINE.storageKey)
        assertEquals("COMPLEMENT_OUTLINED", CoverageGlowStyle.WASH_OUTLINED.rendererFillMode)
        assertEquals(CoverageGlowGeometry.CURRENT_TILES, CoverageGlowStyle.WASH_OUTLINED.geometry)
        assertEquals("wash_outlined", CoverageGlowStyle.WASH_OUTLINED.storageKey)
    }

    @Test
    fun `persisted keys of the original styles never change`() {
        assertEquals("wash", CoverageGlowStyle.WASH.storageKey)
        assertEquals("missing_tiles", CoverageGlowStyle.MISSING_TILES.storageKey)
    }

    @Test
    fun `style storage keys round-trip and unknown keys fall back to the default`() {
        CoverageGlowStyle.entries.forEach {
            assertEquals(it, CoverageGlowStyle.fromStorageKey(it.storageKey))
        }
        assertEquals(CoverageGlowStyle.WASH, CoverageGlowStyle.fromStorageKey(null))
        assertEquals(CoverageGlowStyle.WASH, CoverageGlowStyle.fromStorageKey("hatching_from_the_future"))
    }

    @Test
    fun `style storage keys are unique`() {
        val keys = CoverageGlowStyle.entries.map { it.storageKey }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `next cycles through every style and wraps`() {
        var s = CoverageGlowStyle.DEFAULT
        val seen = mutableSetOf<CoverageGlowStyle>()
        repeat(CoverageGlowStyle.entries.size) {
            seen += s
            s = CoverageGlowStyle.next(s)
        }
        assertEquals(CoverageGlowStyle.entries.toSet(), seen)
        assertEquals(CoverageGlowStyle.DEFAULT, s)
    }

    @Test
    fun `palette starts with white, defaults to hot pink, is opaque and unique`() {
        assertEquals(CoverageGlowColor.WHITE, CoverageGlowColor.entries.first())
        assertEquals(CoverageGlowColor.HOT_PINK, CoverageGlowColor.DEFAULT)
        CoverageGlowColor.entries.forEach { assertEquals(0xFF, (it.argb ushr 24) and 0xFF) }
        assertEquals(CoverageGlowColor.entries.size, CoverageGlowColor.entries.map { it.argb }.toSet().size)
    }

    @Test
    fun `fromArgb ignores alpha and returns null off-palette`() {
        assertEquals(CoverageGlowColor.MID_GREY, CoverageGlowColor.fromArgb(0x00808080))
        assertEquals(CoverageGlowColor.BLACK, CoverageGlowColor.fromArgb(0xFF000000.toInt()))
        assertNull(CoverageGlowColor.fromArgb(0xFF123456.toInt()))
    }

    @Test
    fun `intensity is clamped to the slider range and non-finite falls back to default`() {
        assertEquals(0f, CoverageGlowIntensity.sanitize(-0.5f), 0f)
        assertEquals(0.6f, CoverageGlowIntensity.sanitize(0.9f), 0f)
        assertEquals(0.3f, CoverageGlowIntensity.sanitize(0.3f), 0f)
        assertEquals(0.14f, CoverageGlowIntensity.sanitize(Float.NaN), 0f)
        assertEquals(0.14f, CoverageGlowIntensity.sanitize(Float.POSITIVE_INFINITY), 0f)
        assertEquals(0.6f, CoverageGlowAppearance(intensity = 2f).safeIntensity, 0f)
    }

    @Test
    fun `rgb converts ARGB to unit floats`() {
        assertArrayEquals(
            floatArrayOf(1f, 1f, 1f),
            CoverageGlowAppearance(colorArgb = CoverageGlowColor.WHITE.argb).rgb(),
            0f,
        )
        assertArrayEquals(
            floatArrayOf(1f, 105f / 255f, 180f / 255f),
            CoverageGlowAppearance().rgb(),
            1e-6f,
        )
        assertArrayEquals(
            floatArrayOf(0f, 0f, 0f),
            CoverageGlowAppearance(colorArgb = CoverageGlowColor.BLACK.argb).rgb(),
            0f,
        )
    }
}
