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
import org.opencv.core.Mat

object MatSerializer : KSerializer<Mat> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("Mat") {
        element<Int>("rows")
        element<Int>("cols")
        element<Int>("type")
        element<ByteArray>("data")
    }

    override fun serialize(encoder: Encoder, value: Mat) {
        val expectedSize = value.total().toInt() * value.elemSize().toInt()
        if (expectedSize <= 0) {
            throw IllegalArgumentException("Invalid Mat dimensions or type: rows=${value.rows()}, cols=${value.cols()}, type=${value.type()}")
        }
        val data = ByteArray(expectedSize)
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
            if (rows <= 0 || cols <= 0) {
                throw IllegalArgumentException("Invalid Mat dimensions: rows=$rows, cols=$cols")
            }
            // Sentinel Security: Prevent DoS/OOM via malformed dimensions
            if (rows > 32768 || cols > 32768) {
                throw IllegalArgumentException("Mat dimensions too large (max 32768): rows=$rows, cols=$cols")
            }
            // Sentinel Security: Check for integer overflow in size calculation
            val totalElements = rows.toLong() * cols.toLong()
            val elemSize = org.opencv.core.CvType.ELEM_SIZE(type).toLong()
            val expectedSizeLong = totalElements * elemSize

            if (expectedSizeLong > Int.MAX_VALUE) {
                throw IllegalArgumentException("Mat byte size too large")
            }

            val expectedSize = expectedSizeLong.toInt()

            if (data.size != expectedSize) {
                throw IllegalArgumentException("Data size mismatch: expected $expectedSize, got ${data.size}")
            }
            Mat(rows, cols, type).apply {
                put(0, 0, data)
            }
        }
    }
}
