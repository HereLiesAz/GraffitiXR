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
        /**
         * The fingerprint key a page frozen during a capture may commit under, or null if it may not
         * commit yet (or ever). Commits only into the capture's own project, and only once that
         * project's fingerprint has moved off the value it held when the capture started — proof the
         * capture behind the page produced the fingerprint now on the project.
         */
        fun commitKeyOrNull(
            projectId: String,
            captureProjectId: String?,
            fingerprintKey: List<Float>?,
            keyAtCapture: List<Float>?,
        ): List<Float>? {
            if (keyAtCapture == null || projectId != captureProjectId) return null
            val key = fingerprintKey?.takeIf { it.size == 16 } ?: return null
            return key.takeIf { it != keyAtCapture }
        }

        /** True when a persisted page bound to [boundKey] belongs to the fingerprint [fingerprintKey]. */
        fun isBoundTo(fingerprintKey: List<Float>?, boundKey: List<Float>): Boolean =
            fingerprintKey != null && fingerprintKey.size == 16 && fingerprintKey == boundKey

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
