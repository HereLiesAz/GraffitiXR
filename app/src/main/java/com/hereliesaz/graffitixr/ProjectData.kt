package com.hereliesaz.graffitixr

import android.net.Uri
import com.google.ar.core.Pose
import com.hereliesaz.graffitixr.graphics.Quaternion
import com.hereliesaz.graffitixr.utils.PoseSerializer
import com.hereliesaz.graffitixr.utils.UriSerializer
import kotlinx.serialization.Serializable

/**
 * A data class representing the serializable state of a saved project.
 * This class is used to convert the relevant parts of the UiState to JSON.
 */
@Serializable
data class ProjectData(
    @Serializable(with = UriSerializer::class)
    val overlayImageUri: Uri?,
    @Serializable(with = UriSerializer::class)
    val backgroundImageUri: Uri?,
    val opacity: Float,
    val contrast: Float,
    val saturation: Float,
    // Non-AR transformations
    val scale: Float,
    val rotationX: Float,
    val rotationY: Float,
    val rotationZ: Float,
    // AR transformations
    @Serializable(with = PoseSerializer::class)
    val arImagePose: Pose?,
    val arObjectScale: Float,
    val arObjectOrientation: Quaternion
)