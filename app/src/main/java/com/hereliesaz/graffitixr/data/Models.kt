package com.hereliesaz.graffitixr.data

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

// --- ENUMS ---

enum class ArState {
    SEARCHING, // Looking for planes or features
    LOCKED,    // Features found, anchor established (Tracking)
    PLACED     // User has explicitly placed the content
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

// Custom Serializer to handle the object if needed,
// though @Serializable usually handles the data class fine.
// This is a placeholder if you need custom JSON formatting later.
object FingerprintSerializer : KSerializer<Fingerprint> {
    override val descriptor: SerialDescriptor = Fingerprint.serializer().descriptor
    override fun serialize(encoder: Encoder, value: Fingerprint) = Fingerprint.serializer().serialize(encoder, value)
    override fun deserialize(decoder: Decoder): Fingerprint = Fingerprint.serializer().deserialize(decoder)
}

data class OverlayLayer(
    val id: String,
    val uri: Uri,
    val isVisible: Boolean = true,
    val opacity: Float = 1.0f,
    val brightness: Float = 0f,
    val colorBalanceR: Float = 1.0f,
    val colorBalanceG: Float = 1.0f,
    val colorBalanceB: Float = 1.0f,
    val blendMode: BlendMode = BlendMode.SrcOver,
    val scale: Float = 1.0f,
    val rotationX: Float = 0f,
    val rotationY: Float = 0f,
    val rotationZ: Float = 0f,
    val offset: Offset = Offset.Zero
)

// --- UI STATE ---

data class UiState(
    // AR Status
    val arState: ArState = ArState.SEARCHING,
    val isPlaneDetected: Boolean = false,
    val trackingWarning: String? = null,
    val mappingQualityScore: Float = 0f,
    val isTouchLocked: Boolean = false,
    val showUnlockHint: Boolean = false,

    // Capture & Processing
    val isCapturingTarget: Boolean = false,
    val isProcessingFingerprint: Boolean = false,
    val scanProgress: Float = 0f,
    val error: String? = null,
    val fingerprintJson: String? = null,
    val capturedTargetImages: Map<String, Bitmap> = emptyMap(), // ID -> Bitmap
    val isImageLocked: Boolean = false,

    // Layers & Transform
    val layers: List<OverlayLayer> = emptyList(),
    val activeLayerId: String? = null,
    val arObjectScale: Float = 1.0f,
    val rotationX: Float = 0f,
    val rotationY: Float = 0f,
    val rotationZ: Float = 0f,
    val activeRotationAxis: RotationAxis = RotationAxis.Y
)