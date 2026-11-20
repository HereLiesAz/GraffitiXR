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

    // --- the curated pool ---

    @Test
    fun `the pool has no repeats and enough characters for any scribble`() {
        val pool = ScribbleGenerator.GLYPH_POOL
        assertEquals(pool.size, pool.toSet().size)
        assertTrue(pool.size >= ScribbleGenerator.MAX_GLYPHS)
    }

    @Test
    fun `the pool holds at most one character of each look-alike group`() {
        // The glyphs are drawn at random angles, so an M turned over is a W: one slot per shape.
        for (group in ScribbleGenerator.LOOKALIKES) {
            val inPool = ScribbleGenerator.GLYPH_POOL.filter { it in group }
            assertTrue("group $group has $inPool", inPool.size <= 1)
        }
        // A character is in one group only, or the rule above would not mean one thing.
        val all = ScribbleGenerator.LOOKALIKES.joinToString("")
        assertEquals(all.length, all.toSet().size)
    }

    @Test
    fun `the asterisk is left out because the font centres it badly`() {
        assertTrue('*' !in ScribbleGenerator.GLYPH_POOL)
    }

    @Test
    fun `a scribble never repeats a character and draws only from the pool`() {
        seeds.forEach { seed ->
            val chars = ScribbleGenerator.generate(Random(seed)).glyphs.map { it.char }
            assertEquals("seed=$seed $chars", chars.size, chars.toSet().size)
            assertTrue("seed=$seed $chars", chars.all { it in ScribbleGenerator.GLYPH_POOL })
        }
    }

    // --- fitting the artwork's shape ---

    private val aspects = listOf(0.4f, 0.6f, 1f, 1.6f, 2.5f)

    @Test
    fun `the scribble records the aspect it was laid out for, clamped`() {
        assertEquals(1f, ScribbleGenerator.generate(Random(1)).aspect, 0f)
        assertEquals(1.6f, ScribbleGenerator.generate(Random(1), aspect = 1.6f).aspect, 0f)
        assertEquals(ScribbleGenerator.MAX_ASPECT, ScribbleGenerator.generate(Random(1), aspect = 99f).aspect, 0f)
        assertEquals(ScribbleGenerator.MIN_ASPECT, ScribbleGenerator.generate(Random(1), aspect = 0.01f).aspect, 0f)
        // Nonsense falls back to the square.
        for (bad in listOf(0f, -2f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertEquals("aspect=$bad", 1f, ScribbleGenerator.generate(Random(1), aspect = bad).aspect, 0f)
        }
    }

    @Test
    fun `at any aspect the glyphs stay inside the canvas, apart, and big enough`() {
        for (a in aspects) seeds.forEach { seed ->
            val s = ScribbleGenerator.generate(Random(seed), aspect = a)
            s.glyphs.forEach { g ->
                assertTrue("a=$a seed=$seed cx=${g.cx}", g.cx in 0f..1f)
                assertTrue("a=$a seed=$seed cy=${g.cy}", g.cy in 0f..1f)
                assertTrue("a=$a seed=$seed size=${g.sizeFrac}", g.sizeFrac >= 0.15f)
            }
            for (i in s.glyphs.indices) for (j in i + 1 until s.glyphs.size) {
                val d = ScribbleGenerator.centreDistance(s.glyphs[i], s.glyphs[j], s.aspect)
                assertTrue("a=$a seed=$seed [$i]/[$j] only $d apart", d > 0.10f)
            }
        }
    }

    @Test
    fun `a wide canvas gets a wide scribble and a tall one a tall scribble`() {
        // Spans in units of the canvas' shorter edge, so they compare across the two axes.
        fun spans(a: Float, seed: Long): Pair<Float, Float> {
            val w = if (a >= 1f) a else 1f
            val h = if (a >= 1f) 1f else 1f / a
            val g = ScribbleGenerator.generate(Random(seed), aspect = a).glyphs
            return (g.maxOf { it.cx } - g.minOf { it.cx }) * w to (g.maxOf { it.cy } - g.minOf { it.cy }) * h
        }
        seeds.forEach { seed ->
            val (wx, wy) = spans(2.5f, seed)
            assertTrue("wide seed=$seed x=$wx y=$wy", wx > 1.5f * wy)
            val (tx, ty) = spans(0.4f, seed)
            assertTrue("tall seed=$seed x=$tx y=$ty", ty > 1.5f * tx)
        }
    }

    @Test
    fun `the square layout is unchanged in shape and still spreads over the canvas`() {
        seeds.forEach { seed ->
            val g = ScribbleGenerator.generate(Random(seed), aspect = 1f).glyphs
            assertTrue((g.maxOf { it.cx } - g.minOf { it.cx }) >= 0.15f)
            assertTrue((g.maxOf { it.cy } - g.minOf { it.cy }) >= 0.15f)
        }
    }

    @Test
    fun `the grid gives the biggest cells, a row for a wide box and a column for a tall one`() {
        assertEquals(2 to 2, ScribbleGenerator.bestGrid(4, 0.76f, 0.76f, wide = true))
        assertEquals(3 to 2, ScribbleGenerator.bestGrid(5, 0.76f, 0.76f, wide = true))
        assertEquals(3 to 2, ScribbleGenerator.bestGrid(6, 0.76f, 0.76f, wide = true))
        assertEquals(4 to 1, ScribbleGenerator.bestGrid(4, 2.26f, 0.76f, wide = true))
        assertEquals(1 to 4, ScribbleGenerator.bestGrid(4, 0.76f, 2.26f, wide = false))
        // A tall box with a tie puts the longer count down the long side.
        assertEquals(2 to 3, ScribbleGenerator.bestGrid(6, 0.76f, 0.76f, wide = false))
    }

    // --- laid out inside the artwork's silhouette ---

    /** A silhouette over a canvas of aspect [a]: [inside] says, for a point in units of the shorter edge, whether it is the subject. */
    private fun silhouette(a: Float, inside: (Float, Float) -> Boolean): ScribbleFit.SubjectMask {
        val w = if (a >= 1f) a else 1f
        val h = if (a >= 1f) 1f else 1f / a
        val mw = Math.round(ScribbleFit.CELLS_PER_EDGE * w)
        val mh = Math.round(ScribbleFit.CELLS_PER_EDGE * h)
        val bits = BooleanArray(mw * mh) { i -> inside(((i % mw) + 0.5f) / mw * w, ((i / mw) + 0.5f) / mh * h) }
        return ScribbleFit.SubjectMask(mw, mh, bits)
    }

    private class Shape(val name: String, val aspect: Float, val mask: ScribbleFit.SubjectMask)

    private fun disc(r: Float, a: Float = 1f, cx: Float = 0.5f * (if (a >= 1f) a else 1f), cy: Float = 0.5f * (if (a >= 1f) 1f else 1f / a)): (Float, Float) -> Boolean =
        { x, y -> (x - cx) * (x - cx) + (y - cy) * (y - cy) <= r * r }

    private val shapes: List<Shape> by lazy {
        listOf(
            Shape("disc", 1f, silhouette(1f, disc(0.45f))),
            Shape("wide ellipse", 1.6f, silhouette(1.6f) { x, y -> ((x - 0.8f) / 0.75f).let { u -> u * u } + ((y - 0.5f) / 0.4f).let { v -> v * v } <= 1f }),
            Shape("tall ellipse", 0.5f, silhouette(0.5f) { x, y -> ((x - 0.5f) / 0.45f).let { u -> u * u } + ((y - 1f) / 0.9f).let { v -> v * v } <= 1f }),
            Shape("L", 1f, silhouette(1f) { x, y -> (x in 0.12f..0.42f && y in 0.1f..0.9f) || (x in 0.12f..0.9f && y in 0.62f..0.9f) }),
            Shape("ring", 1f, silhouette(1f) { x, y -> val d = Math.hypot((x - 0.5f).toDouble(), (y - 0.5f).toDouble()); d in 0.2..0.47 }),
            Shape("two blobs", 1.5f, silhouette(1.5f) { x, y -> disc(0.25f, 1.5f, 0.35f, 0.5f)(x, y) || disc(0.22f, 1.5f, 1.15f, 0.5f)(x, y) }),
            Shape("thin bar", 2.5f, silhouette(2.5f) { x, y -> x in 0.2f..2.3f && y in 0.44f..0.56f }),
            Shape("thin column", 0.4f, silhouette(0.4f) { x, y -> x in 0.15f..0.25f && y in 0.2f..2.3f }),
            Shape("speck", 1f, silhouette(1f, disc(0.05f))),
            Shape("diagonal line", 1f, silhouette(1f) { x, y -> Math.abs(x - y) < 0.03f }),
        )
    }

    private fun insideMask(mask: ScribbleFit.SubjectMask, a: Float, g: ScribbleGlyph, radiusScale: Float): Boolean {
        val w = if (a >= 1f) a else 1f
        val h = if (a >= 1f) 1f else 1f / a
        val x0 = g.cx * w; val y0 = g.cy * h
        val r = g.sizeFrac / 2f * radiusScale
        if (!mask[(x0 / w * mask.width).toInt(), (y0 / h * mask.height).toInt()]) return false
        for (k in 0 until 16) {
            val t = k * 2.0 * Math.PI / 16
            val x = (x0 + r * Math.cos(t)).toFloat(); val y = (y0 + r * Math.sin(t)).toFloat()
            if (x < 0f || y < 0f || x >= w || y >= h) return false
            if (!mask[(x / w * mask.width).toInt(), (y / h * mask.height).toInt()]) return false
        }
        return true
    }

    @Test
    fun `every glyph lies wholly inside the subject, whatever its shape`() {
        for (shape in shapes) {
            if (shape.name == "diagonal line") continue // one cell wide: only the centres can be held to it, below
            for (seed in 0L until 150L) {
                val s = ScribbleGenerator.generate(Random(seed), aspect = shape.aspect, subject = shape.mask)
                s.glyphs.forEach { g ->
                    assertTrue("${shape.name} seed=$seed ${g.char} at (${g.cx}, ${g.cy}) size ${g.sizeFrac}", insideMask(shape.mask, shape.aspect, g, 1f))
                }
            }
        }
    }

    @Test
    fun `even a one cell line holds the glyph centres`() {
        val shape = shapes.first { it.name == "diagonal line" }
        for (seed in 0L until 150L) {
            val s = ScribbleGenerator.generate(Random(seed), aspect = shape.aspect, subject = shape.mask)
            s.glyphs.forEach { g ->
                assertTrue("seed=$seed (${g.cx}, ${g.cy})", shape.mask[(g.cx * shape.mask.width).toInt(), (g.cy * shape.mask.height).toInt()])
            }
        }
    }

    @Test
    fun `there is always a scribble of four to six distinct glyphs, sized and spun`() {
        for (shape in shapes) for (seed in 0L until 100L) {
            val s = ScribbleGenerator.generate(Random(seed), aspect = shape.aspect, subject = shape.mask)
            assertTrue("${shape.name} count ${s.glyphs.size}", s.glyphs.size in ScribbleGenerator.MIN_GLYPHS..ScribbleGenerator.MAX_GLYPHS)
            val chars = s.glyphs.map { it.char }
            assertEquals(chars.size, chars.toSet().size)
            assertTrue(chars.all { it in ScribbleGenerator.GLYPH_POOL })
            s.glyphs.forEach { g ->
                assertTrue("${shape.name} size ${g.sizeFrac}", g.sizeFrac > 0f && g.sizeFrac <= ScribbleGenerator.MAX_SIZE_FRAC + 1e-4f)
                assertTrue(g.cx in 0f..1f && g.cy in 0f..1f && g.rotationDeg in 0f..360f)
            }
        }
    }

    @Test
    fun `a roomy subject keeps full size glyphs and the bold weight`() {
        val shape = shapes.first { it.name == "disc" }
        val runs = (0L until 100L).map { ScribbleGenerator.generate(Random(it), aspect = 1f, subject = shape.mask) }
        // Six glyphs of full size do not quite fit a disc this size, so the odd one shrinks; most do not.
        assertTrue("only ${runs.count { !it.thin }} of 100 are bold", runs.count { !it.thin } >= 80)
        runs.forEach { s -> assertEquals(s.glyphs.any { it.sizeFrac < ScribbleGenerator.MIN_SIZE_FRAC }, s.thin) }
    }

    @Test
    fun `a sliver or a speck gets small thin glyphs, piled up inside it`() {
        for (name in listOf("thin bar", "thin column", "speck")) {
            val shape = shapes.first { it.name == name }
            for (seed in 0L until 100L) {
                val s = ScribbleGenerator.generate(Random(seed), aspect = shape.aspect, subject = shape.mask)
                assertTrue("$name seed=$seed should be thin", s.thin)
                assertTrue("$name seed=$seed glyph size", s.glyphs.all { it.sizeFrac < ScribbleGenerator.MIN_SIZE_FRAC })
            }
        }
    }

    @Test
    fun `the silhouette layout is deterministic for a seed`() {
        val shape = shapes.first { it.name == "L" }
        assertEquals(
            ScribbleGenerator.generate(Random(5), aspect = shape.aspect, subject = shape.mask),
            ScribbleGenerator.generate(Random(5), aspect = shape.aspect, subject = shape.mask),
        )
    }

    @Test
    fun `glyphs spread over a roomy subject instead of piling up`() {
        val shape = shapes.first { it.name == "ring" }
        seeds.take(100).forEach { seed ->
            val g = ScribbleGenerator.generate(Random(seed), aspect = 1f, subject = shape.mask).glyphs
            val span = maxOf(g.maxOf { it.cx } - g.minOf { it.cx }, g.maxOf { it.cy } - g.minOf { it.cy })
            assertTrue("seed=$seed span $span", span >= 0.4f)
        }
    }

    @Test
    fun `an empty silhouette still gives a scribble, in the middle`() {
        val empty = ScribbleFit.SubjectMask(24, 24, BooleanArray(24 * 24))
        val s = ScribbleGenerator.generate(Random(1), aspect = 1f, subject = empty)
        assertEquals(ScribbleGenerator.MIN_GLYPHS, s.glyphs.size.coerceAtMost(ScribbleGenerator.MIN_GLYPHS))
        assertTrue(s.glyphs.all { Math.abs(it.cx - 0.5f) < 0.05f && Math.abs(it.cy - 0.5f) < 0.05f })
    }
}
