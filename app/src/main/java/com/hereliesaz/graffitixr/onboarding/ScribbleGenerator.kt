package com.hereliesaz.graffitixr.onboarding

import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.random.Random

/**
 * A single character placed in the onboarding scribble, expressed in a normalized [0,1] x [0,1]
 * canvas so the renderer can scale it to any size. [sizeFrac] is the glyph box edge as a fraction
 * of the canvas' smaller dimension; the glyph's circular footprint (used for the spacing rule) has
 * radius `sizeFrac / 2` around ([cx], [cy]).
 */
data class ScribbleGlyph(
    val char: Char,
    val cx: Float,
    val cy: Float,
    val rotationDeg: Float,
    val sizeFrac: Float,
)

/**
 * A set of glyphs forming one scribble for the user to copy. [aspect] is the width / height of the
 * canvas the glyphs' normalized coordinates are for: 1 is the square the scribble always used to be,
 * and a different value lays the glyphs out to fit a wider or taller box (the shape of the artwork).
 * [thin] says the glyphs were made too small for the usual bold weight: they are drawn in a thin one.
 */
data class Scribble(val glyphs: List<ScribbleGlyph>, val aspect: Float = 1f, val thin: Boolean = false)

/**
 * Generates the random "draw this" scribble shown at first run: a handful of large characters
 * (letters, digits, symbols) spread across the canvas at random rotations.
 *
 * Glyphs are placed on a **jittered grid** rather than chained onto each other, so the marks cover
 * the whole area instead of clustering in the middle. That serves both halves of the job: the
 * scribble is easier to copy onto a wall at a useful size, and — because detection solves a pose
 * from the drawn marks — features spread across a wide baseline condition that solve far better
 * than a tight knot of overlapping glyphs in one spot.
 *
 * Pure and deterministic given a seeded [Random], so the placement logic is unit-testable without
 * Android. Rendering (rotation, stroke) is the caller's job.
 */
object ScribbleGenerator {

    /** Complexity cap — the marks want to be big, so fewer of them fit before it turns to mush. */
    const val MAX_GLYPHS = 6
    const val MIN_GLYPHS = 4

    /**
     * The characters worth drawing, best first. Not every character gives the tracker the same amount
     * to hold on to: the detectors find corners and crossings, so `#`, `&`, `m` and `4` give it several
     * times what the smooth `O`, `o`, `l`, `I`, `C` and `D` do. Measured with the app's own detectors
     * (SuperPoint at its threshold, ORB after CLAHE) on thick, blurred, noisy black marks on a pale wall,
     * at two glyph sizes and three rotations: a random scribble from the whole alphabet had a median of
     * about 50 SuperPoint keypoints, one from this pool about 74, and the worst draw went from about 33
     * (5th percentile) to 46. ORB agrees, and it also keeps far more of them when the view tilts.
     *
     * Chosen by that score, then kept to one per [LOOKALIKES] group, and `*` is left out: in the app's
     * font it is a small raised mark, which the glyph is not centred for.
     */
    val GLYPH_POOL: List<Char> = "#&m%t4f8@RaHX?".toList()

    /**
     * Characters that are one shape to a tracker, or to someone copying by hand, because the glyphs are
     * drawn at random angles: an `M` turned over is a `W`, a `+` turned a little is an `x`, and the
     * round `O`/`o`, the bars `I`/`l`/`!` and the two cases of a letter are near enough alike. The
     * pool holds at most one of each group, so a scribble never asks for two of one shape.
     */
    val LOOKALIKES: List<String> = listOf(
        "MWmwE3", "69", "nuU", "NZz2", "Ss5", "OoQ", "Il!", "Ppqdb", "Xx+", "Cc", "Vv", "Kk", "8B",
    )

    /**
     * Glyph footprint size range, as a fraction of the canvas' smaller edge. Deliberately large —
     * these get copied onto a wall by hand, and a small mark is both fiddly to draw and poor to
     * detect. Capped per-layout against the grid cell so a dense layout doesn't collapse into a blob.
     */
    private const val MIN_SIZE = 0.20f
    private const val MAX_SIZE = 0.34f

    /** The usual range of a glyph's size, as a fraction of the canvas' shorter edge (smaller ones are drawn thin). */
    const val MIN_SIZE_FRAC = MIN_SIZE
    const val MAX_SIZE_FRAC = MAX_SIZE

    /** Keep centres inside a margin so rotated glyphs don't clip the canvas edge. */
    private const val MARGIN = 0.12f

    /**
     * How far a glyph may wander from its grid cell centre, as a fraction of the cell. Enough to
     * break up the grid so it doesn't read as a table, not enough to re-cluster.
     */
    private const val JITTER = 0.45f

    /** Smallest and largest canvas aspect (width / height) the layout is asked to fit; outside it is clamped. */
    const val MIN_ASPECT = 0.25f
    const val MAX_ASPECT = 4f

    /**
     * @param random seeded for determinism/testability.
     * @param glyphCount desired glyph count; coerced into [[MIN_GLYPHS], [MAX_GLYPHS]].
     * @param aspect the canvas' width / height, so the glyphs fit the shape they will be drawn in (the
     *   artwork's) instead of always filling a square. 1 is the square layout; not finite or not
     *   positive is treated as 1, and the rest is clamped to [[MIN_ASPECT], [MAX_ASPECT]].
     * @param subject the artwork's silhouette over the canvas, when it does not fill it: every glyph then
     *   lies wholly inside it (see [generateInSubject]); null lays the glyphs over the whole canvas.
     */
    fun generate(
        random: Random = Random.Default,
        glyphCount: Int = random.nextInt(MIN_GLYPHS, MAX_GLYPHS + 1),
        aspect: Float = 1f,
        subject: ScribbleFit.SubjectMask? = null,
    ): Scribble {
        val n = glyphCount.coerceIn(MIN_GLYPHS, MAX_GLYPHS)
        val a = if (aspect.isFinite() && aspect > 0f) aspect.coerceIn(MIN_ASPECT, MAX_ASPECT) else 1f
        if (subject != null) return generateInSubject(random, n, a, subject)

        // The canvas in units of its shorter edge (glyph sizes are fractions of that edge): the shorter
        // edge is 1, the longer is a or 1 / a.
        val canvasW = if (a >= 1f) a else 1f
        val canvasH = if (a >= 1f) 1f else 1f / a
        val usableW = canvasW - 2f * MARGIN
        val usableH = canvasH - 2f * MARGIN

        // The grid that gives the biggest cells for n glyphs on this canvas, so a wide box gets a row
        // and a tall one a column instead of a square block lost in the middle of it.
        val (cols, rows) = bestGrid(n, usableW, usableH, wide = a >= 1f)
        val cellW = usableW / cols
        val cellH = usableH / rows

        // A glyph may exceed its cell a little (marks touching still reads as one scribble, which is
        // fine) but not so much that neighbours bury each other.
        val maxSize = minOf(MAX_SIZE, minOf(cellW, cellH) * 1.15f)
        val minSize = minOf(MIN_SIZE, maxSize * 0.75f)

        // Shuffle which cells get used so a count that doesn't fill the grid leaves its gaps in a random
        // place rather than always the last row.
        val cells = ArrayList<Pair<Int, Int>>(rows * cols)
        for (r in 0 until rows) for (c in 0 until cols) cells += r to c
        cells.shuffle(random)

        // Distinct characters, from the pool of ones that give the tracker the most to hold on to.
        val chars = GLYPH_POOL.shuffled(random)

        val glyphs = cells.take(n).mapIndexed { i, (r, c) ->
            val jx = (random.nextFloat() - 0.5f) * cellW * JITTER
            val jy = (random.nextFloat() - 0.5f) * cellH * JITTER
            // Clamped into the margin band: jitter is at most half a cell, so the clamp is a safety net
            // rather than something the common case relies on.
            val x = (MARGIN + cellW * (c + 0.5f) + jx).coerceIn(MARGIN, canvasW - MARGIN)
            val y = (MARGIN + cellH * (r + 0.5f) + jy).coerceIn(MARGIN, canvasH - MARGIN)
            ScribbleGlyph(
                char = chars[i],
                cx = x / canvasW,
                cy = y / canvasH,
                rotationDeg = randomRotation(random),
                sizeFrac = minSize + random.nextFloat() * (maxSize - minSize),
            )
        }

        return Scribble(glyphs, a)
    }

    /** Radius of the biggest glyph footprint the silhouette layout starts from: half [MAX_SIZE]. */
    private const val START_RADIUS = MAX_SIZE / 2f

    /** Glyph footprints are kept this far apart, as a multiple of their diameter, while there is room to. */
    private const val SPACING = 0.8f

    /** A glyph's footprint radius, as a share of the room it has: no more than this, so it stays clear of the edge. */
    private const val FILL_LOW = 0.75f
    private const val FILL_HIGH = 0.95f

    /**
     * The scribble laid out inside the artwork's silhouette, [mask] over a canvas of aspect [a]: every glyph's
     * footprint (a circle of radius `sizeFrac / 2` round its centre) lies wholly inside the subject, never on
     * the backdrop the artist has no intention of painting. There is no fallback to a layout that leaves it.
     *
     * The footprints start as big as the usual glyphs and spread as far apart as the shape allows (farthest-point
     * sampling over the cells that are deep enough inside it). Where the shape cannot hold [n] of them that
     * size, they shrink until it can; where it cannot hold them apart at any size (a sliver, a speck) they are
     * piled up on its deepest part, each as big as its own spot allows. Glyphs made smaller than the usual
     * range are drawn thin, so a pile still reads ([Scribble.thin]).
     */
    private fun generateInSubject(random: Random, n: Int, a: Float, mask: ScribbleFit.SubjectMask): Scribble {
        val canvasW = if (a >= 1f) a else 1f
        val canvasH = if (a >= 1f) 1f else 1f / a
        val cellW = canvasW / mask.width
        val cellH = canvasH / mask.height
        val cell = minOf(cellW, cellH)

        // The cells inside the subject, and how deep each is: the radius of the largest circle about its
        // centre that stays inside the subject and the canvas.
        val ix = ArrayList<Float>(); val iy = ArrayList<Float>()
        val ox = ArrayList<Float>(); val oy = ArrayList<Float>()
        for (cy in 0 until mask.height) for (cx in 0 until mask.width) {
            val x = (cx + 0.5f) * cellW; val y = (cy + 0.5f) * cellH
            if (mask[cx, cy]) { ix += x; iy += y } else { ox += x; oy += y }
        }
        if (ix.isEmpty()) { ix += canvasW / 2f; iy += canvasH / 2f } // a speck the cells missed: its middle
        val m = ix.size
        val depth = FloatArray(m) { i ->
            var d = minOf(ix[i], canvasW - ix[i], iy[i], canvasH - iy[i])
            for (k in ox.indices) {
                // 0.71 of a cell: its half-diagonal, so a circle that clears this centre clears the whole cell.
                d = minOf(d, hypot(ix[i] - ox[k], iy[i] - oy[k]) - 0.71f * cell)
            }
            maxOf(d, 0f)
        }
        val maxDepth = depth.max()

        // The biggest size at which n footprints fit apart; smaller until they do.
        var radius = minOf(START_RADIUS, maxDepth)
        var chosen: IntArray? = null
        val smallest = 0.5f * cell
        while (chosen == null && radius >= smallest) {
            val deepEnough = (0 until m).filter { depth[it] >= radius }
            chosen = spread(random, deepEnough, n, ix, iy, SPACING * 2f * radius)
            if (chosen == null) radius *= 0.9f
        }

        val picks: IntArray
        val radii: FloatArray
        if (chosen != null) {
            picks = chosen
            radii = FloatArray(n) { radius }
        } else {
            // No room to keep them apart: a pile on the deepest part of the subject, each as big as its spot allows.
            val deep = (0 until m).filter { depth[it] >= 0.5f * maxDepth }.ifEmpty { listOf(depth.indices.maxByOrNull { depth[it] } ?: 0) }
            picks = spread(random, deep, n, ix, iy, 0f) ?: IntArray(n) { deep[random.nextInt(deep.size)] }
            radii = FloatArray(n) { minOf(depth[picks[it]], START_RADIUS) }
        }

        val chars = GLYPH_POOL.shuffled(random)
        val glyphs = (0 until n).map { k ->
            val i = picks[k]
            val rad = maxOf(radii[k] * (FILL_LOW + random.nextFloat() * (FILL_HIGH - FILL_LOW)), 0.3f * cell)
            ScribbleGlyph(
                char = chars[k],
                cx = ix[i] / canvasW,
                cy = iy[i] / canvasH,
                rotationDeg = randomRotation(random),
                sizeFrac = 2f * rad,
            )
        }
        return Scribble(glyphs, a, thin = glyphs.any { it.sizeFrac < MIN_SIZE })
    }

    /**
     * [n] of the [candidates] (indices into [xs], [ys]) as far from one another as they go: the first at random,
     * each next the one farthest from those chosen (with a little noise, so equal choices vary), and null when the
     * next would be nearer than [minDistance]. Fewer candidates than [n] reuse them.
     */
    private fun spread(random: Random, candidates: List<Int>, n: Int, xs: List<Float>, ys: List<Float>, minDistance: Float): IntArray? {
        if (candidates.isEmpty()) return null
        val chosen = IntArray(n)
        chosen[0] = candidates[random.nextInt(candidates.size)]
        val nearest = FloatArray(candidates.size) { k -> dist(xs, ys, candidates[k], chosen[0]) }
        for (c in 1 until n) {
            var best = -1; var bestScore = -1f
            for (k in candidates.indices) {
                val score = nearest[k] * (1f + 0.25f * random.nextFloat())
                if (score > bestScore) { bestScore = score; best = k }
            }
            if (best < 0 || nearest[best] < minDistance) return null
            chosen[c] = candidates[best]
            for (k in candidates.indices) nearest[k] = minOf(nearest[k], dist(xs, ys, candidates[k], chosen[c]))
        }
        return chosen
    }

    private fun dist(xs: List<Float>, ys: List<Float>, a: Int, b: Int): Float =
        hypot(xs[a] - xs[b], ys[a] - ys[b])

    /**
     * Columns and rows for [n] cells on a [usableW] x [usableH] area: the biggest cells that hold n,
     * then the fewest empty ones, then the longer count along the longer side ([wide]: columns).
     */
    internal fun bestGrid(n: Int, usableW: Float, usableH: Float, wide: Boolean): Pair<Int, Int> {
        var best = 1 to n
        var bestCell = -1f
        var bestEmpty = Int.MAX_VALUE
        for (cols in 1..n) {
            val rows = ceil(n / cols.toFloat()).toInt()
            val cell = minOf(usableW / cols, usableH / rows)
            val empty = cols * rows - n
            val longer = if (wide) cols >= rows else rows >= cols
            val bestLonger = if (wide) best.first >= best.second else best.second >= best.first
            val better = cell > bestCell + 1e-6f ||
                (cell > bestCell - 1e-6f && (empty < bestEmpty || (empty == bestEmpty && longer && !bestLonger)))
            if (better) {
                best = cols to rows
                bestCell = cell
                bestEmpty = empty
            }
        }
        return best
    }

    /** True iff glyph [a]'s circular footprint overlaps glyph [b]'s (centre distance < radius sum). */
    fun overlaps(a: ScribbleGlyph, b: ScribbleGlyph, aspect: Float = 1f): Boolean =
        centreDistance(a, b, aspect) < (a.sizeFrac / 2f + b.sizeFrac / 2f)

    /**
     * Centre-to-centre distance between two glyphs, in units of the canvas' shorter edge: the same
     * unit as [ScribbleGlyph.sizeFrac], whatever the canvas' [aspect].
     */
    fun centreDistance(a: ScribbleGlyph, b: ScribbleGlyph, aspect: Float = 1f): Float {
        val w = if (aspect >= 1f) aspect else 1f
        val h = if (aspect >= 1f) 1f else 1f / aspect
        return hypot((a.cx - b.cx) * w, (a.cy - b.cy) * h)
    }

    private fun randomRotation(random: Random): Float = random.nextFloat() * 360f
}
