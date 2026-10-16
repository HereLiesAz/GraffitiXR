package com.hereliesaz.graffitixr.feature.ar

/**
 * Validation for the physical width represented by the rectified standalone wall target.
 *
 * The value is the real-world distance between the left and right edges of the unwarped reference
 * image. KPM converts this width into reference DPI, so accepting nonsense here would make every
 * translation and rendered dimension nonsense by the same factor.
 *
 * Everything stored and passed on is **metres**. The artist may type the width in the unit the
 * Settings → Units choice selects (metric: metres; imperial: feet and inches), so [parseMeters]
 * with `imperial = true` converts before range-checking; the range itself is a metric constant.
 */
internal object StandaloneReferenceScale {
    const val MIN_METERS: Float = 0.05f
    const val MAX_METERS: Float = 100f

    private const val METERS_PER_FOOT = 0.3048
    private const val METERS_PER_INCH = 0.0254

    /** `12'`, `12'6`, `12'6"`, `78"` — after units are normalised to ' and " and spaces removed. */
    private val FEET_INCHES = Regex("""^(?:(\d+(?:\.\d+)?)')?(?:(\d+(?:\.\d+)?)"?)?$""")

    private val DIGIT_SPACE_DIGIT = Regex("""\d\s+\d""")

    /** Metric parse: a plain number is metres (a decimal comma is accepted). */
    fun parseMeters(raw: String): Float? {
        val normalized = raw.trim().replace(',', '.')
        val value = normalized.toFloatOrNull() ?: return null
        if (!value.isFinite()) return null
        return value.takeIf { it in MIN_METERS..MAX_METERS }
    }

    /**
     * Unit-aware parse to metres. Metric: exactly [parseMeters]. Imperial accepts feet and inches in
     * the usual spellings — `12.5` (a bare number is decimal **feet**), `12'`, `12' 6"`, `12'6`,
     * `12 ft 6 in`, `78"`, `78 in`, `12 feet`, `6 inches` — and rejects anything else (including
     * several numbers with no unit marks, such as `12 6`). The result must land in
     * [MIN_METERS]..[MAX_METERS] like a metric entry.
     */
    fun parseMeters(raw: String, imperial: Boolean): Float? {
        if (!imperial) return parseMeters(raw)
        val spaced = raw.lowercase()
            .replace('′', '\'').replace('’', '\'')
            .replace('″', '"').replace('”', '"')
            .replace("feet", "'").replace("foot", "'").replace("ft", "'")
            .replace("inches", "\"").replace("inch", "\"").replace("in", "\"")
            .replace(',', '.')
        // Two numbers split by a space and no unit mark ("12 6") are ambiguous, not "126 ft".
        if (DIGIT_SPACE_DIGIT.containsMatchIn(spaced)) return null
        val compact = spaced.filterNot { it.isWhitespace() }
        if (compact.isEmpty()) return null
        val meters: Double = if ('\'' !in compact && '"' !in compact) {
            // A bare number: decimal feet.
            (compact.toDoubleOrNull() ?: return null) * METERS_PER_FOOT
        } else {
            val match = FEET_INCHES.matchEntire(compact) ?: return null
            val feet = match.groupValues[1].takeIf { it.isNotEmpty() }?.toDoubleOrNull()
            val inches = match.groupValues[2].takeIf { it.isNotEmpty() }?.toDoubleOrNull()
            if (feet == null && inches == null) return null
            (feet ?: 0.0) * METERS_PER_FOOT + (inches ?: 0.0) * METERS_PER_INCH
        }
        if (!meters.isFinite()) return null
        val value = meters.toFloat()
        return value.takeIf { it in MIN_METERS..MAX_METERS }
    }

    /** The accepted range as shown to the artist: ("0.05 m", "100 m") or ("2 in", "328 ft"). */
    fun rangeLabels(imperial: Boolean): Pair<String, String> =
        if (imperial) "2 in" to "328 ft" else "0.05 m" to "100 m"
}
