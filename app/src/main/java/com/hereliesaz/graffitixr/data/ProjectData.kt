package com.hereliesaz.graffitixr.data

import android.net.Uri
import androidx.compose.ui.geometry.Offset
import com.hereliesaz.graffitixr.data.UriSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

@Serializable
data class ProjectData(
    @Serializable(with = UriSerializer::class)
    val backgroundImageUri: Uri?,
    @Serializable(with = UriSerializer::class)
    val overlayImageUri: Uri?,
    @Serializable(with = UriSerializer::class)
    val targetImageUri: Uri? = null,
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
    val fingerprint: Fingerprint?,
    @Serializable(with = DrawingPathsSerializer::class)
    val drawingPaths: List<List<Pair<Float, Float>>>
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
        return Pair(list[0], list[1])
    }
}
