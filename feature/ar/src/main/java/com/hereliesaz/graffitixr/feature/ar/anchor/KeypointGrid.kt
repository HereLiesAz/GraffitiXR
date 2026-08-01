package com.hereliesaz.graffitixr.feature.ar.anchor

/**
 * `IMPLEMENTATION.md` **Phase 4** — a uniform grid over a frame's keypoints, so the corroboration
 * match can gather candidates near a predicted pixel instead of comparing against every descriptor.
 *
 * This is the pure-Kotlin **reference implementation**. `MobileGS.cpp` transliterates it, and
 * `KeypointGridTest` holds it to a brute-force equivalence check on random points across a range of
 * radii — the same fixture the native smoke assertion uses. Writing it here first is deliberate: the
 * matching change is the part of Phase 4 that lives in C++, where a subtle off-by-one in the cell
 * sweep would show up as slightly fewer corroborated features and nothing else, which is exactly the
 * kind of defect that survives a code review and a device test alike.
 *
 * ## Why a grid and not a k-d tree or FLANN
 *
 * The frame has on the order of 1500 keypoints and the query is a fixed-radius neighbourhood, not a
 * k-nearest search. A uniform grid sized to the radius is `O(n)` to build, allocation-free to query,
 * and touches nine cells regardless of `n`. `cv::flann` would add a dependency, a build cost per
 * frame, and an approximate answer to a question that has an exact one.
 *
 * ## The contract that matters
 *
 * [candidatesWithin] returns a superset guarantee only in one direction: **every** keypoint within
 * `radius` of the query is returned. It may also return keypoints slightly outside it — the nine
 * cells cover a square of side `3 * cellSize`, not a circle — and callers must apply the exact
 * distance test themselves if they need it. That is the correct division of labour: the grid exists
 * to make the candidate set small, and the ratio test that follows is what decides matches. A grid
 * that pre-filtered exactly would do the same arithmetic twice.
 */
class KeypointGrid private constructor(
    private val cellSize: Float,
    private val minX: Float,
    private val minY: Float,
    private val cols: Int,
    private val rows: Int,
    /** Flat `cols * rows` bucket array; each entry holds indices into the original keypoint list. */
    private val cells: Array<IntArray>,
) {

    /**
     * Indices of every keypoint whose cell could contain a point within [radius] of ([x], [y]).
     *
     * Sweeps the cell range covering `[x - radius, x + radius] x [y - radius, y + radius]` rather
     * than a fixed 3x3. Those are the same thing only when `cellSize >= radius`, which is true when
     * the grid was built with [build]'s default — but a caller may build one grid and query it at
     * several radii (the drift term makes the radius vary frame to frame), and a hard-coded 3x3
     * would then silently miss candidates. This is the off-by-one the native transliteration is most
     * likely to reintroduce, which is why the test sweeps radii both below and above the cell size.
     */
    fun candidatesWithin(x: Float, y: Float, radius: Float, out: MutableList<Int>) {
        out.clear()
        if (cols == 0 || rows == 0 || radius < 0f || !x.isFinite() || !y.isFinite()) return
        val r = if (radius.isFinite()) radius else return

        // Computed UNCLAMPED first, so a query whose range lies entirely outside the grid returns
        // nothing. Clamping straight into `[0, count)` — the obvious way to write this — silently
        // maps a distant query onto the edge cells and hands back their contents, which on a grid
        // that is one cell wide (few or tightly clustered keypoints) means every keypoint in the
        // frame for a query nowhere near any of them. Caught by the single-keypoint and coincident
        // fixtures in KeypointGridTest, which is why they are there.
        val c0raw = cellFloor(x - r, minX)
        val c1raw = cellFloor(x + r, minX)
        val r0raw = cellFloor(y - r, minY)
        val r1raw = cellFloor(y + r, minY)
        if (c1raw < 0 || c0raw > cols - 1 || r1raw < 0 || r0raw > rows - 1) return

        val c0 = c0raw.coerceIn(0, cols - 1)
        val c1 = c1raw.coerceIn(0, cols - 1)
        val r0 = r0raw.coerceIn(0, rows - 1)
        val r1 = r1raw.coerceIn(0, rows - 1)
        for (row in r0..r1) {
            val base = row * cols
            for (col in c0..c1) {
                val bucket = cells[base + col]
                for (i in bucket) out.add(i)
            }
        }
    }

    /** Convenience overload; allocates. Use the [out] form in a per-feature loop. */
    fun candidatesWithin(x: Float, y: Float, radius: Float): List<Int> =
        ArrayList<Int>().also { candidatesWithin(x, y, radius, it) }

    /**
     * Unclamped cell index, which may be negative or past the last cell. `floor`, not `toInt`:
     * `toInt` truncates toward zero, so a query 0.5 cells to the LEFT of the origin lands in cell 0
     * instead of -1 and the out-of-range test above would never fire on that side.
     */
    private fun cellFloor(v: Float, min: Float): Int {
        if (!v.isFinite()) return if (v > 0f) Int.MAX_VALUE else Int.MIN_VALUE
        return kotlin.math.floor((v - min) / cellSize).toInt()
    }

    companion object {
        /**
         * Bucket `n` keypoints given as a flat `[x0, y0, x1, y1, ...]` array.
         *
         * @param cellSize edge length of a bucket in pixels. Pass the search radius: cells much
         *   smaller than the radius mean sweeping many of them, and cells much larger mean each one
         *   holds candidates that are nowhere near. Floored at 1 px so a degenerate radius cannot
         *   produce an unbounded grid.
         */
        fun build(pointsXY: FloatArray, cellSize: Float): KeypointGrid {
            val n = pointsXY.size / 2
            val cell = if (cellSize.isFinite() && cellSize >= 1f) cellSize else 1f
            if (n == 0) return KeypointGrid(cell, 0f, 0f, 0, 0, emptyArray())

            var minX = Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE
            var maxY = -Float.MAX_VALUE
            for (i in 0 until n) {
                val x = pointsXY[i * 2]
                val y = pointsXY[i * 2 + 1]
                // A non-finite keypoint would poison the bounds and collapse the whole grid to one
                // cell, quietly turning the local search back into a global one.
                if (!x.isFinite() || !y.isFinite()) continue
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
            if (minX > maxX) return KeypointGrid(cell, 0f, 0f, 0, 0, emptyArray())

            val cols = (((maxX - minX) / cell).toInt() + 1).coerceAtLeast(1)
            val rows = (((maxY - minY) / cell).toInt() + 1).coerceAtLeast(1)

            // Two passes: count, then fill. One pass into growable lists would allocate an
            // ArrayList per cell for a grid that is mostly empty at 1500 points.
            val counts = IntArray(cols * rows)
            for (i in 0 until n) {
                val b = bucketOf(pointsXY, i, minX, minY, cell, cols, rows) ?: continue
                counts[b]++
            }
            val cells = Array(cols * rows) { IntArray(counts[it]) }
            val fill = IntArray(cols * rows)
            for (i in 0 until n) {
                val b = bucketOf(pointsXY, i, minX, minY, cell, cols, rows) ?: continue
                cells[b][fill[b]++] = i
            }
            return KeypointGrid(cell, minX, minY, cols, rows, cells)
        }

        private fun bucketOf(
            pointsXY: FloatArray, i: Int,
            minX: Float, minY: Float, cell: Float, cols: Int, rows: Int,
        ): Int? {
            val x = pointsXY[i * 2]
            val y = pointsXY[i * 2 + 1]
            if (!x.isFinite() || !y.isFinite()) return null
            val c = (((x - minX) / cell).toInt()).coerceIn(0, cols - 1)
            val r = (((y - minY) / cell).toInt()).coerceIn(0, rows - 1)
            return r * cols + c
        }
    }
}
