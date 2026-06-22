package com.hereliesaz.graffitixr.common.model

import kotlinx.serialization.Serializable

/**
 * A persistent, confidence-weighted **feature** map of the wall surrounding the marks
 * [Fingerprint] — the lean spatial backbone for wide-area relocalization (see
 * `docs/RELOC_MAP_DESIGN.md`). Because the overlaid artwork is usually larger than the marks,
 * the fingerprint alone can't hold the lock when you look away from it; this map carries
 * feature descriptors at 3D points across the whole wall so reloc works from far more
 * viewpoints, all co-registered to the fingerprint anchor (matching any patch yields the
 * fingerprint/overlay pose directly).
 *
 * It is built **passively** from whatever keyframes normal use provides (no explicit scan),
 * and bounded by the "lean" budget: ORB descriptors by default (`CV_8U`, 32 B/point;
 * SuperPoint `CV_32F` is an opt-in upgrade), a confidence-pruned cap, and frustum-gated
 * matching at reloc time. Descriptors are a flat row-major blob tagged with their OpenCV
 * [descriptorsType], so either descriptor kind round-trips without a schema change. Every
 * field is defaulted so older projects (no map) deserialize unchanged.
 */
@Serializable
data class WallFeatureMap(
    // Flattened 3D points [x0,y0,z0, x1,y1,z1, ...] in the fingerprint-anchor frame; one triplet per point.
    val points3d: List<Float> = emptyList(),
    // Descriptors as a flat row-major byte blob: [descriptorsRows] rows (one per point), each
    // [descriptorsCols] wide, of OpenCV [descriptorsType] (CV_8U for ORB, CV_32F for SuperPoint).
    val descriptorsData: ByteArray = ByteArray(0),
    val descriptorsRows: Int = 0,
    val descriptorsCols: Int = 0,
    val descriptorsType: Int = 0,
    // Per-point confidence in [0,1] and observation count, parallel to the points (size == rows).
    val confidence: List<Float> = emptyList(),
    val obsCount: List<Int> = emptyList(),
    // Co-registration: the anchor pose (column-major 4x4, 16 floats) and intrinsics (fx,fy,cx,cy)
    // the points were built in — the SAME frame as the marks fingerprint's anchor. Empty => unset.
    val anchor: List<Float> = emptyList(),
    val intrinsics: List<Float> = emptyList(),
) {
    /** Number of mapped points (== descriptor rows). */
    val pointCount: Int get() = descriptorsRows

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as WallFeatureMap
        // contentEquals for the descriptor blob; structural equality for the rest. A plain
        // generated equals() would compare the ByteArray by reference and break round-trip checks.
        if (points3d != other.points3d) return false
        if (!descriptorsData.contentEquals(other.descriptorsData)) return false
        if (descriptorsRows != other.descriptorsRows) return false
        if (descriptorsCols != other.descriptorsCols) return false
        if (descriptorsType != other.descriptorsType) return false
        if (confidence != other.confidence) return false
        if (obsCount != other.obsCount) return false
        if (anchor != other.anchor) return false
        if (intrinsics != other.intrinsics) return false
        return true
    }

    override fun hashCode(): Int {
        var result = points3d.hashCode()
        result = 31 * result + descriptorsData.contentHashCode()
        result = 31 * result + descriptorsRows
        result = 31 * result + descriptorsCols
        result = 31 * result + descriptorsType
        result = 31 * result + confidence.hashCode()
        result = 31 * result + obsCount.hashCode()
        result = 31 * result + anchor.hashCode()
        result = 31 * result + intrinsics.hashCode()
        return result
    }
}
