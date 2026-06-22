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

    // Multi-layer support
    val layers: List<OverlayLayer> = emptyList(),

    // Neural Scan ID
    val cloudAnchorId: String? = null,

    // Path to the localized map file (.bin) if using native SLAM
    val mapPath: String? = null,
    // Path to the point cloud file (.bin) if using cloud-points scan mode
    val cloudPointsPath: String? = null,
    val targetFingerprint: String? = null,
    val isRightHanded: Boolean = true,
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

    // Persistent confidence-weighted feature map of the wall around the marks fingerprint — the
    // lean spatial backbone for wide-area relocalization (see docs/RELOC_MAP_DESIGN.md). Null on
    // projects without one; built passively during normal use. Defaulted for back-compat.
    val wallFeatureMap: WallFeatureMap? = null
)
