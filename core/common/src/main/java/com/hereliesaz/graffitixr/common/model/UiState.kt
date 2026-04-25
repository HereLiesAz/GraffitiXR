// FILE: core/common/src/main/java/com/hereliesaz/graffitixr/common/model/UiState.kt
package com.hereliesaz.graffitixr.common.model

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import java.nio.ByteBuffer

data class ArUiState(
    val isScanning: Boolean = false,
    val splatCount: Int = 0,
    val immutableSplatCount: Int = 0,
    val isTargetDetected: Boolean = false,
    // True once a target fingerprint has been saved to the current project.
    // Controls whether artwork is rendered in AR space (via OverlayRenderer).
    val isAnchorEstablished: Boolean = false,
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
    val capturedTargetUris: List<Uri> = emptyList(),
    val capturedTargetImages: List<Bitmap> = emptyList(),
    val gpsData: GpsData? = null,
    val sensorData: SensorData? = null,
    val pendingKeyframePath: String? = null,
    val unwarpPoints: List<Offset> = emptyList(),
    val activeUnwarpPointIndex: Int = -1,
    val magnifierPosition: Offset = Offset.Zero,
    val maskPath: androidx.compose.ui.graphics.Path? = null,
    val isCaptureRequested: Boolean = false,

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

    // Physical half-extents of the overlay quad in meters (computed from depth center pixel).
    // OverlayRenderer sizes its textured quad to (halfW*2) × (halfH*2) meters.
    val targetPhysicalExtent: Pair<Float, Float>? = null,

    // Which 3-D mapping mode is active. Defaults to MURAL.
    val arScanMode: ArScanMode = ArScanMode.MURAL,
    // The specific engine used when MURAL is active.
    val muralMethod: MuralMethod = MuralMethod.VOXEL_HASH,

    // Phase 3 — True once the renderer has confirmed ARCore Depth API is available on this device.
    val isDepthApiSupported: Boolean = false,

    // Phase 4 — Tap-to-target: list of normalized screen coords (0..1) where the user has tapped
    // their painted reference marks. Displayed as cyan circles on the live camera view.
    val tapHighlightKeypoints: List<Pair<Float, Float>> = emptyList(),

    // Phase 5 — When true, OverlayRenderer draws an orange line-loop around the anchor quad boundary.
    val showAnchorBoundary: Boolean = false,

    // Teleological SLAM — fraction [0,1] of locked artwork guide features currently visible
    // on the wall.  0 until addLayerFeaturesToSLAM has been called (layers locked as guide).
    // Updated after every PnP relocalisation pass inside the native engine (~1–2 Hz).
    val paintingProgress: Float = 0f,

    // Guided scan phase: AMBIENT (rotate 360°) → WALL (scan the target) → COMPLETE.
    val scanPhase: ScanPhase = ScanPhase.AMBIENT,
    // How many 30° sectors (0..12) the user has swept during the AMBIENT phase.
    val ambientSectorsCovered: Int = 0,

    // Erase history — whether undo/redo are available during the REVIEW mark-removal step.
    val canUndoErase: Boolean = false,
    val canRedoErase: Boolean = false,

    // Distance from camera to anchor in metres, or -1f when not in front of camera / not established.
    val distanceToAnchorMeters: Float = -1f,
    // Whether to display distances in imperial units (feet) rather than metric.
    val isImperialUnits: Boolean = false,

    // True once ARCore has been confirmed installed and supported on this device.
    // False while unverified or when ARCore is missing / not supported.
    val isArCoreAvailable: Boolean = true,

    // Mirrors the runtime camera permission state so AR overlays can react without
    // threading the raw permission flag all the way into every composable.
    val hasCameraPermission: Boolean = false,

    // Relative direction to the anchor in camera-local space (for offscreen indicators).
    // X > 0 is right, Y > 0 is up, Z < 0 is in front.
    val anchorRelativeDirection: Triple<Float, Float, Float>? = null,

    // Freeze preview — non-null while FreezePreviewScreen is shown
    val freezePreviewBitmap: Bitmap? = null,
    // True when target was captured without depth data; shown as banner in FreezePreviewScreen
    val freezeDepthWarning: Boolean = false,

    // Peer-to-Peer Sync — True when a local peer is found and coordinates are being aligned.
    val isSyncing: Boolean = false,
    val isCoopSearching: Boolean = false,
    val coopStatus: String? = null,
    val coopRole: CoopRole = CoopRole.NONE,
    val showCoopNotFoundDialog: Boolean = false,

    // ── Enhanced Diagnostics ──────────────
    val isDualLensActive: Boolean = false,
    val isHardwareStereoActive: Boolean = false,
    val currentCenterDepth: Float = -1f,
    val visibleSplatConfidenceAvg: Float = 0f,
    val globalSplatConfidenceAvg: Float = 0f,
    val fpsAr: Float = 0f,
    val rawSensorReadings: String? = null
)

enum class CoopRole { NONE, HOST, GUEST }

enum class Tool {
    NONE, BRUSH, ERASER, BLUR, HEAL, BURN, DODGE, LIQUIFY, COLOR
}

enum class CaptureStep {
    NONE, CAPTURE, RECTIFY, MASK, REVIEW
}

enum class ArScanMode {
    /** 
     * User-facing: "Canvas". Optimized for smaller desk-scale art.
     * Use ARCore's built-in feature-point cloud (reliable, no depth API required). 
     */
    CLOUD_POINTS,
    /**
     * User-facing: "Mural". The specific engine (Splatting or Surface Mesh) 
     * is determined by the MuralMethod setting.
     */
    MURAL
}

enum class MuralMethod {
    /** Gaussian Splatting (Mural v1) */
    VOXEL_HASH,
    /** Surface-Aware Mesh / t-SNE Unroller (Mural v2) */
    SURFACE_MESH
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
