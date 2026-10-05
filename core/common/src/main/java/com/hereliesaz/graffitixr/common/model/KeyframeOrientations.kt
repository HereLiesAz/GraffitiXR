package com.hereliesaz.graffitixr.common.model

import kotlinx.serialization.Serializable

/**
 * Per-keyframe device orientation samples recorded during standalone (SphereSLAM) tracking — the
 * **angular glue** for the planned spherical-coverage map (see `docs/SPHERESLAM_SPHERE_MAP.md`).
 *
 * The standalone path has no VIO baseline to place surrounding features in 3D; a recorded bearing
 * per keyframe (from `GyroOrientationBridge`'s game-rotation quaternion) supplies the "which way the
 * camera was pointing" half, with monocular depth later supplying the radius. Today
 * `GyroOrientationBridge` throws its orientation away after each short dropout bridge; this type is
 * where it is kept instead.
 *
 * **Phase 1 (this type): storage only.** Nothing reads these samples for relocalization yet — they
 * are recorded and persisted so the round trip is proven before any reloc behavior depends on them.
 * The field is defaulted/nullable so older projects (no orientation log) deserialize unchanged.
 *
 * Stored as **parallel primitive arrays** (matching [WallFeatureMap]): [timestampsNs] holds one
 * `elapsedRealtimeNanos`-domain timestamp per keyframe, and [quaternions] holds the matching
 * normalized `[x, y, z, w]` rotation-vector quaternion flattened four floats per keyframe, so
 * `quaternions.size == timestampsNs.size * 4`.
 */
@Serializable
data class KeyframeOrientations(
    val timestampsNs: LongArray = LongArray(0),
    val quaternions: FloatArray = FloatArray(0),
) {
    /** Number of recorded keyframe orientations. */
    val count: Int get() = timestampsNs.size

    init {
        require(quaternions.size.toLong() == timestampsNs.size.toLong() * 4L) {
            "quaternions (${quaternions.size}) must be 4 * timestampsNs (${timestampsNs.size})"
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as KeyframeOrientations
        // contentEquals so a round-trip comparison is by value, not array reference.
        if (!timestampsNs.contentEquals(other.timestampsNs)) return false
        if (!quaternions.contentEquals(other.quaternions)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = timestampsNs.contentHashCode()
        result = 31 * result + quaternions.contentHashCode()
        return result
    }
}
