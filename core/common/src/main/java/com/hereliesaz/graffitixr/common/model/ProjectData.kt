package com.hereliesaz.graffitixr.common.model

import android.net.Uri
import android.os.Parcelable
import com.hereliesaz.graffitixr.common.serialization.BlendModeSerializer
import com.hereliesaz.graffitixr.common.serialization.OffsetSerializer
import com.hereliesaz.graffitixr.common.serialization.UriSerializer
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
@Parcelize
data class GpsData(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val accuracy: Float,
    val time: Long
) : Parcelable

@Serializable
@Parcelize
data class SensorData(
    val azimuth: Float,
    val pitch: Float,
    val roll: Float
) : Parcelable

@Serializable
@Parcelize
data class CalibrationSnapshot(
    val gpsData: GpsData?,
    val sensorData: SensorData?,
    val poseMatrix: List<Float>?,
    val timestamp: Long
) : Parcelable

@Serializable
data class ProjectData(
    // Force Recompile
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
    val offset: androidx.compose.ui.geometry.Offset = androidx.compose.ui.geometry.Offset.Zero,

    @Serializable(with = BlendModeSerializer::class)
    val blendMode: androidx.compose.ui.graphics.BlendMode = androidx.compose.ui.graphics.BlendMode.SrcOver,

    val fingerprint: Fingerprint? = null,

    // Using a simpler representation for drawing paths (list of point lists)
    // Serialization of Pair<Float, Float> needs to be handled or simplified.
    // For now we assume standard list serialization works for basic types if Pair is serializable
    // Pair is Serializable in Kotlin.
    val drawingPaths: List<List<Pair<Float, Float>>> = emptyList(),

    val progressPercentage: Float = 0f,

    val evolutionImageUris: List<@Serializable(with = UriSerializer::class) Uri> = emptyList(),

    val gpsData: GpsData? = null,
    val sensorData: SensorData? = null,
    val calibrationSnapshots: List<CalibrationSnapshot> = emptyList(),

    val layers: List<OverlayLayer> = emptyList(),

    // Neural Scan ID
    val cloudAnchorId: String? = null
) {
    companion object {
        fun create(name: String): ProjectData {
            return ProjectData(name = name)
        }
    }
}