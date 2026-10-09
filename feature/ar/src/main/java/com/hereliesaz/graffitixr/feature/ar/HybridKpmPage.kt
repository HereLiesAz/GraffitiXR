package com.hereliesaz.graffitixr.feature.ar

/**
 * Everything needed to re-arm the hybrid (ARCore-primary) KPM sidecar in a later session without
 * recapturing: the metric rectified page luma, its physical width, and the frozen
 * `page_from_artwork` relation.
 *
 * DPI and page geometry are deliberately NOT stored; they are recomputed from [width] and
 * [widthMeters] by the same `SphereSlamPoseMath` calls the capture used, so a library change in
 * that derivation cannot desynchronise a stored value from its source.
 *
 * Restoring needs no ARCore page anchor: the correction solve consumes only `page_from_artwork`
 * and the live artwork backbone, and the relation is rigid and ARCore-world-independent.
 */
class HybridKpmPage(
    val luma: ByteArray,
    val width: Int,
    val height: Int,
    val widthMeters: Float,
    val pageFromArtwork: FloatArray,
) {
    /** True when every field is usable; a page that fails this is never restored or persisted. */
    fun isValid(): Boolean =
        width > 0 && height > 0 &&
            luma.size == width * height &&
            widthMeters.isFinite() && widthMeters > 0f &&
            pageFromArtwork.size == 16 && pageFromArtwork.all { it.isFinite() } &&
            pageFromArtwork[15] == 1f

    companion object {
        /** Rebuild from persisted project fields; null when they are absent or inconsistent. */
        fun fromPersisted(
            luma: ByteArray?,
            width: Int,
            height: Int,
            widthMeters: Float,
            pageFromArtwork: List<Float>,
        ): HybridKpmPage? {
            if (luma == null || pageFromArtwork.size != 16) return null
            return HybridKpmPage(luma, width, height, widthMeters, pageFromArtwork.toFloatArray())
                .takeIf { it.isValid() }
        }
    }
}
