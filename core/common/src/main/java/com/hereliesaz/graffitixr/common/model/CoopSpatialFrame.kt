package com.hereliesaz.graffitixr.common.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Wire/project-independent version of the co-op wall-frame contract. */
const val COOP_SPATIAL_FRAME_SCHEMA_VERSION: Int = 1

/**
 * Pose backend that owns the host wall frame advertised to a co-op guest.
 *
 * This is descriptive metadata, not permission to reinterpret one backend's world frame as the
 * other. Every co-op pose crosses through [CoopSpatialFrame.doodleFromWall].
 */
@Serializable
enum class CoopTrackingBackend {
    ARCORE,
    SPHERESLAM,
}

/**
 * Unit semantics of the host wall frame.
 *
 * [METRIC] means translation is in metres. [NORMALIZED_PAGE] means the standalone page width is an
 * arbitrary renderer unit; that frame can be shared with another SphereSLAM client that consumes
 * the same page, but must never be injected into an ARCore metre world.
 */
@Serializable
enum class CoopSpatialScale {
    METRIC,
    NORMALIZED_PAGE,
}

/**
 * Explicit bridge from a host's durable wall-local coordinates to the doodle object points
 * shipped over co-op.
 *
 * The transform is `doodle_from_wall`:
 * - ARCore host: `Doodle.captureAnchorCam = V_cv(capture) * anchorModel`;
 * - standalone host: identity, because the SphereSLAM doodle already stores points directly in
 *   the canonical centered KPM wall frame.
 *
 * A peer PnP solve therefore becomes one backend-neutral wall view:
 *
 * `cameraGL_from_wall = CV_TO_GL * pnpCV_camera_from_doodle * doodleFromWall`.
 */
@Serializable
data class CoopSpatialFrame(
    val schemaVersion: Int = COOP_SPATIAL_FRAME_SCHEMA_VERSION,
    val hostBackend: CoopTrackingBackend,
    @SerialName("fingerprintFrameVersion")
    val doodleFrameVersion: Int,
    val scale: CoopSpatialScale,
    /**
     * Whether the bulk snapshot carries a PnP doodle in this frame. Standalone peers can share
     * one KPM page without it; every cross-backend pairing needs it as the geometric bridge.
     */
    @SerialName("fingerprintAvailable")
    val doodleAvailable: Boolean,
    /** Stable for the host wall target; changes when the target/doodle is replaced. */
    val anchorRevision: Long,
    /** One useful wall-width scale in this frame. Metres for METRIC, page units otherwise. */
    val referenceWidthUnits: Float,
    /** Column-major rigid 4x4 transform: doodle object frame from host wall-local frame. */
    @SerialName("fingerprintFromWall")
    val doodleFromWall: List<Float>,
) {
    init {
        require(schemaVersion == COOP_SPATIAL_FRAME_SCHEMA_VERSION) {
            "unsupported co-op spatial frame schema: $schemaVersion"
        }
        require(doodleFrameVersion > 0) { "doodle frame version must be positive" }
        require(referenceWidthUnits.isFinite() && referenceWidthUnits > 0f) {
            "referenceWidthUnits must be finite and positive"
        }
        require(doodleFromWall.size == 16 && doodleFromWall.all { it.isFinite() }) {
            "doodleFromWall must be a finite column-major 4x4"
        }
        require(kotlin.math.abs(doodleFromWall[3]) < 1e-4f)
        require(kotlin.math.abs(doodleFromWall[7]) < 1e-4f)
        require(kotlin.math.abs(doodleFromWall[11]) < 1e-4f)
        require(kotlin.math.abs(doodleFromWall[15] - 1f) < 1e-4f)
    }

    /**
     * A normalized standalone page is meaningful to another SphereSLAM client because both can
     * consume the same shared page. ARCore's world translation is metres, so accepting it there
     * would silently relabel arbitrary page units as metres.
     */
    fun supportsGuest(guestBackend: CoopTrackingBackend): Boolean {
        if (guestBackend != hostBackend && !doodleAvailable) return false
        if (hostBackend == CoopTrackingBackend.ARCORE && !doodleAvailable) return false
        return scale == CoopSpatialScale.METRIC ||
            (hostBackend == CoopTrackingBackend.SPHERESLAM &&
                guestBackend == CoopTrackingBackend.SPHERESLAM)
    }
}
