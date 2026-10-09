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
    /**
     * True when every field is usable; a page that fails this is never restored or persisted.
     * Dimensions are bounded BEFORE the area is formed: from an imported/peer manifest,
     * 65537 x 65537 wraps an Int product to 131073 and would otherwise "match" a tiny buffer.
     */
    fun isValid(): Boolean =
        width in 1..MAX_DIMENSION_PX && height in 1..MAX_DIMENSION_PX &&
            luma.size.toLong() == width.toLong() * height.toLong() &&
            widthMeters.isFinite() && widthMeters > 0f &&
            pageFromArtwork.size == 16 && pageFromArtwork.all { it.isFinite() } &&
            pageFromArtwork[15] == 1f

    companion object {
        /** Generous ceiling over the capture's 1024-px rectification cap. */
        const val MAX_DIMENSION_PX = 4096

        /**
         * The fingerprint key a page frozen during a capture may commit under, or null if it may not
         * commit yet (or ever). Commits only into the capture's own project, and only once that
         * project's fingerprint differs from the value it held when the capture started — whether
         * that change landed before or after the page froze (the fingerprint build and the page
         * anchor's first TRACKING frame race).
         *
         * Residual, accepted: if a new capture is started while an EARLIER capture's fingerprint
         * build is still running, that earlier fingerprint landing can bind this page. When this
         * capture's own fingerprint then lands, the key mismatch makes the page inert (not wrong);
         * it is wrong only if this capture's build also fails.
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
