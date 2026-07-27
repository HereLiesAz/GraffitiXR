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

    // Bounds below are derived from the generator's grid, not guessed. For n in 4..6 the layout is
    // 2x2 (n=4) or 2x3 (n=5,6); with at most one cell dropped every row and column stays occupied,
    // so worst case (all jitter pulling inward) the bounding box spans cellDim*(1 - JITTER) = 0.209
    // on the tighter axis, adjacent centres sit at least 0.139 apart, and sizeFrac is at least 0.20.
    // The assertions sit safely under those figures.

    @Test
    fun `glyphs are spread across the canvas, not clustered in the middle`() {
        // The scribble gets copied onto a wall and then detected from the drawn marks, so the marks
        // must cover area: a tight central knot is both awkward to draw big and a badly-conditioned
        // pose solve.
        seeds.forEach { seed ->
            val glyphs = ScribbleGenerator.generate(Random(seed)).glyphs
            val spanX = glyphs.maxOf { it.cx } - glyphs.minOf { it.cx }
            val spanY = glyphs.maxOf { it.cy } - glyphs.minOf { it.cy }
            assertTrue("seed=$seed spanX=$spanX", spanX >= 0.15f)
            assertTrue("seed=$seed spanY=$spanY", spanY >= 0.15f)
        }
    }

    @Test
    fun `no two glyphs sit on top of each other`() {
        // Marks touching is fine and still reads as one scribble; near-concentric is not, because
        // then there is nothing legible to copy.
        seeds.forEach { seed ->
            val glyphs = ScribbleGenerator.generate(Random(seed)).glyphs
            for (i in glyphs.indices) for (j in i + 1 until glyphs.size) {
                val d = ScribbleGenerator.centreDistance(glyphs[i], glyphs[j])
                assertTrue("seed=$seed glyphs[$i]/[$j] centres only $d apart", d > 0.10f)
            }
        }
    }

    @Test
    fun `glyphs are large enough to be worth drawing`() {
        seeds.forEach { seed ->
            ScribbleGenerator.generate(Random(seed)).glyphs.forEach { g ->
                assertTrue("seed=$seed size=${g.sizeFrac}", g.sizeFrac >= 0.15f)
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
