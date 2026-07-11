package com.hereliesaz.graffitixr.onboarding

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/**
 * A single character placed in the onboarding scribble, expressed in a normalized [0,1] x [0,1]
 * canvas so the renderer can scale it to any size. [sizeFrac] is the glyph box edge as a fraction
 * of the canvas' smaller dimension; the glyph's circular footprint (used for the overlap rule) has
 * radius `sizeFrac / 2` around ([cx], [cy]).
 */
data class ScribbleGlyph(
    val char: Char,
    val cx: Float,
    val cy: Float,
    val rotationDeg: Float,
    val sizeFrac: Float,
)

/** An ordered set of overlapping glyphs forming one connected scribble. */
data class Scribble(val glyphs: List<ScribbleGlyph>)

/**
 * Generates the random "doodle this" scribble shown at first run: a small cluster of characters
 * (letters, digits, symbols) at random positions and rotations, with the rule that **every glyph
 * overlaps at least one other** so the result reads as a single connected mark rather than scattered
 * confetti. A complexity cap ([MAX_GLYPHS]) keeps it drawable in a few seconds regardless of the
 * user's utensil.
 *
 * Pure and deterministic given a seeded [Random], so the placement logic is unit-testable without
 * Android. Rendering (rotation, stroke) is the caller's job.
 */
object ScribbleGenerator {

    /** Complexity cap — more than this becomes an unreadable tangle nobody wants to copy. */
    const val MAX_GLYPHS = 7
    const val MIN_GLYPHS = 3

    private val ALPHABET: List<Char> =
        (('A'..'Z') + ('a'..'z') + ('2'..'9') + listOf('#', '@', '&', '%', '*', '?', '!', '+', '=')).toList()

    // Glyph footprint size range, as a fraction of the canvas' smaller edge.
    private const val MIN_SIZE = 0.14f
    private const val MAX_SIZE = 0.24f

    // Keep centres inside a margin so rotated glyphs don't clip the canvas edge.
    private const val MARGIN = 0.16f

    // A new glyph is dropped this fraction of (r_self + r_neighbor) from a chosen neighbour's
    // centre. < 1.0 forces the footprints to overlap (two circles overlap iff centre distance is
    // below the radius sum); the range keeps the overlap visible but not fully concentric.
    private const val MIN_OVERLAP_SPACING = 0.45f
    private const val MAX_OVERLAP_SPACING = 0.80f

    /**
     * @param random seeded for determinism/testability.
     * @param glyphCount desired glyph count; coerced into [[MIN_GLYPHS], [MAX_GLYPHS]].
     */
    fun generate(
        random: Random = Random.Default,
        glyphCount: Int = random.nextInt(MIN_GLYPHS, MAX_GLYPHS + 1),
    ): Scribble {
        val n = glyphCount.coerceIn(MIN_GLYPHS, MAX_GLYPHS)
        val glyphs = ArrayList<ScribbleGlyph>(n)

        // Seed glyph near centre with a little jitter.
        glyphs += ScribbleGlyph(
            char = ALPHABET.random(random),
            cx = 0.5f + random.nextFloat() * 0.1f - 0.05f,
            cy = 0.5f + random.nextFloat() * 0.1f - 0.05f,
            rotationDeg = randomRotation(random),
            sizeFrac = randomSize(random),
        )

        while (glyphs.size < n) {
            val neighbor = glyphs[random.nextInt(glyphs.size)]
            val size = randomSize(random)
            val rSelf = size / 2f
            val rNeighbor = neighbor.sizeFrac / 2f
            val spacing = MIN_OVERLAP_SPACING + random.nextFloat() * (MAX_OVERLAP_SPACING - MIN_OVERLAP_SPACING)
            val dist = (rSelf + rNeighbor) * spacing
            val angle = random.nextFloat() * (2f * Math.PI.toFloat())

            // Offset from the neighbour. `dist < rSelf + rNeighbor` (spacing < 1) guarantees the
            // footprints overlap. To keep the glyph on-canvas WITHOUT shrinking that distance (which
            // could break the overlap), mirror the offset on any axis that would leave the margin —
            // reflection preserves |offset|, so overlap with the neighbour is retained exactly. With
            // dist <= MAX_SIZE (0.24) and a [MARGIN, 1-MARGIN] band of width 0.68 > 2*0.24, at least
            // one direction per axis always lands in-bounds.
            var dx = cos(angle) * dist
            var dy = sin(angle) * dist
            if (neighbor.cx + dx !in MARGIN..(1f - MARGIN)) dx = -dx
            if (neighbor.cy + dy !in MARGIN..(1f - MARGIN)) dy = -dy

            glyphs += ScribbleGlyph(
                char = ALPHABET.random(random),
                cx = neighbor.cx + dx,
                cy = neighbor.cy + dy,
                rotationDeg = randomRotation(random),
                sizeFrac = size,
            )
        }

        return Scribble(glyphs)
    }

    /** True iff glyph [a]'s circular footprint overlaps glyph [b]'s (centre distance < radius sum). */
    fun overlaps(a: ScribbleGlyph, b: ScribbleGlyph): Boolean {
        val centreDist = hypot(a.cx - b.cx, a.cy - b.cy)
        return centreDist < (a.sizeFrac / 2f + b.sizeFrac / 2f)
    }

    private fun randomRotation(random: Random): Float = random.nextFloat() * 360f

    private fun randomSize(random: Random): Float = MIN_SIZE + random.nextFloat() * (MAX_SIZE - MIN_SIZE)
}
