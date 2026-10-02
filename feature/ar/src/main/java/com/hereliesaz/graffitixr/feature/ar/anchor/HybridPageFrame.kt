package com.hereliesaz.graffitixr.feature.ar.anchor

/**
 * Rigid frame conversions at the ARCore ↔ metric-KPM seam.
 *
 * No ARCore types live here; the renderer supplies matrices sampled from ARCore and this object
 * performs only explicit frame composition.
 */
object HybridPageFrame {
    /** ARCore world-from-page at capture from sensor camera view and metric KPM camera-from-page. */
    fun worldFromPage(
        sensorView: FloatArray,
        cameraFromPage: FloatArray,
    ): FloatArray =
        PoseMath.multiply(PoseMath.rigidInverse(sensorView), cameraFromPage)

    /**
     * Immutable centered-page-from-artwork relation. Under any ARCore global rebase G:
     *
     * inv(G·worldFromPage) · (G·worldFromArtwork) == inv(worldFromPage) · worldFromArtwork.
     */
    fun pageFromArtwork(
        worldFromPage: FloatArray,
        worldFromArtwork: FloatArray,
    ): FloatArray =
        PoseMath.multiply(PoseMath.rigidInverse(worldFromPage), worldFromArtwork)
}
