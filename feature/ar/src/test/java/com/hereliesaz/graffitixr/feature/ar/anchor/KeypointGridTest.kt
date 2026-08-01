package com.hereliesaz.graffitixr.feature.ar.anchor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * `IMPLEMENTATION.md` **4.4** — the grid against a brute-force reference, on random points across a
 * range of radii.
 *
 * The property under test is one-directional and that is the whole design: the grid must return
 * **every** keypoint within the radius, and is allowed to return extras. So the assertion is
 * `bruteForce ⊆ grid`, not set equality — asserting equality would be asserting a contract the
 * implementation deliberately does not offer, and would fail on points that are inside the swept
 * cells but outside the circle.
 *
 * The seed is fixed. A randomized test that cannot be re-run identically reports a failure nobody
 * can reproduce, which in a geometry routine is barely better than no test.
 *
 * These fixtures are also what the native transliteration has to reproduce, so a radius that works
 * here is a claim about `MobileGS.cpp` too.
 */
class KeypointGridTest {

    private fun bruteForce(pts: FloatArray, x: Float, y: Float, r: Float): Set<Int> {
        val out = HashSet<Int>()
        val r2 = r * r
        for (i in 0 until pts.size / 2) {
            val dx = pts[i * 2] - x
            val dy = pts[i * 2 + 1] - y
            if (dx * dx + dy * dy <= r2) out.add(i)
        }
        return out
    }

    private fun randomPoints(n: Int, seed: Int, w: Float = 1920f, h: Float = 1080f): FloatArray {
        val rnd = Random(seed)
        return FloatArray(n * 2) { if (it % 2 == 0) rnd.nextFloat() * w else rnd.nextFloat() * h }
    }

    /**
     * The headline equivalence. Radii deliberately span **below and above** the cell size: the grid
     * sweeps a cell range rather than a fixed 3x3 precisely so a query wider than the cell still
     * works, and a hard-coded 3x3 — the likeliest transliteration error — passes at `radius <=
     * cellSize` and fails here.
     */
    @Test
    fun `the grid returns every point a brute-force search finds`() {
        val pts = randomPoints(1500, seed = 20260801)
        val cellSize = 16f
        val grid = KeypointGrid.build(pts, cellSize)
        val rnd = Random(7)
        val scratch = ArrayList<Int>()

        for (radius in floatArrayOf(1f, 4f, 15f, 16f, 17f, 40f, 120f, 400f)) {
            repeat(40) {
                val qx = rnd.nextFloat() * 1920f
                val qy = rnd.nextFloat() * 1080f
                grid.candidatesWithin(qx, qy, radius, scratch)
                val got = scratch.toSet()
                val expected = bruteForce(pts, qx, qy, radius)
                assertTrue(
                    "radius=$radius query=($qx,$qy) missed ${(expected - got).size} of " +
                        "${expected.size} true neighbours",
                    got.containsAll(expected),
                )
            }
        }
    }

    /**
     * The grid has to be *worth* having. If it returned nearly everything it would be correct and
     * useless — and the phase's entire argument is that the candidate set collapses, which is what
     * lets the Lowe ratio be loosened afterwards.
     */
    @Test
    fun `the candidate set is a small fraction of the frame`() {
        val n = 1500
        val pts = randomPoints(n, seed = 4242)
        val grid = KeypointGrid.build(pts, cellSize = 16f)
        val rnd = Random(99)
        var total = 0
        val scratch = ArrayList<Int>()
        repeat(200) {
            grid.candidatesWithin(rnd.nextFloat() * 1920f, rnd.nextFloat() * 1080f, 16f, scratch)
            total += scratch.size
        }
        val meanFraction = total / 200.0 / n
        assertTrue(
            "a 16 px search over a 1920x1080 frame returned $meanFraction of all keypoints on " +
                "average; the local search is not local",
            meanFraction < 0.02,
        )
    }

    @Test
    fun `an empty keypoint set yields no candidates`() {
        val grid = KeypointGrid.build(FloatArray(0), 16f)
        assertEquals(0, grid.candidatesWithin(100f, 100f, 50f).size)
    }

    @Test
    fun `a single keypoint is found from inside its radius and missed from outside`() {
        val grid = KeypointGrid.build(floatArrayOf(100f, 100f), 16f)
        assertTrue(grid.candidatesWithin(100f, 100f, 1f).contains(0))
        assertTrue(grid.candidatesWithin(110f, 100f, 20f).contains(0))
        // Far enough that no swept cell can reach it.
        assertTrue(grid.candidatesWithin(1000f, 1000f, 10f).isEmpty())
    }

    /**
     * All points on one spot is the degenerate clustering case — every keypoint in one bucket. It
     * must still answer correctly rather than divide by a zero extent.
     */
    @Test
    fun `coincident keypoints do not collapse the grid`() {
        val pts = FloatArray(200) { if (it % 2 == 0) 500f else 500f }
        val grid = KeypointGrid.build(pts, 16f)
        assertEquals(100, grid.candidatesWithin(500f, 500f, 1f).size)
        assertTrue(grid.candidatesWithin(900f, 900f, 10f).isEmpty())
    }

    /**
     * A non-finite keypoint must not poison the bounds. If it did, `minX`/`maxX` would go infinite,
     * the grid would collapse to a single cell, and every query would return every point — the local
     * search silently becoming a global one, which is a performance and precision regression that no
     * assertion elsewhere would catch.
     */
    @Test
    fun `a NaN keypoint does not collapse the grid to one cell`() {
        val pts = floatArrayOf(
            10f, 10f,
            Float.NaN, 50f,
            1000f, 800f,
            20f, 20f,
        )
        val grid = KeypointGrid.build(pts, 16f)
        val near = grid.candidatesWithin(15f, 15f, 20f)
        assertTrue("the two nearby points must be found", near.containsAll(listOf(0, 3)))
        assertTrue("the far point must not be", !near.contains(2))
    }

    @Test
    fun `a degenerate cell size does not produce an unbounded grid`() {
        val pts = randomPoints(100, seed = 5)
        for (cell in floatArrayOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            val grid = KeypointGrid.build(pts, cell)
            val got = grid.candidatesWithin(960f, 540f, 50f)
            assertTrue("cellSize=$cell produced $got", got.containsAll(bruteForce(pts, 960f, 540f, 50f)))
        }
    }

    /** The `out` overload must clear, or a per-feature loop accumulates every previous query. */
    @Test
    fun `the reusable buffer is cleared on each query`() {
        val grid = KeypointGrid.build(randomPoints(300, seed = 11), 16f)
        val scratch = ArrayList<Int>()
        grid.candidatesWithin(500f, 500f, 100f, scratch)
        val first = scratch.size
        grid.candidatesWithin(500f, 500f, 100f, scratch)
        assertEquals("a repeated query must not double the buffer", first, scratch.size)
    }
}
