package com.hereliesaz.graffitixr.common.model

import android.net.Uri
import android.os.Parcelable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import com.hereliesaz.graffitixr.common.serialization.BlendModeSerializer
import com.hereliesaz.graffitixr.common.serialization.OffsetSerializer
import com.hereliesaz.graffitixr.common.serialization.UriSerializer
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Persisted frame contract for [GraffitiProject.sphereSlamFingerprint].
 *
 * v1 = centered rectified KPM page: +X image-right, +Y image-up, +Z wall normal, with the exact
 * artoolkitX half-pixel page mapping. Bump this BEFORE changing point semantics.
 */
const val SPHERE_SLAM_FINGERPRINT_FRAME_VERSION: Int = 1

/**
 * One additional standalone KPM page registered into the canonical centered-wall frame.
 *
 * Page 0 remains [GraffitiProject.sphereSlamReferenceUri] for backwards compatibility. Grown pages
 * use stable positive IDs and store their rigid canonical-from-page transform explicitly, so a
 * match can always be rebased to page 0's immutable wall coordinates.
 */
@Serializable
data class SphereSlamAtlasPage(
    val pageNo: Int,
    @Serializable(with = UriSerializer::class)
    val referenceUri: Uri,
    val referenceWidthMeters: Float,
    val physicallyMetric: Boolean,
    val canonicalFromPage: List<Float>,
    val frameVersion: Int = SPHERE_SLAM_FINGERPRINT_FRAME_VERSION,
) {
    init {
        require(pageNo > 0) { "grown SphereSLAM atlas pages use positive page IDs" }
        require(referenceWidthMeters.isFinite() && referenceWidthMeters > 0f)
        require(canonicalFromPage.size == 16 && canonicalFromPage.all { it.isFinite() }) {
            "canonicalFromPage must be a finite 4x4 transform"
        }
    }
}


/**
 * Data class representing GPS coordinates and accuracy.
 */
@Serializable
@Parcelize
data class GpsData(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val accuracy: Float,
    val time: Long
) : Parcelable

/**
 * Data class representing device orientation sensor readings.
 */
@Serializable
@Parcelize
data class SensorData(
    val azimuth: Float,
    val pitch: Float,
    val roll: Float
) : Parcelable

/**
 * A snapshot of the device state during a calibration event.
 */
@Serializable
@Parcelize
data class CalibrationSnapshot(
    val gpsData: GpsData?,
    val sensorData: SensorData?,
    val poseMatrix: List<Float>?,
    val timestamp: Long
) : Parcelable

/**
 * Grouping of legacy editor fields to reduce top-level field count in GraffitiProject.
 */
@Serializable
data class LegacyVisuals(
    val opacity: Float = 1f,
    val brightness: Float = 0f,
    val contrast: Float = 1f,
    val saturation: Float = 1f,
    val colorBalanceR: Float = 1f,
    val colorBalanceG: Float = 1f,
    val colorBalanceB: Float = 1f,
    val scale: Float = 1f,
    val rotationX: Float = 0f,
    val rotationY: Float = 0f,
    val rotationZ: Float = 0f,

    @Serializable(with = OffsetSerializer::class)
    val offset: Offset = Offset.Zero,

    @Serializable(with = BlendModeSerializer::class)
    val blendMode: BlendMode = BlendMode.SrcOver
)

/**
 * The primary data model representing a user's graffiti project.
 */
@Serializable
data class GraffitiProject(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Untitled",
    val created: Long = System.currentTimeMillis(),
    val lastModified: Long = System.currentTimeMillis(),

    @Serializable(with = UriSerializer::class)
    val backgroundImageUri: Uri? = null,

    @Serializable(with = UriSerializer::class)
    val overlayImageUri: Uri? = null,

    @Serializable(with = UriSerializer::class)
    val originalOverlayImageUri: Uri? = null,

    @Serializable(with = UriSerializer::class)
    val thumbnailUri: Uri? = null,

    val targetImageUris: List<@Serializable(with = UriSerializer::class) Uri> = emptyList(),

    /**
     * Rectified wall reference used by the ARCore-independent SphereSLAM/KPM tracker.
     *
     * Kept separate from [targetImageUris]: this is the canonical page that must be restored on a
     * later visit, not capture-history UI. The width may be a real measurement or a normalized
     * renderer unit; [sphereSlamReferencePhysicallyMetric] tells consumers which.
     */
    @Serializable(with = UriSerializer::class)
    val sphereSlamReferenceUri: Uri? = null,
    val sphereSlamReferenceWidthMeters: Float = 1f,
    val sphereSlamReferencePhysicallyMetric: Boolean = false,

    /**
     * Backend-neutral standalone wall anchor identity.
     *
     * The standalone "anchor" is the canonical centered SphereSLAM page frame itself, not an
     * ARCore Anchor object. [sphereSlamAnchorGeneration] increments whenever canonical page 0 is
     * replaced or invalidated, so any cached pose/placement tied to an older wall can be rejected
     * deterministically. [sphereSlamAnchorFrameVersion] declares the coordinate semantics.
     */
    val sphereSlamAnchorGeneration: Long = 0L,
    val sphereSlamAnchorFrameVersion: Int = SPHERE_SLAM_FINGERPRINT_FRAME_VERSION,
    /**
     * Anchor generation the persisted AR-mode design adjustment was authored against.
     * A mismatch means the adjustment belongs to an older standalone wall frame and must not render.
     */
    val sphereSlamPlacementAnchorGeneration: Long = 0L,
    /**
     * Standalone AR's whole-design adjustment. Kept separate from modeAdjustments[AR] because an
     * ARCore anchor frame and a centered SphereSLAM page frame are not interchangeable until the
     * explicit cross-backend calibration work exists.
     *
     * Null is the migration value for projects created before the split; standalone initially copies
     * the legacy AR adjustment, then persists its own value on the first edit/save.
     */
    val sphereSlamModeAdjustment: ModeAdjustment? = null,

    /**
     * MobileGS reloc seed built from the SAME rectified page as [sphereSlamReferenceUri].
     *
     * Kept separate from [fingerprint]: the legacy/ARCore fingerprint stores object points in a
     * capture-camera CV frame, while this seed stores points in the centered durable SphereSLAM wall
     * frame. Mixing those two fields would make an ARCore loader interpret valid page points using
     * the wrong frame semantics.
     */
    val sphereSlamFingerprint: Fingerprint? = null,
    val sphereSlamFingerprintFrameVersion: Int = SPHERE_SLAM_FINGERPRINT_FRAME_VERSION,

    /**
     * Column-major GL 4x4 `map_from_fingerprint` transform for standalone SphereSLAM.
     *
     * The photosphere/map is the base tracking frame and exists before the fingerprint. The later
     * fingerprint is therefore NOT the map origin; this transform places its local page coordinates
     * into the already-running SphereSLAM map. Empty means legacy/unknown and must fail closed rather
     * than being silently treated as identity.
     */
    val sphereSlamMapFromFingerprint: List<Float> = emptyList(),

    /**
     * Wide-area MobileGS feature map for the standalone SphereSLAM backend.
     *
     * Kept separate from [wallFeatureMap], whose points are in the legacy ARCore fingerprint frame.
     * These points are in the exact same centred KPM-page frame as [sphereSlamFingerprint]. A new
     * standalone reference therefore invalidates both together.
     */
    val sphereSlamWallFeatureMap: WallFeatureMap? = null,
    val sphereSlamWallFeatureMapFrameVersion: Int = SPHERE_SLAM_FINGERPRINT_FRAME_VERSION,

    /**
     * Per-keyframe device orientation samples recorded during standalone tracking — the angular glue
     * for the planned spherical-coverage map (`docs/SPHERESLAM_SPHERE_MAP.md`, Phase 1). Storage only:
     * nothing consumes it for relocalization yet. Null for every project that predates the feature.
     */
    val sphereSlamKeyframeOrientations: KeyframeOrientations? = null,

    /**
     * Additional KPM pages beyond canonical page 0. Every transform targets page 0's exact centered
     * wall frame; changing page 0 invalidates this entire list.
     */
    val sphereSlamAtlasPages: List<SphereSlamAtlasPage> = emptyList(),

    val refinementPaths: List<RefinementPath> = emptyList(),

    // Legacy visual state grouped to fix binary compatibility issues with large data classes.
    val legacyVisuals: LegacyVisuals = LegacyVisuals(),

    val fingerprint: Fingerprint? = null,

    // Teleological Fingerprinting
    val targetFingerprintPath: String? = null,

    val drawingPaths: List<List<Pair<Float, Float>>> = emptyList(),

    val progressPercentage: Float = 0f,
    val evolutionImageUris: List<@Serializable(with = UriSerializer::class) Uri> = emptyList(),

    val gpsData: GpsData? = null,
    val sensorData: SensorData? = null,
    val calibrationSnapshots: List<CalibrationSnapshot> = emptyList(),

    /** The single overlay image this project places. Null before one has been chosen. */
    val design: OverlayLayer? = null,

    /**
     * Read-only migration path for projects saved when this app still held a layer LIST.
     *
     * Multilayer editing moved to the companion design app; a project now carries one image. Old
     * `.gxr` files still have the array, so it is kept for reading and collapsed to [design] on
     * load (see ProjectManager.migrateInMemory). Nothing writes it any more, so it disappears from
     * a file the first time that project is saved.
     */
    val layers: List<OverlayLayer> = emptyList(),

    // Neural Scan ID
    val cloudAnchorId: String? = null,

    val targetFingerprint: String? = null,
    // Per-mode whole-design adjustments, keyed by EditorMode.name. Defaulted for back-compat with
    // projects saved before this field existed.
    val modeAdjustments: Map<String, ModeAdjustment> = emptyMap(),

    // Camera intrinsics (fx,fy,cx,cy) and the anchor pose (column-major 4x4, 16 floats) captured
    // alongside a metric fingerprint. Persisted so relocalization on reload replays the TRUE capture
    // intrinsics + anchor through SlamManager.restoreWallFingerprintMetric instead of falling back to
    // a default guess. Kept here rather than on Fingerprint because Fingerprint is constructed in
    // native JNI with a fixed constructor signature (changing it would break depth-path capture).
    // Both empty on depth-path or pre-existing projects, which keep the legacy descriptors-only restore.
    val fingerprintIntrinsics: List<Float> = emptyList(),
    val fingerprintAnchor: List<Float> = emptyList(),

    // The camera view (column-major 4x4, GL convention world→camera) at fingerprint capture. This is
    // what lets the reloc thread pre-cancel oblique-vs-frontal distortion: the marks lie on a known
    // plane, so with the capture view and the live view it can warp the live frame into the
    // fingerprint's frontal frame, match there, and map the correspondences back.
    // Without it MobileGS::computeRectifyHomography returns false and that whole pass is skipped —
    // which is what happened to every metric fingerprint, since only the (dead, depth-API) capture
    // path ever set it. An artist working a wall at arm's length is oblique most of the time, so this
    // is exactly the case it exists for. Empty on projects saved before this field.
    val fingerprintViewMatrix: List<Float> = emptyList(),

    // rotationNeeded at fingerprint capture (0/90/180/270), or -1 when unknown — IMPLEMENTATION.md
    // 0.11. Persisted alongside the view matrix because the two only mean anything together: the
    // stored points, the stored view and the intrinsics are all in the DISPLAY frame as of Phase 0,
    // and this records which display frame that was.
    //
    // Without it, a project captured AFTER Phase 0 — a correct one — is indistinguishable on disk
    // from one captured before, so the legacy check in 0.7 would reject both. -1 therefore has to
    // mean "genuinely unknown" and not "not written yet", which is why it is defaulted rather than
    // inferred at load time.
    val fingerprintCaptureRotationDeg: Int = -1,

    // The AR design quad's half-width in metres, fixed at its first placement in this project. The
    // first placement fits the quad to the screen at the anchor's distance; persisting the result
    // keeps the mural the same real-world size on every later visit instead of re-fitting to wherever
    // the artist happens to stand. Height follows the design's aspect. -1 = never placed.
    val arDesignHalfWidthM: Float = -1f,

    /**
     * Real wall width in metres, measured by the artist with AR Measure (two taps on the plane the
     * design is drawn on). Null = never measured. The input every later scale-dependent feature
     * (grid, sections, paintable area — BACKLOG Phase 6) reads; nothing derives it automatically.
     */
    val wallWidthMeters: Float? = null,

    // How and where the device was held when this project's target was captured — attitude,
    // ARCore's three poses, the frame rotations, and a location fix. Null on projects saved before
    // the app collected any of it. See CaptureEnvironment for why each group is independently
    // optional.
    val captureEnvironment: CaptureEnvironment? = null,

    // Persistent confidence-weighted feature map of the wall around the marks fingerprint — the
    // lean spatial backbone for wide-area relocalization (see docs/RELOC_MAP_DESIGN.md). Null on
    // projects without one; built passively during normal use. Defaulted for back-compat.
    val wallFeatureMap: WallFeatureMap? = null,
    // Teleological reference set: design features the wall has confirmed as painted, in the
    // fingerprint frame (points + descriptors only). Relocalization matches these alongside the
    // original marks, so a return visit locks onto the paint once the marks are painted over.
    // Valid only with the fingerprint it was built against; a new target capture clears it.
    val paintMarks: WallFeatureMap? = null,
    // Area-progress state (painted grid cells + learned paint colours), Base64 of the native blob.
    // Tied to the fingerprint like paintMarks: a new target capture clears it.
    val paintGrid: String? = null,

    // Per-host AzNavRail expansion state (host id -> expanded), so the rail restores exactly as the
    // user left it on reopen. Defaulted for back-compat with projects saved before this field
    // existed. Populated live via onRailHostExpansionChanged (EditorViewModel, wired from
    // MainActivity) whenever the rail's expansion state changes.
    val railExpansion: Map<String, Boolean> = emptyMap()
)
