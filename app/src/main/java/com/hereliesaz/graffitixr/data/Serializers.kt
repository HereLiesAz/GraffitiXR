package com.hereliesaz.graffitixr.data

import android.net.Uri
import androidx.compose.ui.geometry.Offset
import com.google.ar.core.Pose
import com.hereliesaz.graffitixr.graphics.ArFeaturePattern
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ByteArraySerializer
import kotlinx.serialization.builtins.FloatArraySerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import org.opencv.core.Mat
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.encoding.encodeStructure

object UriSerializer : KSerializer<Uri> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("Uri") {
            element<String>("uriString")
        }

    override fun serialize(encoder: Encoder, value: Uri) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): Uri {
        return Uri.parse(decoder.decodeString())
    }
}

object OffsetSerializer : KSerializer<Offset> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("Offset") {
            element<Float>("x")
            element<Float>("y")
        }

    override fun serialize(encoder: Encoder, value: Offset) {
        encoder.encodeStructure(descriptor) {
            encodeFloatElement(descriptor, 0, value.x)
            encodeFloatElement(descriptor, 1, value.y)
        }
    }

    override fun deserialize(decoder: Decoder): Offset {
        return decoder.decodeStructure(descriptor) {
            var x = 0f
            var y = 0f
            while (true) {
                when (val index = decodeElementIndex(descriptor)) {
                    0 -> x = decodeFloatElement(descriptor, 0)
                    1 -> y = decodeFloatElement(descriptor, 1)
                    -1 -> break
                }
            }
            Offset(x, y)
        }
    }
}

object PoseSerializer : KSerializer<Pose?> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("Pose") {
            element<FloatArray>("translation")
            element<FloatArray>("rotationQuaternion")
        }

    override fun serialize(encoder: Encoder, value: Pose?) {
        if (value == null) {
            encoder.encodeNull()
            return
        }
        encoder.encodeStructure(descriptor) {
            encodeSerializableElement(descriptor, 0, FloatArraySerializer(), value.translation)
            encodeSerializableElement(descriptor, 1, FloatArraySerializer(), value.rotationQuaternion)
        }
    }

    override fun deserialize(decoder: Decoder): Pose? {
        if (decoder.decodeNotNullMark()) {
            return decoder.decodeStructure(descriptor) {
                var translation: FloatArray? = null
                var rotation: FloatArray? = null
                while (true) {
                    when (val index = decodeElementIndex(descriptor)) {
                        0 -> translation = decodeSerializableElement(descriptor, 0, FloatArraySerializer())
                        1 -> rotation = decodeSerializableElement(descriptor, 1, FloatArraySerializer())
                        -1 -> break
                    }
                }
                Pose(translation, rotation)
            }
        } else {
            return decoder.decodeNull()
        }
    }
}

object ArFeaturePatternSerializer : KSerializer<ArFeaturePattern?> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("ArFeaturePattern") {
            element<Int>("rows")
            element<Int>("cols")
            element<Int>("type")
            element<ByteArray>("data")
            element<List<FloatArray>>("worldPoints")
        }

    override fun serialize(encoder: Encoder, value: ArFeaturePattern?) {
        if (value == null) {
            encoder.encodeNull()
            return
        }
        val matData = ByteArray(value.descriptors.total().toInt() * value.descriptors.elemSize().toInt())
        if (value.descriptors.rows() > 0 && value.descriptors.cols() > 0) {
            value.descriptors.get(0, 0, matData)
        }

        encoder.encodeStructure(descriptor) {
            encodeIntElement(descriptor, 0, value.descriptors.rows())
            encodeIntElement(descriptor, 1, value.descriptors.cols())
            encodeIntElement(descriptor, 2, value.descriptors.type())
            encodeSerializableElement(descriptor, 3, ByteArraySerializer(), matData)
            encodeSerializableElement(descriptor, 4, ListSerializer(FloatArraySerializer()), value.worldPoints)
        }
    }

    override fun deserialize(decoder: Decoder): ArFeaturePattern? {
         if (decoder.decodeNotNullMark()) {
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
                        -1 -> break
                    }
                }

                val descriptors = Mat(rows, cols, type)
                if (rows > 0 && cols > 0 && matData != null) {
                    descriptors.put(0, 0, matData)
                }

                ArFeaturePattern(descriptors, worldPoints ?: emptyList())
            }
        } else {
            return decoder.decodeNull()
        }
    }
}