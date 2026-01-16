package com.hereliesaz.graffitixr.data

import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

object UriSerializer : KSerializer<Uri?> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Uri", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Uri?) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): Uri? {
        val string = decoder.decodeString()
        return if (string == "null" || string.isEmpty()) null else Uri.parse(string)
    }
}

object OffsetSerializer : KSerializer<Offset> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Offset", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Offset) {
        encoder.encodeString("${value.x},${value.y}")
    }

    override fun deserialize(decoder: Decoder): Offset {
        val string = decoder.decodeString()
        val parts = string.split(",")
        return if (parts.size == 2) {
            Offset(parts[0].toFloat(), parts[1].toFloat())
        } else {
            Offset.Zero
        }
    }
}

object BlendModeSerializer : KSerializer<BlendMode> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("BlendMode", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: BlendMode) {
        encoder.encodeString(value.name)
    }

    override fun deserialize(decoder: Decoder): BlendMode {
        return try {
            BlendMode.valueOf(decoder.decodeString())
        } catch (e: Exception) {
            BlendMode.SrcOver
        }
    }
}