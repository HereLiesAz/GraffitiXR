package com.hereliesaz.graffitixr.common.model

/**
 * Which photosphere tiles a [CoverageGlowStyle] builds its glow geometry from.
 *
 * SphereSLAM's `CoverageGlowRenderer` draws either the complement of the supplied triangles (a wash
 * with holes, fed `PhotosphereMap.currentRegions()`) or the triangles themselves (fed
 * `PhotosphereMap.regionsNeedingUpdate()`), so a style has to say which region list feeds it.
 */
enum class CoverageGlowGeometry {
    /** `PhotosphereMap.currentRegions()`: the captured, up-to-date tiles. */
    CURRENT_TILES,

    /** `PhotosphereMap.regionsNeedingUpdate()`: the missing or stale tiles. */
    TILES_NEEDING_UPDATE,
}

/**
 * The user-selectable look of the standalone SphereSLAM photosphere coverage glow.
 *
 * Each entry names the SphereSLAM `CoverageGlowRenderer.FillMode` it draws with by NAME
 * ([rendererFillMode]) because `:core:common` does not (and should not) depend on the `:overlay`
 * library; `:feature:ar` resolves the name and a unit test there proves every entry resolves.
 *
 * Adding a style is one line here plus its Settings label/description in every locale. Only styles
 * the pinned library can actually draw belong in this list: Settings offers every entry, and
 * `CoverageGlowStyleMappingTest` proves each name resolves against the pinned `:overlay`.
 *
 * Persisted by [storageKey], never by ordinal or enum name, so reordering or renaming entries can't
 * silently change what a saved preference means.
 */
enum class CoverageGlowStyle(
    val storageKey: String,
    val rendererFillMode: String,
    val geometry: CoverageGlowGeometry,
) {
    /** Glow everywhere except the captured tiles (the original, default look). */
    WASH("wash", "COMPLEMENT", CoverageGlowGeometry.CURRENT_TILES),

    /** Glow only over the tiles still missing or stale. */
    MISSING_TILES("missing_tiles", "TRIANGLES", CoverageGlowGeometry.TILES_NEEDING_UPDATE),

    /**
     * Only a soft glowing edge just outside the captured tiles, on the side that still needs
     * scanning (SphereSLAM 0.24.3 `FillMode.OUTLINE`). Nothing is drawn before the first keyframe.
     */
    OUTLINE("outline", "OUTLINE", CoverageGlowGeometry.CURRENT_TILES),

    /** The [WASH] plus the [OUTLINE] edge on top (SphereSLAM 0.24.3 `FillMode.COMPLEMENT_OUTLINED`). */
    WASH_OUTLINED("wash_outlined", "COMPLEMENT_OUTLINED", CoverageGlowGeometry.CURRENT_TILES),
    ;

    companion object {
        val DEFAULT: CoverageGlowStyle = WASH

        /** The style persisted as [key]; unknown or absent keys (e.g. a downgrade) fall back to [DEFAULT]. */
        fun fromStorageKey(key: String?): CoverageGlowStyle =
            entries.firstOrNull { it.storageKey == key } ?: DEFAULT

        /** The next style after [current] for a tap-to-cycle Settings row, wrapping. */
        fun next(current: CoverageGlowStyle): CoverageGlowStyle =
            entries[(current.ordinal + 1) % entries.size]
    }
}

/**
 * The fixed glow-colour palette offered in Settings: monochrome first (the app's aesthetic is
 * monochromatic and dark), then a couple of accents. Persisted as the opaque ARGB int, so the stored
 * value stays meaningful even if the palette changes.
 */
enum class CoverageGlowColor(val argb: Int) {
    WHITE(0xFFFFFFFF.toInt()),
    LIGHT_GREY(0xFFBDBDBD.toInt()),
    MID_GREY(0xFF808080.toInt()),
    BLACK(0xFF000000.toInt()),
    HOT_PINK(0xFFFF69B4.toInt()),
    CYAN(0xFF00E5FF.toInt()),
    ;

    companion object {
        val DEFAULT: CoverageGlowColor = WHITE

        /** The palette entry for [argb] (alpha ignored), or null for a colour outside the palette. */
        fun fromArgb(argb: Int): CoverageGlowColor? =
            entries.firstOrNull { (it.argb and RGB_MASK) == (argb and RGB_MASK) }

        private const val RGB_MASK = 0x00FFFFFF
    }
}

/** Glow intensity (opacity over the camera preview) bounds, as a fraction in `[0, 1]`. */
object CoverageGlowIntensity {
    const val MIN = 0f
    const val MAX = 0.6f

    /** The shipped 14% wash. */
    const val DEFAULT = 0.14f

    /** Clamp to `[MIN, MAX]`; non-finite values fall back to [DEFAULT]. */
    fun sanitize(value: Float): Float = if (value.isFinite()) value.coerceIn(MIN, MAX) else DEFAULT
}

/** The three coverage-glow settings as one snapshot, as the renderer consumes them. */
data class CoverageGlowAppearance(
    /** Opaque ARGB; the alpha byte is ignored (opacity is [intensity]). */
    val colorArgb: Int = CoverageGlowColor.DEFAULT.argb,
    val intensity: Float = CoverageGlowIntensity.DEFAULT,
    val style: CoverageGlowStyle = CoverageGlowStyle.DEFAULT,
) {
    /** [colorArgb] as the `[r, g, b]` floats in `[0, 1]` the renderer takes. */
    fun rgb(): FloatArray = floatArrayOf(
        ((colorArgb shr 16) and 0xFF) / 255f,
        ((colorArgb shr 8) and 0xFF) / 255f,
        (colorArgb and 0xFF) / 255f,
    )

    /** [intensity] clamped to the renderer-safe range. */
    val safeIntensity: Float get() = CoverageGlowIntensity.sanitize(intensity)

    companion object {
        val DEFAULT = CoverageGlowAppearance()
    }
}
