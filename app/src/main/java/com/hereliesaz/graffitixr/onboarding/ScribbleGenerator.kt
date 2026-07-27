package com.hereliesaz.graffitixr.onboarding

import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.sqrt
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

/** A set of glyphs forming one scribble for the user to copy. */
data class Scribble(val glyphs: List<ScribbleGlyph>)

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

    private val ALPHABET: List<Char> =
        (('A'..'Z') + ('a'..'z') + ('2'..'9') + listOf('#', '@', '&', '%', '*', '?', '!', '+', '=')).toList()

    /**
     * Glyph footprint size range, as a fraction of the canvas' smaller edge. Deliberately large —
     * these get copied onto a wall by hand, and a small mark is both fiddly to draw and poor to
     * detect. Capped per-layout against the grid cell so a dense layout doesn't collapse into a blob.
     */
    private const val MIN_SIZE = 0.20f
    private const val MAX_SIZE = 0.34f

    /** Keep centres inside a margin so rotated glyphs don't clip the canvas edge. */
    private const val MARGIN = 0.12f

    /**
     * How far a glyph may wander from its grid cell centre, as a fraction of the cell. Enough to
     * break up the grid so it doesn't read as a table, not enough to re-cluster.
     */
    private const val JITTER = 0.45f

    /**
     * @param random seeded for determinism/testability.
     * @param glyphCount desired glyph count; coerced into [[MIN_GLYPHS], [MAX_GLYPHS]].
     */
    fun generate(
        random: Random = Random.Default,
        glyphCount: Int = random.nextInt(MIN_GLYPHS, MAX_GLYPHS + 1),
    ): Scribble {
        val n = glyphCount.coerceIn(MIN_GLYPHS, MAX_GLYPHS)

        // Near-square grid big enough to hold n cells; shuffle which cells get used so a count that
        // doesn't fill the grid leaves its gaps in a random place rather than always the last row.
        val cols = ceil(sqrt(n.toFloat())).toInt().coerceAtLeast(1)
        val rows = ceil(n / cols.toFloat()).toInt().coerceAtLeast(1)
        val usable = 1f - 2f * MARGIN
        val cellW = usable / cols
        val cellH = usable / rows

        // A glyph may exceed its cell a little (marks touching still reads as one scribble, which is
        // fine) but not so much that neighbours bury each other.
        val maxSize = minOf(MAX_SIZE, minOf(cellW, cellH) * 1.15f)
        val minSize = minOf(MIN_SIZE, maxSize * 0.75f)

        val cells = ArrayList<Pair<Int, Int>>(rows * cols)
        for (r in 0 until rows) for (c in 0 until cols) cells += r to c
        cells.shuffle(random)

        val glyphs = cells.take(n).map { (r, c) ->
            val jx = (random.nextFloat() - 0.5f) * cellW * JITTER
            val jy = (random.nextFloat() - 0.5f) * cellH * JITTER
            ScribbleGlyph(
                char = ALPHABET.random(random),
                // Clamped into the margin band: jitter is at most half a cell, so the clamp is a
                // safety net rather than something the common case relies on.
                cx = (MARGIN + cellW * (c + 0.5f) + jx).coerceIn(MARGIN, 1f - MARGIN),
                cy = (MARGIN + cellH * (r + 0.5f) + jy).coerceIn(MARGIN, 1f - MARGIN),
                rotationDeg = randomRotation(random),
                sizeFrac = minSize + random.nextFloat() * (maxSize - minSize),
            )
        }

        return Scribble(glyphs)
    }

    /** True iff glyph [a]'s circular footprint overlaps glyph [b]'s (centre distance < radius sum). */
    fun overlaps(a: ScribbleGlyph, b: ScribbleGlyph): Boolean =
        centreDistance(a, b) < (a.sizeFrac / 2f + b.sizeFrac / 2f)

    /** Centre-to-centre distance between two glyphs, in normalized canvas units. */
    fun centreDistance(a: ScribbleGlyph, b: ScribbleGlyph): Float = hypot(a.cx - b.cx, a.cy - b.cy)

    private fun randomRotation(random: Random): Float = random.nextFloat() * 360f
}
