@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
package com.hereliesaz.graffitixr.utils

import android.net.Uri
import com.google.ar.core.Pose
import com.hereliesaz.graffitixr.graphics.Quaternion
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.FloatArraySerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.encoding.encodeStructure

object UriSerializer : KSerializer<Uri?> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Uri", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Uri?) {
        encoder.encodeString(value?.toString() ?: "")
    }

    override fun deserialize(decoder: Decoder): Uri? {
        val string = decoder.decodeString()
        return if (string.isNotEmpty()) Uri.parse(string) else null
    }
}

object PoseSerializer : KSerializer<Pose?> {
    private val floatArraySerializer = FloatArraySerializer()
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("Pose") {
        element<FloatArray>("translation")
        element<FloatArray>("rotationQuaternion")
    }

    override fun serialize(encoder: Encoder, value: Pose?) {
        if (value == null) {
            encoder.encodeNull()
        } else {
            encoder.encodeStructure(descriptor) {
                encodeSerializableElement(descriptor, 0, floatArraySerializer, value.translation)
                encodeSerializableElement(descriptor, 1, floatArraySerializer, value.rotationQuaternion)
            }
        }
    }

    override fun deserialize(decoder: Decoder): Pose? {
        return decoder.decodeStructure(descriptor) {
            var translation: FloatArray? = null
            var rotation: FloatArray? = null
            while (true) {
                when (val index = decodeElementIndex(descriptor)) {
                    -1 -> break
                    0 -> translation = decodeSerializableElement(descriptor, 0, floatArraySerializer)
                    1 -> rotation = decodeSerializableElement(descriptor, 1, floatArraySerializer)
                    else -> error("Unexpected index: $index")
                }
            }
            if (translation != null && rotation != null) {
                Pose(translation, rotation)
            } else {
                null
            }
        }
    }
}

object QuaternionSerializer : KSerializer<Quaternion> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("Quaternion") {
        element<Float>("x")
        element<Float>("y")
        element<Float>("z")
        element<Float>("w")
    }

    override fun serialize(encoder: Encoder, value: Quaternion) {
        encoder.encodeStructure(descriptor) {
            encodeFloatElement(descriptor, 0, value.x)
            encodeFloatElement(descriptor, 1, value.y)
            encodeFloatElement(descriptor, 2, value.z)
            encodeFloatElement(descriptor, 3, value.w)
        }
    }

    override fun deserialize(decoder: Decoder): Quaternion {
        return decoder.decodeStructure(descriptor) {
            var x = 0f
            var y = 0f
            var z = 0f
            var w = 0f
            while (true) {
                when (val index = decodeElementIndex(descriptor)) {
                    -1 -> break
                    0 -> x = decodeFloatElement(descriptor, 0)
                    1 -> y = decodeFloatElement(descriptor, 1)
                    2 -> z = decodeFloatElement(descriptor, 2)
                    3 -> w = decodeFloatElement(descriptor, 3)
                    else -> error("Unexpected index: $index")
                }
            }
            Quaternion(x, y, z, w)
        }
    }
}
