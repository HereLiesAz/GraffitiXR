package com.hereliesaz.graffitixr.onboarding

import kotlin.math.abs
import kotlin.math.floor

/**
 * The colour a first-run glyph takes over the picture behind it: the backdrop's *opposite*, which is not
 * its per-channel negative. The negative of a grey is a grey, so a glyph over a mid-tone vanished
 * exactly where it needed to read. What is opposite to muted is vivid:
 *
 *  - **hue** turns half way round the wheel; a neutral has none to turn, so it takes a vivid hue chosen for
 *    lightness ([LIGHT_HUE], cyan, over a dark neutral; [DARK_HUE], hot pink, over a light one);
 *  - **saturation** is turned over: a muted backdrop (little chroma, which is saturation times value, so a
 *    dark colour counts as muted) gets a fully saturated glyph, and a vivid backdrop a neutral one;
 *  - **value** is turned over: a light patch gets a dark glyph and a dark one a light glyph.
 *
 * Where that still leaves the glyph too near the backdrop in lightness, its value is pushed away from the
 * backdrop's until the two are [MIN_SEPARATION] apart (lightness and chroma both count), and if even the extreme value does not do it the glyph
 * loses saturation, only as far as it takes, towards the neutral that always does.
 *
 * Pure arithmetic on hue, saturation and value in 0..1; the draw code works it out once per glyph pixel.
 */
object GlyphColor {

    /** The hue (0..1 round the wheel) a glyph takes over a dark neutral backdrop: cyan, a light hue, as the app's highlight. */
    const val LIGHT_HUE = 0.5f

    /** The hue a glyph takes over a light neutral backdrop: hot pink, a dark hue. */
    const val DARK_HUE = 0.9f

    /** Backdrops with less chroma than this have so little hue that it is blended towards the neutral hue. */
    const val ACHROMATIC_BELOW = 0.15f

    /** The least [separation] the glyph may have from its backdrop. */
    const val MIN_SEPARATION = 0.30f

    /** How much a difference in chroma counts beside a difference in lightness: a vivid glyph on a grey is clear at any lightness. */
    const val CHROMA_WEIGHT = 0.5f

    private const val VALUE_STEP = 0.1f
    private const val MAX_STEPS = 9

    /** [rgb] (alpha ignored) as hue, saturation and value, each 0..1, written to [out] (3 floats). */
    fun toHsv(rgb: Int, out: FloatArray) {
        val r = ((rgb shr 16) and 0xFF) / 255f
        val g = ((rgb shr 8) and 0xFF) / 255f
        val b = (rgb and 0xFF) / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val d = max - min
        val h = when {
            d <= 0f -> 0f
            max == r -> ((g - b) / d) / 6f
            max == g -> (((b - r) / d) + 2f) / 6f
            else -> (((r - g) / d) + 4f) / 6f
        }
        out[0] = if (h < 0f) h + 1f else h
        out[1] = if (max <= 0f) 0f else d / max
        out[2] = max
    }

    /** Hue, saturation and value (each 0..1) as 0xRRGGBB. */
    fun toRgb(h: Float, s: Float, v: Float): Int {
        val hh = (h - floor(h)) * 6f
        val i = floor(hh).toInt()
        val f = hh - i
        val p = v * (1f - s)
        val q = v * (1f - s * f)
        val t = v * (1f - s * (1f - f))
        // Not a Triple: this runs for every glyph pixel, and an allocation each would be the whole cost.
        val r: Float
        val g: Float
        val b: Float
        when (i) {
            0 -> { r = v; g = t; b = p }
            1 -> { r = q; g = v; b = p }
            2 -> { r = p; g = v; b = t }
            3 -> { r = p; g = q; b = v }
            4 -> { r = t; g = p; b = v }
            else -> { r = v; g = p; b = q }
        }
        return (to255(r) shl 16) or (to255(g) shl 8) or to255(b)
    }

    /** Relative lightness of [rgb] (alpha ignored), 0..1. */
    fun luma(rgb: Int): Float =
        (0.2126f * ((rgb shr 16) and 0xFF) + 0.7152f * ((rgb shr 8) and 0xFF) + 0.0722f * (rgb and 0xFF)) / 255f

    /** Colourfulness of [rgb] (alpha ignored): the spread of its channels, 0..1. */
    fun chroma(rgb: Int): Float {
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF
        return (maxOf(r, g, b) - minOf(r, g, b)) / 255f
    }

    /** How far apart two colours read: the difference in lightness plus [CHROMA_WEIGHT] of the difference in chroma. */
    fun separation(a: Int, b: Int): Float =
        abs(luma(a) - luma(b)) + CHROMA_WEIGHT * abs(chroma(a) - chroma(b))

    /** The glyph colour (0xRRGGBB) over a backdrop of hue [h], saturation [s] and value [v], each 0..1. */
    fun opposite(h: Float, s: Float, v: Float): Int {
        val value0 = v.coerceIn(0f, 1f)
        val chroma = s.coerceIn(0f, 1f) * value0
        val backRgb = toRgb(h, s.coerceIn(0f, 1f), value0)
        val back = luma(backRgb)

        // A neutral backdrop has no hue to turn: a light vivid hue over a dark one, a dark vivid hue over a light one.
        val toward = smoothstep(0.35f, 0.65f, value0)
        val neutralHue = blendHue(LIGHT_HUE, DARK_HUE, toward)
        val vivid = (chroma / ACHROMATIC_BELOW).coerceIn(0f, 1f)
        val hue = blendHue(neutralHue, h + 0.5f, vivid)
        val newSat = 1f - chroma

        var value = 1f - value0
        var sat = newSat
        var rgb = toRgb(hue, sat, value)
        // Push the value away from the backdrop's lightness, as far as the extreme if need be...
        val towards = if (back < 0.5f) VALUE_STEP else -VALUE_STEP
        var steps = 0
        while (separation(rgb, backRgb) < MIN_SEPARATION && steps < MAX_STEPS) {
            val next = (value + towards).coerceIn(0f, 1f)
            if (next == value) break
            value = next
            rgb = toRgb(hue, sat, value)
            steps++
        }
        // ...then, only as far as it takes, let the glyph go from vivid towards neutral at that value: the
        // neutral extreme is always at least half the range from any backdrop's lightness.
        while (separation(rgb, backRgb) < MIN_SEPARATION && sat > 0f) {
            sat = maxOf(0f, sat - VALUE_STEP)
            rgb = toRgb(hue, sat, value)
        }
        return rgb
    }

    private fun smoothstep(a: Float, b: Float, x: Float): Float {
        val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** The point [w] of the way from hue [a] to hue [b] by the shorter arc, 0..1. */
    private fun blendHue(a: Float, b: Float, w: Float): Float {
        val d = (((b - a) % 1f) + 1.5f) % 1f - 0.5f
        val x = a + d * w
        return x - floor(x)
    }

    private fun to255(x: Float): Int = (x.coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255)
}
