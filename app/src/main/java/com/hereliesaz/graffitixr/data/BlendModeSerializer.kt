package com.hereliesaz.graffitixr.data

import androidx.compose.ui.graphics.BlendMode
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

object BlendModeSerializer : KSerializer<BlendMode> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("BlendMode", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: BlendMode) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): BlendMode {
        return when (val blendModeString = decoder.decodeString()) {
            "SrcOver" -> BlendMode.SrcOver
            "Multiply" -> BlendMode.Multiply
            "Screen" -> BlendMode.Screen
            "Overlay" -> BlendMode.Overlay
            "Darken" -> BlendMode.Darken
            "Lighten" -> BlendMode.Lighten
            else -> throw IllegalArgumentException("Unknown blend mode: $blendModeString")
        }
    }
}
