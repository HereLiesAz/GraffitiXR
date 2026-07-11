package com.hereliesaz.graffitixr.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ScribbleGeneratorTest {

    // Cover a wide, fixed seed range so the placement invariants are checked deterministically
    // (no flakiness) across many random layouts.
    private val seeds = 0L until 2000L

    @Test
    fun `glyph count respects the complexity cap and floor`() {
        seeds.forEach { seed ->
            val s = ScribbleGenerator.generate(Random(seed))
            assertTrue(
                "seed=$seed count=${s.glyphs.size}",
                s.glyphs.size in ScribbleGenerator.MIN_GLYPHS..ScribbleGenerator.MAX_GLYPHS,
            )
        }
    }

    @Test
    fun `explicit glyphCount is coerced into the allowed band`() {
        assertEquals(ScribbleGenerator.MIN_GLYPHS, ScribbleGenerator.generate(Random(1), glyphCount = 0).glyphs.size)
        assertEquals(ScribbleGenerator.MAX_GLYPHS, ScribbleGenerator.generate(Random(1), glyphCount = 99).glyphs.size)
    }

    @Test
    fun `every glyph centre stays within the canvas`() {
        seeds.forEach { seed ->
            ScribbleGenerator.generate(Random(seed)).glyphs.forEach { g ->
                assertTrue("seed=$seed cx=${g.cx}", g.cx in 0f..1f)
                assertTrue("seed=$seed cy=${g.cy}", g.cy in 0f..1f)
            }
        }
    }

    @Test
    fun `every glyph overlaps at least one other glyph`() {
        seeds.forEach { seed ->
            val glyphs = ScribbleGenerator.generate(Random(seed)).glyphs
            glyphs.forEachIndexed { i, g ->
                val hasOverlap = glyphs.withIndex().any { (j, other) ->
                    j != i && ScribbleGenerator.overlaps(g, other)
                }
                assertTrue("seed=$seed glyph[$i]='${g.char}' has no overlapping neighbour", hasOverlap)
            }
        }
    }

    @Test
    fun `same seed yields identical scribble`() {
        val a = ScribbleGenerator.generate(Random(42))
        val b = ScribbleGenerator.generate(Random(42))
        assertEquals(a, b)
    }

    @Test
    fun `rotation is within a full turn`() {
        seeds.forEach { seed ->
            ScribbleGenerator.generate(Random(seed)).glyphs.forEach { g ->
                assertTrue("seed=$seed rot=${g.rotationDeg}", g.rotationDeg in 0f..360f)
            }
        }
    }
}
