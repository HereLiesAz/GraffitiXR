package com.hereliesaz.graffitixr.data

import android.net.Uri
import androidx.compose.ui.geometry.Offset
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable
data class ProjectData(
    @Serializable(with = UriSerializer::class)
    val backgroundImageUri: Uri?,
    @Serializable(with = UriSerializer::class)
    val overlayImageUri: Uri?,
    @Serializable(with = UriSerializer::class)
    val originalOverlayImageUri: Uri? = null,
    // List of URIs for the captured target images (Front, Left, Right, etc.)
    @Serializable(with = UriListSerializer::class)
    val targetImageUris: List<Uri> = emptyList(),
    // Saved mask paths
    @Serializable(with = RefinementPathListSerializer::class)
    val refinementPaths: List<RefinementPath> = emptyList(),

    val opacity: Float,
    val brightness: Float = 0f,
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
    val fingerprint: Fingerprint?,
    @Serializable(with = DrawingPathsSerializer::class)
    val drawingPaths: List<List<Pair<Float, Float>>>,
    val progressPercentage: Float = 0f,
    @Serializable(with = UriListSerializer::class)
    val evolutionImageUris: List<Uri> = emptyList(),
    val isLineDrawing: Boolean = false,
    val gpsData: GpsData? = null,
    val sensorData: SensorData? = null
)

@Serializable
data class GpsData(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val accuracy: Float,
    val time: Long
)

@Serializable
data class SensorData(
    val azimuth: Float,
    val pitch: Float,
    val roll: Float
)

object DrawingPathsSerializer : KSerializer<List<List<Pair<Float, Float>>>> {
    private val listSerializer = ListSerializer(ListSerializer(PairFloatFloatSerializer))
    override val descriptor = listSerializer.descriptor
    override fun serialize(encoder: Encoder, value: List<List<Pair<Float, Float>>>) {
        listSerializer.serialize(encoder, value)
    }
    override fun deserialize(decoder: Decoder): List<List<Pair<Float, Float>>> {
        return listSerializer.deserialize(decoder)
    }
}

object PairFloatFloatSerializer : KSerializer<Pair<Float, Float>> {
    override val descriptor = ListSerializer(Float.serializer()).descriptor
    override fun serialize(encoder: Encoder, value: Pair<Float, Float>) {
        val list = listOf(value.first, value.second)
        encoder.encodeSerializableValue(ListSerializer(Float.serializer()), list)
    }
    override fun deserialize(decoder: Decoder): Pair<Float, Float> {
        val list = decoder.decodeSerializableValue(ListSerializer(Float.serializer()))
        // Sentinel Security: Validate input size to prevent IndexOutOfBoundsException
        if (list.size != 2) {
            throw IllegalArgumentException("Invalid input for PairFloatFloatSerializer: Expected 2 elements, got ${list.size}")
        }
        return Pair(list[0], list[1])
    }
}

object NonNullableUriSerializer : KSerializer<Uri> {
    override val descriptor = PrimitiveSerialDescriptor("Uri", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Uri) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): Uri = Uri.parse(decoder.decodeString())
}

object UriListSerializer : KSerializer<List<Uri>> {
    private val serializer = ListSerializer(NonNullableUriSerializer)
    override val descriptor = serializer.descriptor
    override fun serialize(encoder: Encoder, value: List<Uri>) = serializer.serialize(encoder, value)
    override fun deserialize(decoder: Decoder): List<Uri> = serializer.deserialize(decoder)
}

object RefinementPathListSerializer : KSerializer<List<RefinementPath>> {
    private val serializer = ListSerializer(RefinementPathSerializer)
    override val descriptor = serializer.descriptor
    override fun serialize(encoder: Encoder, value: List<RefinementPath>) = serializer.serialize(encoder, value)
    override fun deserialize(decoder: Decoder): List<RefinementPath> = serializer.deserialize(decoder)
}