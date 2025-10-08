package com.hereliesaz.graffitixr.data

import android.net.Uri
import com.google.ar.core.Pose
import com.hereliesaz.graffitixr.graphics.ArFeaturePattern
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ByteArraySerializer
import kotlinx.serialization.builtins.FloatArraySerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.encoding.encodeStructure
import org.opencv.core.Mat

// Serializer for android.net.Uri
object UriSerializer : KSerializer<Uri> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("Uri") {
            element<String>("uriString")
        }

    override fun serialize(encoder: Encoder, value: Uri) {
        encoder.encodeStructure(descriptor) {
            encodeStringElement(descriptor, 0, value.toString())
        }
    }

    override fun deserialize(decoder: Decoder): Uri {
        return decoder.decodeStructure(descriptor) {
            var uriString = ""
            while (true) {
                when (val index = decodeElementIndex(descriptor)) {
                    0 -> uriString = decodeStringElement(descriptor, 0)
                    else -> break
                }
            }
            Uri.parse(uriString)
        }
    }
}

// Serializer for com.google.ar.core.Pose
object PoseSerializer : KSerializer<Pose> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("Pose") {
            element<FloatArray>("translation")
            element<FloatArray>("rotation")
        }

    override fun serialize(encoder: Encoder, value: Pose) {
        encoder.encodeStructure(descriptor) {
            encodeSerializableElement(descriptor, 0, FloatArraySerializer(), value.translation)
            encodeSerializableElement(descriptor, 1, FloatArraySerializer(), value.rotationQuaternion)
        }
    }

    override fun deserialize(decoder: Decoder): Pose {
        return decoder.decodeStructure(descriptor) {
            var translation: FloatArray? = null
            var rotation: FloatArray? = null
            while (true) {
                when (val index = decodeElementIndex(descriptor)) {
                    0 -> translation = decodeSerializableElement(descriptor, 0, FloatArraySerializer())
                    1 -> rotation = decodeSerializableElement(descriptor, 1, FloatArraySerializer())
                    else -> break
                }
            }
            Pose(translation, rotation)
        }
    }
}

// Serializer for com.hereliesaz.graffitixr.graphics.ArFeaturePattern
object ArFeaturePatternSerializer : KSerializer<ArFeaturePattern> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("ArFeaturePattern") {
            element<Int>("rows")
            element<Int>("cols")
            element<Int>("type")
            element<ByteArray>("data")
            element<List<FloatArray>>("worldPoints")
        }

    override fun serialize(encoder: Encoder, value: ArFeaturePattern) {
        encoder.encodeStructure(descriptor) {
            val matData = ByteArray(value.descriptors.total().toInt() * value.descriptors.elemSize().toInt())
            if (value.descriptors.rows() > 0 && value.descriptors.cols() > 0) {
                value.descriptors.get(0, 0, matData)
            }
            encodeIntElement(descriptor, 0, value.descriptors.rows())
            encodeIntElement(descriptor, 1, value.descriptors.cols())
            encodeIntElement(descriptor, 2, value.descriptors.type())
            encodeSerializableElement(descriptor, 3, ByteArraySerializer(), matData)
            encodeSerializableElement(descriptor, 4, ListSerializer(FloatArraySerializer()), value.worldPoints)
        }
    }

    override fun deserialize(decoder: Decoder): ArFeaturePattern {
        return decoder.decodeStructure(descriptor) {
            var rows = 0
            var cols = 0
            var type = 0
            var matData: ByteArray? = null
            var worldPoints: List<FloatArray>? = null
            while (true) {
                when (val index = decodeElementIndex(descriptor)) {
                    0 -> rows = decodeIntElement(descriptor, 0)
                    1 -> cols = decodeIntElement(descriptor, 1)
                    2 -> type = decodeIntElement(descriptor, 2)
                    3 -> matData = decodeSerializableElement(descriptor, 3, ByteArraySerializer())
                    4 -> worldPoints = decodeSerializableElement(descriptor, 4, ListSerializer(FloatArraySerializer()))
                    else -> break
                }
            }
            val descriptors = Mat(rows, cols, type)
            if (rows > 0 && cols > 0) {
                descriptors.put(0, 0, matData)
            }
            ArFeaturePattern(descriptors, worldPoints!!)
        }
    }
}
