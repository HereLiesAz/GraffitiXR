package com.hereliesaz.graffitixr.data

import kotlinx.serialization.Serializable

/**
 * A unique identifier for an AR target image.
 * 
 * Bolt Optimization: Replaced OpenCV Mat with ByteArray to prevent finalizer n_delete crashes.
 * Descriptors are stored as raw bytes and metadata for safe serialization and thread-safe handling.
 */
@Serializable
data class Fingerprint(
    val keypoints: List<@Serializable(with = KeyPointSerializer::class) org.opencv.core.KeyPoint>,
    val descriptorsData: ByteArray,
    val descriptorsRows: Int,
    val descriptorsCols: Int,
    val descriptorsType: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as Fingerprint
        if (keypoints != other.keypoints) return false
        if (!descriptorsData.contentEquals(other.descriptorsData)) return false
        if (descriptorsRows != other.descriptorsRows) return false
        if (descriptorsCols != other.descriptorsCols) return false
        if (descriptorsType != other.descriptorsType) return false
        return true
    }

    override fun hashCode(): Int {
        var result = keypoints.hashCode()
        result = 31 * result + descriptorsData.contentHashCode()
        result = 31 * result + descriptorsRows
        result = 31 * result + descriptorsCols
        result = 31 * result + descriptorsType
        return result
    }
}
