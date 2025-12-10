package com.hereliesaz.graffitixr.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.encoding.encodeStructure

object RefinementPathSerializer : KSerializer<RefinementPath> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("RefinementPath") {
        element<List<com.hereliesaz.graffitixr.data.PairFloatFloatSerializer.SerializablePair>>("points")
        element<Boolean>("isEraser")
    }

    override fun serialize(encoder: Encoder, value: RefinementPath) {
        // Convert Offset to Pair<Float, Float> for serialization using existing serializer structure or simple list
        // Since we have OffsetSerializer, let's look at ProjectData. It used a specific way.
        // Let's implement manually here for simplicity and robustness.

        encoder.encodeStructure(descriptor) {
            val pointList = value.points.map { it.x to it.y }
            // Using a custom list serializer for points (x, y)
            encodeSerializableElement(descriptor, 0, ListSerializer(PairFloatFloatSerializer), pointList)
            encodeBooleanElement(descriptor, 1, value.isEraser)
        }
    }

    override fun deserialize(decoder: Decoder): RefinementPath {
        return decoder.decodeStructure(descriptor) {
            var points = emptyList<Pair<Float, Float>>()
            var isEraser = false

            while (true) {
                when (val index = decodeElementIndex(descriptor)) {
                    0 -> points = decodeSerializableElement(descriptor, 0, ListSerializer(PairFloatFloatSerializer))
                    1 -> isEraser = decodeBooleanElement(descriptor, 1)
                    CompositeDecoder.DECODE_DONE -> break
                    else -> error("Unexpected index: $index")
                }
            }
            val offsetList = points.map { androidx.compose.ui.geometry.Offset(it.first, it.second) }
            RefinementPath(offsetList, isEraser)
        }
    }
}