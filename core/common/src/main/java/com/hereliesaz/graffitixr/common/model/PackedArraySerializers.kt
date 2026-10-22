package com.hereliesaz.graffitixr.common.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ByteArraySerializer
import kotlinx.serialization.builtins.FloatArraySerializer
import kotlinx.serialization.builtins.IntArraySerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/**
 * Primitive arrays written as one Base64 string (little-endian for floats/ints) instead of a JSON
 * number list. A [WallFeatureMap] at its cap holds megabytes of descriptors and points; as a
 * pretty-printed list every element took its own indented line, and project.json grew past the
 * heap (OOM in ProjectManager.saveProjectInternal). Reading still accepts the old list form, so
 * projects saved before this change open unchanged.
 */
private abstract class PackedArraySerializer<T>(
    name: String,
    private val legacy: KSerializer<T>,
) : KSerializer<T> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(name, PrimitiveKind.STRING)

    abstract fun toBytes(value: T): ByteArray
    abstract fun fromBytes(bytes: ByteArray): T

    override fun serialize(encoder: Encoder, value: T) =
        encoder.encodeString(Base64.getEncoder().encodeToString(toBytes(value)))

    override fun deserialize(decoder: Decoder): T {
        if (decoder is JsonDecoder) {
            return when (val element = decoder.decodeJsonElement()) {
                is JsonArray -> decoder.json.decodeFromJsonElement(legacy, element)
                is JsonPrimitive -> fromBytes(Base64.getDecoder().decode(element.content))
                else -> throw IllegalArgumentException("expected a Base64 string or an array")
            }
        }
        return fromBytes(Base64.getDecoder().decode(decoder.decodeString()))
    }
}

private fun le(size: Int): ByteBuffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

object PackedByteArraySerializer : KSerializer<ByteArray> by object :
    PackedArraySerializer<ByteArray>("PackedByteArray", ByteArraySerializer()) {
    override fun toBytes(value: ByteArray) = value
    override fun fromBytes(bytes: ByteArray) = bytes
}

object PackedFloatArraySerializer : KSerializer<FloatArray> by object :
    PackedArraySerializer<FloatArray>("PackedFloatArray", FloatArraySerializer()) {
    override fun toBytes(value: FloatArray) = le(value.size * 4).apply { asFloatBuffer().put(value) }.array()
    override fun fromBytes(bytes: ByteArray): FloatArray {
        require(bytes.size % 4 == 0) { "packed float array length ${bytes.size} is not a multiple of 4" }
        return FloatArray(bytes.size / 4).also { ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(it) }
    }
}

object PackedIntArraySerializer : KSerializer<IntArray> by object :
    PackedArraySerializer<IntArray>("PackedIntArray", IntArraySerializer()) {
    override fun toBytes(value: IntArray) = le(value.size * 4).apply { asIntBuffer().put(value) }.array()
    override fun fromBytes(bytes: ByteArray): IntArray {
        require(bytes.size % 4 == 0) { "packed int array length ${bytes.size} is not a multiple of 4" }
        return IntArray(bytes.size / 4).also { ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(it) }
    }
}
