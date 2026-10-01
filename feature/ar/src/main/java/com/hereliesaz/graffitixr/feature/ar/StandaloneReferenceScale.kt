package com.hereliesaz.graffitixr.feature.ar

/**
 * Validation for the physical width represented by the rectified standalone wall target.
 *
 * The value is the real-world distance between the left and right edges of the unwarped reference
 * image. KPM converts this width into reference DPI, so accepting nonsense here would make every
 * translation and rendered dimension nonsense by the same factor.
 */
internal object StandaloneReferenceScale {
    const val MIN_METERS: Float = 0.05f
    const val MAX_METERS: Float = 100f

    fun parseMeters(raw: String): Float? {
        val normalized = raw.trim().replace(',', '.')
        val value = normalized.toFloatOrNull() ?: return null
        if (!value.isFinite()) return null
        return value.takeIf { it in MIN_METERS..MAX_METERS }
    }
}
