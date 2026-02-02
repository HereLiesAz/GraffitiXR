package com.hereliesaz.graffitixr.common.serialization

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.encoding.encodeStructure
import org.opencv.core.KeyPoint

object KeyPointSerializer : KSerializer<KeyPoint> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("KeyPoint") {
        element<Float>("x")
        element<Float>("y")
        element<Float>("size")
        element<Float>("angle")
        element<Float>("response")
        element<Int>("octave")
        element<Int>("class_id")
    }

    override fun serialize(encoder: Encoder, value: KeyPoint) {
        encoder.encodeStructure(descriptor) {
            encodeFloatElement(descriptor, 0, value.pt.x.toFloat())
            encodeFloatElement(descriptor, 1, value.pt.y.toFloat())
            encodeFloatElement(descriptor, 2, value.size)
            encodeFloatElement(descriptor, 3, value.angle)
            encodeFloatElement(descriptor, 4, value.response)
            encodeIntElement(descriptor, 5, value.octave)
            encodeIntElement(descriptor, 6, value.class_id)
        }
    }

    override fun deserialize(decoder: Decoder): KeyPoint {
        return decoder.decodeStructure(descriptor) {
            var x = 0f
            var y = 0f
            var size = 0f
            var angle = 0f
            var response = 0f
            var octave = 0
            var class_id = 0
            while (true) {
                when (val index = decodeElementIndex(descriptor)) {
                    0 -> x = decodeFloatElement(descriptor, 0)
                    1 -> y = decodeFloatElement(descriptor, 1)
                    2 -> size = decodeFloatElement(descriptor, 2)
                    3 -> angle = decodeFloatElement(descriptor, 3)
                    4 -> response = decodeFloatElement(descriptor, 4)
                    5 -> octave = decodeIntElement(descriptor, 5)
                    6 -> class_id = decodeIntElement(descriptor, 6)
                    CompositeDecoder.DECODE_DONE -> break
                    else -> error("Unexpected index: $index")
                }
            }
            KeyPoint(x, y, size, angle, response, octave, class_id)
        }
    }
}
