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
 * [descriptorsType], so either descriptor kind round-trips without a schema change.
 *
 * Stored as **primitive arrays** (not boxed `List`s): at the cap this is tens of thousands of
 * floats, so boxing would cost real memory, and the native restore path (Phase 2) hands these
 * straight to JNI without a copy. Every field is defaulted so older projects (no map)
 * deserialize unchanged.
 */
@Serializable
data class WallFeatureMap(
    // Flattened 3D points [x0,y0,z0, x1,y1,z1, ...] in the fingerprint-anchor frame; one triplet per point.
    val points3d: FloatArray = FloatArray(0),
    // Descriptors as a flat row-major byte blob: [descriptorsRows] rows (one per point), each
    // [descriptorsCols] wide, of OpenCV [descriptorsType] (CV_8U for ORB, CV_32F for SuperPoint).
    val descriptorsData: ByteArray = ByteArray(0),
    val descriptorsRows: Int = 0,
    val descriptorsCols: Int = 0,
    val descriptorsType: Int = 0,
    // Per-point confidence in [0,1] and observation count, parallel to the points (size == rows, or empty).
    val confidence: FloatArray = FloatArray(0),
    val obsCount: IntArray = IntArray(0),
    // Co-registration: the anchor pose (column-major 4x4, 16 floats) and intrinsics (fx,fy,cx,cy)
    // the points were built in — the SAME frame as the marks fingerprint's anchor. Empty => unset.
    val anchor: FloatArray = FloatArray(0),
    val intrinsics: FloatArray = FloatArray(0),
) {
    /** Number of mapped points (== descriptor rows). */
    val pointCount: Int get() = descriptorsRows

    init {
        // Defensive: the parallel arrays must agree so the native restore (Phase 2) can trust
        // them and never index out of bounds. Empty optional arrays are allowed.
        require(descriptorsRows >= 0 && descriptorsCols >= 0) { "descriptor dims must be non-negative" }
        require(points3d.size == descriptorsRows * 3) {
            "points3d (${points3d.size}) must be 3 * descriptorsRows ($descriptorsRows)"
        }
        require(descriptorsRows == 0 || descriptorsData.size % descriptorsRows == 0) {
            "descriptorsData (${descriptorsData.size}) must divide evenly into $descriptorsRows rows"
        }
        require(confidence.isEmpty() || confidence.size == descriptorsRows) {
            "confidence (${confidence.size}) must be empty or == descriptorsRows ($descriptorsRows)"
        }
        require(obsCount.isEmpty() || obsCount.size == descriptorsRows) {
            "obsCount (${obsCount.size}) must be empty or == descriptorsRows ($descriptorsRows)"
        }
        require(anchor.isEmpty() || anchor.size == 16) { "anchor must be empty or 16 floats" }
        require(intrinsics.isEmpty() || intrinsics.size == 4) { "intrinsics must be empty or 4 floats" }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as WallFeatureMap
        // contentEquals for every array; a generated equals() would compare them by reference
        // and break round-trip checks.
        if (!points3d.contentEquals(other.points3d)) return false
        if (!descriptorsData.contentEquals(other.descriptorsData)) return false
        if (descriptorsRows != other.descriptorsRows) return false
        if (descriptorsCols != other.descriptorsCols) return false
        if (descriptorsType != other.descriptorsType) return false
        if (!confidence.contentEquals(other.confidence)) return false
        if (!obsCount.contentEquals(other.obsCount)) return false
        if (!anchor.contentEquals(other.anchor)) return false
        if (!intrinsics.contentEquals(other.intrinsics)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = points3d.contentHashCode()
        result = 31 * result + descriptorsData.contentHashCode()
        result = 31 * result + descriptorsRows
        result = 31 * result + descriptorsCols
        result = 31 * result + descriptorsType
        result = 31 * result + confidence.contentHashCode()
        result = 31 * result + obsCount.contentHashCode()
        result = 31 * result + anchor.contentHashCode()
        result = 31 * result + intrinsics.contentHashCode()
        return result
    }
}
