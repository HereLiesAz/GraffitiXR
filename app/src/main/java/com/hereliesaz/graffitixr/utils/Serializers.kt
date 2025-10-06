package com.hereliesaz.graffitixr.utils

import android.net.Uri
import com.google.ar.core.Pose
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.encoding.encodeStructure

/**
 * A custom serializer for the Android [Uri] class.
 */
object UriSerializer : KSerializer<Uri?> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("Uri") {
        element<String>("uriString")
    }

    override fun serialize(encoder: Encoder, value: Uri?) {
        encoder.encodeString(value?.toString() ?: "null")
    }

    override fun deserialize(decoder: Decoder): Uri? {
        val uriString = decoder.decodeString()
        return if (uriString == "null") null else Uri.parse(uriString)
    }
}

/**
 * A custom serializer for the ARCore [Pose] class.
 */
object PoseSerializer : KSerializer<Pose?> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("Pose") {
        element<FloatArray>("translation")
        element<FloatArray>("rotation")
    }

    override fun serialize(encoder: Encoder, value: Pose?) {
        encoder.encodeStructure(descriptor) {
            if (value != null) {
                encodeFloatArrayElement(descriptor, 0, value.translation)
                encodeFloatArrayElement(descriptor, 1, value.rotationQuaternion)
            }
        }
    }

    override fun deserialize(decoder: Decoder): Pose? {
        var translation: FloatArray? = null
        var rotation: FloatArray? = null
        decoder.decodeStructure(descriptor) {
            while (true) {
                when (val index = decodeElementIndex(descriptor)) {
                    0 -> translation = decodeFloatArrayElement(descriptor, 0)
                    1 -> rotation = decodeFloatArrayElement(descriptor, 1)
                    CompositeDecoder.DECODE_DONE -> break
                    else -> error("Unexpected index: $index")
                }
            }
        }
        return if (translation != null && rotation != null) {
            Pose(translation, rotation)
        } else {
            null
        }
    }
}