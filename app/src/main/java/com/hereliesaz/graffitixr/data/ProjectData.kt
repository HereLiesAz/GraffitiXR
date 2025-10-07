package com.hereliesaz.graffitixr.data

import android.net.Uri
import androidx.compose.ui.geometry.Offset
import com.google.ar.core.Pose
import com.hereliesaz.graffitixr.EditorMode
import com.hereliesaz.graffitixr.RotationAxis
import com.hereliesaz.graffitixr.graphics.ArFeaturePattern
import com.hereliesaz.graffitixr.graphics.Quaternion
import kotlinx.serialization.Serializable

@Serializable
data class ProjectData(
    val editorMode: EditorMode,
    @Serializable(with = UriSerializer::class)
    val backgroundImageUri: Uri?,
    @Serializable(with = UriSerializer::class)
    val overlayImageUri: Uri?,
    @Serializable(with = UriSerializer::class)
    val backgroundRemovedImageUri: Uri?,
    val opacity: Float,
    val contrast: Float,
    val saturation: Float,
    val scale: Float,
    val rotationZ: Float,
    @Serializable(with = OffsetSerializer::class)
    val offset: Offset,
    @Serializable(with = PoseSerializer::class)
    val arImagePose: Pose?,
    val arObjectScale: Float,
    val arObjectOrientation: Quaternion,
    @Serializable(with = ArFeaturePatternSerializer::class)
    val arFeaturePattern: ArFeaturePattern?,
    val rotationX: Float,
    val rotationY: Float,
    val activeRotationAxis: RotationAxis,
    val colorBalanceR: Float = 1f,
    val colorBalanceG: Float = 1f,
    val colorBalanceB: Float = 1f
)