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
 *
 * @property latitude The latitude in degrees.
 * @property longitude The longitude in degrees.
 * @property altitude The altitude in meters above WGS84 ellipsoid.
 * @property accuracy The estimated horizontal accuracy radius in meters.
 * @property time The timestamp of the fix in milliseconds since epoch.
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
 *
 * @property azimuth Rotation around the -Z axis (0=North).
 * @property pitch Rotation around the X axis.
 * @property roll Rotation around the Y axis.
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
 *
 * @property gpsData The GPS location at the time of capture.
 * @property sensorData The orientation sensor readings.
 * @property poseMatrix The 4x4 model matrix of the device in AR world space (column-major).
 * @property timestamp The time of capture.
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
 * The primary data model representing a user's graffiti project.
 * This class persists all state related to the artwork, including image references,
 * editor adjustments, layer composition, and SLAM mapping data.
 *
 * @property id Unique identifier for the project.
 * @property name User-defined name of the project.
 * @property created Timestamp when the project was created.
 * @property lastModified Timestamp when the project was last saved.
 * @property backgroundImageUri URI to the background image (for Mockup Mode).
 * @property overlayImageUri URI to the primary overlay image (the art).
 * @property originalOverlayImageUri URI to the original un-edited overlay image.
 * @property thumbnailUri URI to a generated thumbnail for the project library.
 * @property targetImageUris List of URIs for images used as AR tracking targets.
 * @property refinementPaths Paths used for manual refinement of the overlay alignment.
 * @property opacity Global opacity of the overlay (0.0 - 1.0).
 * @property brightness Brightness adjustment (-1.0 to 1.0).
 * @property contrast Contrast adjustment (0.0 to 2.0+).
 * @property saturation Saturation adjustment (0.0 = grayscale, 1.0 = normal).
 * @property colorBalanceR Red channel balance.
 * @property colorBalanceG Green channel balance.
 * @property colorBalanceB Blue channel balance.
 * @property scale Global scale factor for the overlay.
 * @property rotationX Rotation around X axis (degrees).
 * @property rotationY Rotation around Y axis (degrees).
 * @property rotationZ Rotation around Z axis (degrees).
 * @property offset 2D translation offset in screen space.
 * @property blendMode Blending mode for the overlay (e.g., Multiply, Overlay).
 * @property fingerprint Legacy fingerprinting data (deprecated).
 * @property targetFingerprintPath Path to the ORB descriptor file for Teleological SLAM.
 * @property drawingPaths Vector paths drawn on the canvas (deprecated/unused?).
 * @property progressPercentage Completion percentage of the artwork.
 * @property evolutionImageUris History of progress snapshots.
 * @property gpsData Initial GPS location of the project.
 * @property sensorData Initial sensor orientation.
 * @property calibrationSnapshots List of calibration points recorded.
 * @property layers List of additional image layers for composition.
 * @property cloudAnchorId ID for Cloud Anchors (if enabled).
 * @property mapPath Path to the serialized MobileGS map file (.bin).
 * @property targetFingerprint Duplicate/Legacy field for target fingerprinting.
 * @property isRightHanded User preference for UI layout within this project.
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

    // Legacy Editor State (Kept for backward compatibility)
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
    val blendMode: BlendMode = BlendMode.SrcOver,

    val fingerprint: Fingerprint? = null,

    // Teleological Fingerprinting (NEW)
    // Stores the path to the ORB descriptor file extracted from the digital overlay.
    // This allows the SLAM engine to recognize the "Future Wall" as a valid anchor.
    val targetFingerprintPath: String? = null,

    // Using a simpler representation for drawing paths (list of point lists)
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
    val targetFingerprint: String? = null,
    val isRightHanded: Boolean = true
)
