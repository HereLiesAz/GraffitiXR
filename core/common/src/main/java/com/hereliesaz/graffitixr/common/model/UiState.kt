// FILE: core/common/src/main/java/com/hereliesaz/graffitixr/common/model/UiState.kt
package com.hereliesaz.graffitixr.common.model

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import java.nio.ByteBuffer

/** Live eval metrics snapshot rendered by the dev overlay (Sub-project A). */
data class EvalLiveMetrics(
    val errMm: Float = -1f,
    val errDeg: Float = -1f,
    val jitterMm: Float = 0f,
    val availability: Float = 0f,
    val recoveryMs: Long? = null,
    val stageMs: FloatArray = FloatArray(5),
    val batteryMa: Float = 0f,
    val wallCount: Int = 0, // live wall-fingerprint point count (reloc health / self-grow watch)
)

/** A tapped wall mark: normalized screen coords (0..1) plus the camera→point range in meters
 *  (-1 when depth was unavailable/out of range at that pixel). */
data class TapMark(val nx: Float, val ny: Float, val distanceMeters: Float)

data class ArUiState(
    val isScanning: Boolean = false,
    val splatCount: Int = 0,
    val isTargetDetected: Boolean = false,
    // True once a target fingerprint has been saved to the current project.
    // Controls whether artwork is rendered in AR space (via OverlayRenderer).
    val isAnchorEstablished: Boolean = false,
    // First-run onboarding signals. isArReady flips true the first frame ARCore reports TRACKING
    // (ARCore has finished initializing); planeDetected flips true the first time a tracking plane
    // is found. Both are latching (never reset within a session) and drive the onboarding overlay's
    // stage transitions (show movement guidance until a surface appears, etc.).
    val isArReady: Boolean = false,
    val planeDetected: Boolean = false,
    // First-run walkthrough only: latches true once the marks the user drew have been detected (or the
    // phase timed out), which is what ends the walkthrough and places the artwork.
    val doodleLocked: Boolean = false,
    // Wall stats from the doodle capture, used to auto-tune the artwork's adjustments on the swap.
    val doodleWallStats: com.hereliesaz.graffitixr.common.util.ImageStats? = null,
    val isFlashlightOn: Boolean = false,
    val lightLevel: Float = 1.0f,
    val tempCaptureBitmap: Bitmap? = null,
    // Grayscale + ORB keypoint overlay computed after capture so the artist can
    // judge whether the surface has enough visual texture before confirming.
    val annotatedCaptureBitmap: Bitmap? = null,
    val targetDepthBuffer: ByteBuffer? = null,
    val targetDepthWidth: Int = 0,
    val targetDepthHeight: Int = 0,
    val targetIntrinsics: FloatArray? = null,
    // The green (parallel, in-range) ARCore wall plane under the capture tap, as 6 floats
    // [pointX,pointY,pointZ, normalX,normalY,normalZ] in world space. Null when the tap wasn't on a
    // qualifying (MATCH/green) plane — single-capture target creation requires one (back-projection).
    val targetWallPlane: FloatArray? = null,
    val capturedTargetUris: List<Uri> = emptyList(),
    val capturedTargetImages: List<Bitmap> = emptyList(),
    val gpsData: GpsData? = null,
    val sensorData: SensorData? = null,
    val pendingKeyframePath: String? = null,
    val unwarpPoints: List<Offset> = emptyList(),
    // Real fingerprint feature positions (normalized 0..1 in the captured target image) shown on the
    // refinement screen so the user can see and erase exactly what will anchor the fingerprint.
    val targetKeypoints: List<Offset> = emptyList(),
    val isCaptureRequested: Boolean = false,
    val isAnchorEstablishmentRequested: Boolean = false,

    val undoCount: Int = 0,

    val gestureInProgress: Boolean = false,

    // Live diagnostic log lines for in-app debugging (newest entry replaces old)
    val diagLog: String? = null,

    // Contextual scan coaching hint. Non-null only during the scanning phase
    // (splatCount < 50000). Computed by ArViewModel based on what the user is
    // actually failing to do — low light, not moving, not pointing at surfaces.
    val scanHint: String? = null,

    // ── Anchor overlay data (populated when target is captured) ──────────────

    // Actual depth image dimensions (not color image dimensions).
    val targetDepthBufferWidth: Int = 0,
    val targetDepthBufferHeight: Int = 0,
    val targetDepthStride: Int = 0,

    // Column-major 4×4 view matrix captured at the moment the target was photographed.
    // Used to unproject depth pixels to 3D world positions for layer feature baking.
    val targetCaptureViewMatrix: FloatArray? = null,

    // Store the raw sensor-aligned bitmap for addLayerFeatures mapping
    val targetRawBitmap: Bitmap? = null,
    // Store the rotation applied to the display bitmap
    val targetDisplayRotation: Int = 0,
    /**
     * How and where the device was held when the active target was captured.
     *
     * Null until a capture happens this session. Persisted onto the project at save, because the
     * conditions a fingerprint was built under are not recoverable from the fingerprint.
     */
    val targetCaptureEnvironment: CaptureEnvironment? = null,
    /**
     * Set when a saved wall fingerprint was refused at load because it predates Phase 0 and its 3D
     * points are in the sensor frame with no recorded capture rotation (IMPLEMENTATION.md 0.7).
     *
     * Surfaced so the artist is told to re-capture rather than left wondering why the overlay never
     * locks onto a target the app appears to have loaded.
     */
    val legacyFingerprintRefused: Boolean = false,
    /**
     * Set when the artwork's footprint leaves too little bare wall around it for the reloc PnP to
     * localize against (`IMPLEMENTATION.md` 2.8, `PARAMETERS.md` §4 `MIN_BACKBONE`).
     *
     * Raised when the partition is computed — i.e. when the artwork is placed or resized — and not
     * at capture, because a capture is what *establishes* the anchor the artwork sits on and so has
     * no footprint to measure. Cleared the moment a resize brings the backbone back above the floor,
     * which is why it is recomputed on every partition rather than latched.
     */
    val backboneTooSmall: Boolean = false,

    // Physical half-extents of the overlay quad in meters (computed from depth center pixel).
    // OverlayRenderer sizes its textured quad to (halfW*2) × (halfH*2) meters.
    val targetPhysicalExtent: Pair<Float, Float>? = null,

    // Which 3-D mapping mode is active. Defaults to MURAL.
    /**
     * Whether the 360-degree ambient sweep is required before the wall scan begins. Default on.
     *
     * Replaces the Canvas/Mural `arScanMode`. Those modes named two mapping engines — splatting and
     * surface mesh — that were deleted, and natively the mode was inert (`mScanMode` was stored and
     * read nowhere). The only behaviour that still genuinely differed between them was this sweep,
     * so that is what the setting now says.
     */
    val ambientScanEnabled: Boolean = true,
    // The specific engine used when MURAL is active.

    // Phase 3 — True once the renderer has confirmed ARCore Depth API is available on this device.
    val isDepthApiSupported: Boolean = false,

    // Phase 4 — Tap-to-target: marks the user tapped on their painted reference, each with the
    // camera→point distance measured at that pixel. Rendered as a chip on the live camera view.
    val tapMarks: List<TapMark> = emptyList(),

    // Phase 5 — When true, OverlayRenderer draws an orange line-loop around the anchor quad boundary.
    val showAnchorBoundary: Boolean = false,
    /** ARCore camera target frame rate: 60 (default) or 30. Applies on next AR entry. */
    val cameraTargetFps: Int = 60,
    /**
     * Perception-throttle triggers. When enabled and active, each drops the world-locked perception
     * redraw rate from 60 to 30 fps to save power; camera + overlay + gestures stay full-rate.
     */
    val throttleOnThermal: Boolean = true,
    val throttleOnPowerSave: Boolean = true,
    val throttleOnLowBattery: Boolean = true,
    val throttleOnLag: Boolean = true,
    /** Derived: any enabled thermal/power-save/low-battery trigger is currently active. */
    val perceptionSystemThrottle: Boolean = false,
    /**
     * Master toggle for the adaptive AR frame-rate coach. When on (default), the renderer gates the
     * heavy native SLAM/VIO work while the projection is locked and the phone is held still, snapping
     * back to full rate instantly on motion — a still scene looks identical, so it's imperceptible.
     */
    val adaptiveRateEnabled: Boolean = true,
    /** Heavy-work cadence (fps) while idle. Tightened under battery/thermal pressure. */
    val idleRateCeilingFps: Int = 30,
    /** Heavy-work cadence cap (fps) while active; 0 = uncapped. Set >0 only under battery pressure. */
    val activeRateCeilingFps: Int = 0,
    /** Battery pressure tier: 0 = normal, 1 = medium (≤30%), 2 = low (≤15%). Drives degradation. */
    val batteryTier: Int = 0,

    // Teleological SLAM — fraction [0,1] of locked artwork guide features currently visible
    // on the wall.  0 until addLayerFeaturesToSLAM has been called (layers locked as guide).
    // Updated after every PnP relocalisation pass inside the native engine (~1–2 Hz).
    val paintingProgress: Float = 0f,

    // Guided scan phase: AMBIENT (rotate 360°) → WALL (scan the target) → COMPLETE.
    val scanPhase: ScanPhase = ScanPhase.AMBIENT,
    // How many 30° sectors (0..12) the user has swept during the AMBIENT phase.
    val ambientSectorsCovered: Int = 0,
    // 360° angular coverage progress [0,1].
    val worldMappingProgress: Float = 0f,

    // Bitmask of visited 10° sectors (bit N = sector N, 36 bits total, 0 = north/up).
    // Used to render the per-sector coverage ring in the scan coaching overlay.
    val visitedSectorsMask: Long = 0L,

    // Erase history — whether undo/redo are available during the REVIEW mark-removal step.
    val canUndoErase: Boolean = false,
    val canRedoErase: Boolean = false,

    // distance from camera to anchor in metres, or -1f when not in front of camera / not established.
    val distanceToAnchorMeters: Float = -1f,
    // Whether the user is right-handed (UI orientation)
    val isRightHanded: Boolean = true,
    // Whether to display distances in imperial units (feet) rather than metric.
    val isImperialUnits: Boolean = false,

    // True once ARCore has been confirmed installed and supported on this device.
    // False while unverified or when ARCore is missing / not supported.
    val isArCoreAvailable: Boolean = true,

    // False until ArAvailabilityChecker.check() returns a final (non-UNKNOWN)
    // result. UI gates that hide AR mode for unsupported devices must wait for
    // this to be true before reacting, otherwise AR mode would briefly hide on
    // every cold start before the check resolves.
    val isArCoreAvailabilityResolved: Boolean = false,

    // Mirrors the runtime camera permission state so AR overlays can react without
    // threading the raw permission flag all the way into every composable.
    val hasCameraPermission: Boolean = false,

    // Relative direction to the anchor in camera-local space (for offscreen indicators).
    // X > 0 is right, Y > 0 is up, Z < 0 is in front.
    val anchorRelativeDirection: Triple<Float, Float, Float>? = null,

    val coopRole: CoopRole = CoopRole.NONE,
    val coopSessionState: CoopSessionState = CoopSessionState.Idle,
    val showCoopNotFoundDialog: Boolean = false,

    // ── Enhanced Diagnostics ──────────────
    val isHardwareStereoActive: Boolean = false,
    val currentCenterDepth: Float = -1f,
    val fpsAr: Float = 0f,
    val rawSensorReadings: String? = null,

    // Flipped to true after ARCore has failed to acquire a TRACKING state for
    // ~10 s after AR mode entry. Drives the "AR can't initialize" escape
    // overlay, which gives the user a guaranteed exit even when the VIO/depth
    // pipelines are stuck and the main thread is starved.
    val trackingFailed: Boolean = false,

    /**
     * Sticky for the session once a guest edit has been dropped — the co-op protocol is
     * host-broadcast, so a guest's own edits never reach anyone. The one-shot toast
     * ([ArViewModel.observeDroppedGuestEdits]) explains this the first time it happens; this field
     * keeps a NOTICE badge on the co-op rail item after the toast is gone, since a toast is easy to
     * miss mid-gesture and the badge is the durable record that it happened this session.
     */
    val guestEditWasDropped: Boolean = false,

    val evalLiveMetrics: EvalLiveMetrics = EvalLiveMetrics(),

    // Live relocalization state: locked, or which gate the last attempt missed. Surfaced by the
    // Diagnostic Overlay in release as well as debug — every one of these failures is otherwise
    // silent, which is why "it never works" was so hard to pin down.
    val relocDiagnostics: RelocDiagnostics = RelocDiagnostics(),
    /**
     * `IMPLEMENTATION.md` **4.6** — the corroboration path's pixel-valued readings, carried apart
     * from [relocDiagnostics] only because that record is filled from an `int[]` channel.
     *
     * Both fields are -1 until measured. The search radius in particular is the number that tells an
     * artist's bug report apart from a tuning problem: a radius pinned at its ceiling means the
     * design's on-screen scale is being read wrongly, and one pinned at its floor means
     * corroboration is being starved — two opposite faults that look identical from the outside.
     */
    val corroborationDiagnostics: CorroborationDiagnostics = CorroborationDiagnostics(),
    /**
     * What pose fusion decided this frame — and, when it did not run, why.
     *
     * The other diagnostics report measurements; this reports a decision. Six quite different causes
     * produce the same artist-visible symptom (the overlay sits on the backbone and drifts), and
     * until this existed nothing on screen distinguished them. See [FusionDiagnostics].
     */
    val fusionDiagnostics: FusionDiagnostics = FusionDiagnostics(),
    /**
     * How many 3D points the live wall fingerprint holds — 0 when there is none.
     *
     * The direct answer to "did the target actually get built", read straight from the engine
     * rather than inferred from [relocDiagnostics], which is stale until the reloc thread has run at
     * least once and therefore cannot be trusted in the seconds right after a capture.
     *
     * Exists because "TARGET ESTABLISHED" was gated on [isAnchorEstablished] — an ARCore anchor,
     * which says nothing about the fingerprint. A capture whose back-projection produced no usable
     * points still lit the success banner, and the artist was told to go ahead and paint against an
     * overlay that could only ever drift. The failure was fully diagnosable in the engine and the UI
     * asserted the opposite.
     */
    val wallFingerprintPoints: Int = 0,
)

enum class CoopRole { NONE, HOST, GUEST }


enum class CaptureStep {
    NONE, CAPTURE, RECTIFY, MASK, REVIEW
}



enum class ScanPhase { AMBIENT, WALL, COMPLETE }

/**
 * Derived state for the teleological SLAM relocalization loop.
 * Computed in the UI from [ArUiState.isAnchorEstablished] + [ArUiState.paintingProgress].
 */
enum class RelocState {
    /** No fingerprint loaded — target not yet confirmed. */
    IDLE,
    /** Fingerprint active, PnP running, but no features matched yet. */
    SEARCHING,
    /** At least some artwork features are visible and matched. */
    TRACKING
}

enum class BlendMode {
    SrcOver, Multiply, Screen, Overlay, Darken, Lighten, ColorDodge, ColorBurn,
    HardLight, SoftLight, Difference, Exclusion, Hue, Saturation, Color, Luminosity,
    Clear, Src, Dst, DstOver, SrcIn, DstIn, SrcOut, DstOut, SrcAtop, DstAtop,
    Xor, Plus, Modulate
}
