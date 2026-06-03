package com.hereliesaz.graffitixr.common.model

import kotlinx.serialization.Serializable

/**
 * A unique identifier for an AR target image.
 * Now includes 3D points for Perspective-n-Point (PnP) relocalization.
 */
@Serializable
data class Fingerprint(
    val keypoints: List<@Serializable(with = com.hereliesaz.graffitixr.common.serialization.KeyPointSerializer::class) org.opencv.core.KeyPoint>,
    // 3D coordinates relative to the anchor at capture time.
    // Flattened list [x0, y0, z0, x1, y1, z1...] for serialization efficiency.
    val points3d: List<Float> = emptyList(),
    val descriptorsData: ByteArray,
    val descriptorsRows: Int,
    val descriptorsCols: Int,
    val descriptorsType: Int,
    // Canonical 256x256 raw-gray patch of the marks (row-major) for the distortion head. Empty when
    // the head isn't in use. Defaulted so older saved projects deserialize unchanged.
    val patchData: ByteArray = ByteArray(0),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as Fingerprint
        if (keypoints != other.keypoints) return false
        if (points3d != other.points3d) return false
        if (!descriptorsData.contentEquals(other.descriptorsData)) return false
        if (descriptorsRows != other.descriptorsRows) return false
        if (descriptorsCols != other.descriptorsCols) return false
        if (descriptorsType != other.descriptorsType) return false
        if (!patchData.contentEquals(other.patchData)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = keypoints.hashCode()
        result = 31 * result + points3d.hashCode()
        result = 31 * result + descriptorsData.contentHashCode()
        result = 31 * result + descriptorsRows
        result = 31 * result + descriptorsCols
        result = 31 * result + descriptorsType
        result = 31 * result + patchData.contentHashCode()
        return result
    }
}