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

object PairFloatFloatSerializer : KSerializer<Pair<Float, Float>> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("PairFloatFloat") {
        element<Float>("first")
        element<Float>("second")
    }

    override fun serialize(encoder: Encoder, value: Pair<Float, Float>) {
        encoder.encodeStructure(descriptor) {
            encodeFloatElement(descriptor, 0, value.first)
            encodeFloatElement(descriptor, 1, value.second)
        }
    }

    override fun deserialize(decoder: Decoder): Pair<Float, Float> {
        return decoder.decodeStructure(descriptor) {
            var first = 0f
            var second = 0f
            while (true) {
                when (val index = decodeElementIndex(descriptor)) {
                    0 -> first = decodeFloatElement(descriptor, 0)
                    1 -> second = decodeFloatElement(descriptor, 1)
                    CompositeDecoder.DECODE_DONE -> break
                    else -> error("Unexpected index: $index")
                }
            }
            first to second
        }
    }
}

object RefinementPathSerializer : KSerializer<RefinementPath> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("RefinementPath") {
        element("points", ListSerializer(PairFloatFloatSerializer).descriptor)
        element<Boolean>("isEraser")
    }

    override fun serialize(encoder: Encoder, value: RefinementPath) {
        encoder.encodeStructure(descriptor) {
            val pointList = value.points.map { it.x to it.y }
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