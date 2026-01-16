package com.hereliesaz.graffitixr.data

import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

// Serializer for non-null Uri. Kotlinx handles Uri? automatically when using this.
object UriSerializer : KSerializer<Uri> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Uri", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Uri) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): Uri {
        return Uri.parse(decoder.decodeString())
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
            try {
                Offset(parts[0].toFloat(), parts[1].toFloat())
            } catch (e: NumberFormatException) {
                Offset.Zero
            }
        } else {
            Offset.Zero
        }
    }
}

object BlendModeSerializer : KSerializer<BlendMode> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("BlendMode", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: BlendMode) {
        // Manually map to strings to avoid 'value class' issues and ensure stability
        val name = when (value) {
            BlendMode.Clear -> "Clear"
            BlendMode.Src -> "Src"
            BlendMode.Dst -> "Dst"
            BlendMode.SrcOver -> "SrcOver"
            BlendMode.DstOver -> "DstOver"
            BlendMode.SrcIn -> "SrcIn"
            BlendMode.DstIn -> "DstIn"
            BlendMode.SrcOut -> "SrcOut"
            BlendMode.DstOut -> "DstOut"
            BlendMode.SrcAtop -> "SrcAtop"
            BlendMode.DstAtop -> "DstAtop"
            BlendMode.Xor -> "Xor"
            BlendMode.Plus -> "Plus"
            BlendMode.Modulate -> "Modulate"
            BlendMode.Screen -> "Screen"
            BlendMode.Overlay -> "Overlay"
            BlendMode.Darken -> "Darken"
            BlendMode.Lighten -> "Lighten"
            BlendMode.ColorDodge -> "ColorDodge"
            BlendMode.ColorBurn -> "ColorBurn"
            BlendMode.Hardlight -> "Hardlight" // Fixed Casing
            BlendMode.Softlight -> "Softlight" // Fixed Casing
            BlendMode.Difference -> "Difference"
            BlendMode.Exclusion -> "Exclusion"
            BlendMode.Multiply -> "Multiply"
            BlendMode.Hue -> "Hue"
            BlendMode.Saturation -> "Saturation"
            BlendMode.Color -> "Color"
            BlendMode.Luminosity -> "Luminosity"
            else -> "SrcOver"
        }
        encoder.encodeString(name)
    }

    override fun deserialize(decoder: Decoder): BlendMode {
        return when (decoder.decodeString()) {
            "Clear" -> BlendMode.Clear
            "Src" -> BlendMode.Src
            "Dst" -> BlendMode.Dst
            "SrcOver" -> BlendMode.SrcOver
            "DstOver" -> BlendMode.DstOver
            "SrcIn" -> BlendMode.SrcIn
            "DstIn" -> BlendMode.DstIn
            "SrcOut" -> BlendMode.SrcOut
            "DstOut" -> BlendMode.DstOut
            "SrcAtop" -> BlendMode.SrcAtop
            "DstAtop" -> BlendMode.DstAtop
            "Xor" -> BlendMode.Xor
            "Plus" -> BlendMode.Plus
            "Modulate" -> BlendMode.Modulate
            "Screen" -> BlendMode.Screen
            "Overlay" -> BlendMode.Overlay
            "Darken" -> BlendMode.Darken
            "Lighten" -> BlendMode.Lighten
            "ColorDodge" -> BlendMode.ColorDodge
            "ColorBurn" -> BlendMode.ColorBurn
            "Hardlight", "HardLight" -> BlendMode.Hardlight // Handle potential legacy casing
            "Softlight", "SoftLight" -> BlendMode.Softlight // Handle potential legacy casing
            "Difference" -> BlendMode.Difference
            "Exclusion" -> BlendMode.Exclusion
            "Multiply" -> BlendMode.Multiply
            "Hue" -> BlendMode.Hue
            "Saturation" -> BlendMode.Saturation
            "Color" -> BlendMode.Color
            "Luminosity" -> BlendMode.Luminosity
            else -> BlendMode.SrcOver
        }
    }
}