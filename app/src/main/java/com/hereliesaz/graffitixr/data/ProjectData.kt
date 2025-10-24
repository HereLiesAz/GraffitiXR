package com.hereliesaz.graffitixr.data

import android.net.Uri
import androidx.compose.ui.geometry.Offset
import com.hereliesaz.graffitixr.data.UriSerializer
import kotlinx.serialization.Serializable

@Serializable
data class ProjectData(
    @Serializable(with = UriSerializer::class)
    val backgroundImageUri: Uri?,
    @Serializable(with = UriSerializer::class)
    val overlayImageUri: Uri?,
    val opacity: Float,
    val contrast: Float,
    val saturation: Float,
    val colorBalanceR: Float,
    val colorBalanceG: Float,
    val colorBalanceB: Float,
    val scale: Float,
    val rotationZ: Float,
    val rotationX: Float,
    val rotationY: Float,
    @Serializable(with = OffsetSerializer::class)
    val offset: Offset,
    @Serializable(with = BlendModeSerializer::class)
    val blendMode: androidx.compose.ui.graphics.BlendMode,
    @Serializable(with = FingerprintSerializer::class)
    val fingerprint: Fingerprint?
)

@Serializable
data class Fingerprint(
    val keypoints: List<@Serializable(with = KeyPointSerializer::class) org.opencv.core.KeyPoint>,
    @Serializable(with = MatSerializer::class)
    val descriptors: org.opencv.core.Mat
)
