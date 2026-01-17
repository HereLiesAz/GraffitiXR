package com.hereliesaz.graffitixr.data

import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.util.UUID

// --- ENUMS (Unified) ---

enum class ArState {
    SEARCHING,
    LOCKED,
    PLACED
}

enum class RotationAxis { X, Y, Z }

// --- DATA CLASSES ---

@Serializable
data class KeyPointData(
    val x: Float,
    val y: Float,
    val size: Float,
    val angle: Float,
    val response: Float,
    val octave: Int,
    val classId: Int
)

@Serializable
data class Fingerprint(
    val keypoints: List<KeyPointData>,
    val descriptorsData: ByteArray,
    val descriptorsRows: Int,
    val descriptorsCols: Int,
    val descriptorsType: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as Fingerprint
        if (descriptorsRows != other.descriptorsRows) return false
        if (descriptorsCols != other.descriptorsCols) return false
        if (!descriptorsData.contentEquals(other.descriptorsData)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = descriptorsRows
        result = 31 * result + descriptorsCols
        result = 31 * result + descriptorsData.contentHashCode()
        return result
    }
}

object FingerprintSerializer : KSerializer<Fingerprint> {
    override val descriptor: SerialDescriptor = Fingerprint.serializer().descriptor
    override fun serialize(encoder: Encoder, value: Fingerprint) = Fingerprint.serializer().serialize(encoder, value)
    override fun deserialize(decoder: Decoder): Fingerprint = Fingerprint.serializer().deserialize(decoder)
}

data class OverlayLayer(
    val id: String = UUID.randomUUID().toString(),
    val uri: Uri,
    val name: String,
    val isVisible: Boolean = true,

    // Transforms
    val scale: Float = 1.0f,
    val rotationX: Float = 0f,
    val rotationY: Float = 0f,
    val rotationZ: Float = 0f,
    val offset: Offset = Offset.Zero,

    // Adjustments
    val opacity: Float = 1.0f,
    val brightness: Float = 0f,
    val contrast: Float = 1.0f,
    val saturation: Float = 1.0f,

    // Color Balance
    val colorBalanceR: Float = 1.0f,
    val colorBalanceG: Float = 1.0f,
    val colorBalanceB: Float = 1.0f,

    // Composition
    val blendMode: BlendMode = BlendMode.SrcOver
)