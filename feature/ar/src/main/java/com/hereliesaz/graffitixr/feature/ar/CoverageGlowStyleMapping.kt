package com.hereliesaz.graffitixr.feature.ar

import com.hereliesaz.graffitixr.common.model.CoverageGlowGeometry
import com.hereliesaz.graffitixr.common.model.CoverageGlowIntensity
import com.hereliesaz.graffitixr.common.model.CoverageGlowStyle
import com.hereliesaz.sphereslam.SphereCoverage
import com.hereliesaz.sphereslam.overlay.CoverageGlowRenderer
import kotlin.math.pow

/**
 * Maps the persisted [CoverageGlowStyle] onto SphereSLAM's `:overlay` renderer: which
 * [CoverageGlowRenderer.FillMode] to draw with, which photosphere regions feed it, and how the
 * user's single intensity setting drives the edge glow of the outline styles.
 */
internal object CoverageGlowStyleMapping {

    /**
     * The renderer fill mode for [style]. A style whose mode the pinned library doesn't have (a
     * setting written by a newer app build, say) falls back to the default style as a whole — never
     * to the default MODE alone, which would pair it with the wrong geometry and invert the glow.
     */
    fun fillModeFor(style: CoverageGlowStyle): CoverageGlowRenderer.FillMode =
        resolve(style) ?: requireNotNull(resolve(CoverageGlowStyle.DEFAULT)) {
            "SphereSLAM no longer has the default glow fill mode ${CoverageGlowStyle.DEFAULT.rendererFillMode}"
        }

    /** The style actually drawn for [style] (itself, or the default when its mode is unavailable). */
    fun effectiveStyle(style: CoverageGlowStyle): CoverageGlowStyle =
        if (resolve(style) != null) style else CoverageGlowStyle.DEFAULT

    private fun resolve(style: CoverageGlowStyle): CoverageGlowRenderer.FillMode? =
        CoverageGlowRenderer.FillMode.entries.firstOrNull { it.name == style.rendererFillMode }

    /**
     * The regions to project for [style], or null for "the whole view".
     *
     * Null only for the needs-update geometry while nothing is known yet: before the photosphere's
     * first keyframe anchors its heading, `regionsNeedingUpdate()` is empty even though every tile
     * is missing. The whole view glows then, exactly as the wash style does. The current-tiles
     * styles never need it: no current tile means a full wash (no holes) or, for the edge-only
     * style, no edge yet.
     */
    fun regionsFor(
        style: CoverageGlowStyle,
        currentRegions: List<SphereCoverage.TileRegion>,
        regionsNeedingUpdate: List<SphereCoverage.TileRegion>,
    ): List<SphereCoverage.TileRegion>? = when (effectiveStyle(style).geometry) {
        CoverageGlowGeometry.CURRENT_TILES -> currentRegions
        CoverageGlowGeometry.TILES_NEEDING_UPDATE ->
            if (regionsNeedingUpdate.isEmpty() && currentRegions.isEmpty()) null else regionsNeedingUpdate
    }

    /**
     * Peak opacity of the edge glow ([CoverageGlowRenderer.outlineAlpha]) for the user's glow
     * [intensity] (the wash opacity, `0..`[CoverageGlowIntensity.MAX]).
     *
     * The slider's full range spans the edge's full range: `edge = (intensity / MAX)^`
     * [OUTLINE_ALPHA_EXPONENT]. The exponent (`ln 0.5 / ln(0.14 / 0.6)` ≈ 0.477) puts the shipped
     * 14% wash at ≈ 0.5 — the library's own default edge — while 0 is still "no glow" and only the
     * slider's 60% maximum reaches 1, so every step of the slider changes the edge. (A linear gain
     * of 3.5 saturated at ≈ 29%, leaving the top half of the slider dead for the edge styles.)
     */
    fun outlineAlphaFor(intensity: Float): Float {
        val fraction = (CoverageGlowIntensity.sanitize(intensity) / CoverageGlowIntensity.MAX)
            .coerceIn(0f, 1f)
        return fraction.toDouble().pow(OUTLINE_ALPHA_EXPONENT).toFloat().coerceIn(0f, 1f)
    }

    /**
     * Edge glow width in framebuffer pixels ([CoverageGlowRenderer.outlineWidthPx]) for a display
     * [density] (px per dp): [OUTLINE_WIDTH_DP] dp, inside the renderer's accepted range. A
     * non-finite or non-positive density falls back to mdpi.
     */
    fun outlineWidthPxFor(density: Float): Float {
        val d = if (density.isFinite() && density > 0f) density else 1f
        return (OUTLINE_WIDTH_DP * d).coerceIn(1f, CoverageGlowRenderer.MAX_OUTLINE_WIDTH_PX)
    }

    /** Curve of the edge opacity over the slider fraction; 14% of a 60% slider → 0.5. */
    const val OUTLINE_ALPHA_EXPONENT = 0.4771

    /** Edge-glow width: about a fingertip's edge on a phone, wide enough to read at arm's length. */
    const val OUTLINE_WIDTH_DP = 10f

    /** Two NDC triangles covering the viewport (`GL_TRIANGLES`, x/y per vertex). */
    val FULL_VIEW_TRIANGLES: FloatArray
        get() = floatArrayOf(
            -1f, -1f, 1f, -1f, 1f, 1f,
            -1f, -1f, 1f, 1f, -1f, 1f,
        )
}
