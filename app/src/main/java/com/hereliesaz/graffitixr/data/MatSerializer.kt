package com.hereliesaz.graffitixr.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.encoding.encodeStructure
import org.opencv.core.CvType
import org.opencv.core.Mat

object MatSerializer : KSerializer<Mat> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("Mat") {
        element<Int>("rows")
        element<Int>("cols")
        element<Int>("type")
        element<ByteArray>("data")
    }

    override fun serialize(encoder: Encoder, value: Mat) {
        val data = ByteArray(value.total().toInt() * value.elemSize().toInt())
        value.get(0, 0, data)
        encoder.encodeStructure(descriptor) {
            encodeIntElement(descriptor, 0, value.rows())
            encodeIntElement(descriptor, 1, value.cols())
            encodeIntElement(descriptor, 2, value.type())
            encodeSerializableElement(descriptor, 3, kotlinx.serialization.builtins.ByteArraySerializer(), data)
        }
    }

    override fun deserialize(decoder: Decoder): Mat {
        return decoder.decodeStructure(descriptor) {
            var rows = 0
            var cols = 0
            var type = 0
            var data = ByteArray(0)
            while (true) {
                when (val index = decodeElementIndex(descriptor)) {
                    0 -> rows = decodeIntElement(descriptor, 0)
                    1 -> cols = decodeIntElement(descriptor, 1)
                    2 -> type = decodeIntElement(descriptor, 2)
                    3 -> data = decodeSerializableElement(descriptor, 3, kotlinx.serialization.builtins.ByteArraySerializer())
                    CompositeDecoder.DECODE_DONE -> break
                    else -> error("Unexpected index: $index")
                }
            }
            Mat(rows, cols, type).apply {
                put(0, 0, data)
            }
        }
    }
}
