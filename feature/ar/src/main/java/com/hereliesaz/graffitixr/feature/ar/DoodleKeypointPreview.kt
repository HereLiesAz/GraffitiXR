package com.hereliesaz.graffitixr.feature.ar

/**
 * The target review's keypoint overlay: the features the detector (SuperPoint, else ORB) finds in the
 * capture photo, split by the marks mask into the ones the doodle keeps and the ones it drops. The
 * highlight shows what the artist's marks are; this shows what the tracker will actually have to match,
 * and what the mask costs it.
 *
 * The rule is the builders' own (`StandaloneFingerprintBuilder.filterByMask`, `MetricDoodleBuilder.applyMask`):
 * a feature is kept when the mask's alpha at its pixel, the position truncated into the mask, is non-zero.
 */
internal object DoodleKeypointPreview {

    /** [kept] and [dropped] as flat `[u0, v0, u1, v1, …]` in the photo's pixels. */
    class Split(val kept: FloatArray, val dropped: FloatArray) {
        val keptCount: Int get() = kept.size / 2
        val droppedCount: Int get() = dropped.size / 2
    }

    /**
     * [positions] (`[u0, v0, …]` in a [photoW] × [photoH] photo) split by [selected], which says whether
     * the mask ([maskW] × [maskH], possibly another size than the photo) is set at a mask pixel.
     * Positions that are not finite are left out of both.
     */
    fun split(
        positions: FloatArray,
        photoW: Int,
        photoH: Int,
        maskW: Int,
        maskH: Int,
        selected: (x: Int, y: Int) -> Boolean,
    ): Split {
        if (photoW <= 0 || photoH <= 0 || maskW <= 0 || maskH <= 0) return Split(FloatArray(0), FloatArray(0))
        val kept = ArrayList<Float>()
        val dropped = ArrayList<Float>()
        val sx = maskW.toFloat() / photoW
        val sy = maskH.toFloat() / photoH
        var k = 0
        while (k + 1 < positions.size) {
            val u = positions[k]
            val v = positions[k + 1]
            k += 2
            if (!u.isFinite() || !v.isFinite()) continue
            val mx = (u * sx).toInt().coerceIn(0, maskW - 1)
            val my = (v * sy).toInt().coerceIn(0, maskH - 1)
            val into = if (selected(mx, my)) kept else dropped
            into += u
            into += v
        }
        return Split(kept.toFloatArray(), dropped.toFloatArray())
    }
}
