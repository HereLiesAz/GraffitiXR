package com.hereliesaz.graffitixr.feature.ar

import com.hereliesaz.graffitixr.common.model.CoverageGlowGeometry
import com.hereliesaz.graffitixr.common.model.CoverageGlowStyle
import com.hereliesaz.sphereslam.SphereCoverage
import com.hereliesaz.sphereslam.overlay.CoverageGlowRenderer

/**
 * Maps the persisted [CoverageGlowStyle] onto SphereSLAM's `:overlay` renderer: which
 * [CoverageGlowRenderer.FillMode] to draw with, and which photosphere regions feed it.
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
     * is missing. The whole view glows then, exactly as the wash style does.
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

    /** Two NDC triangles covering the viewport (`GL_TRIANGLES`, x/y per vertex). */
    val FULL_VIEW_TRIANGLES: FloatArray
        get() = floatArrayOf(
            -1f, -1f, 1f, -1f, 1f, 1f,
            -1f, -1f, 1f, 1f, -1f, 1f,
        )
}
