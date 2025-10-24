package com.hereliesaz.graffitixr.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.builtins.ListSerializer

object FingerprintSerializer : KSerializer<Fingerprint> {
    override val descriptor: SerialDescriptor = Fingerprint.serializer().descriptor

    override fun serialize(encoder: Encoder, value: Fingerprint) {
        Fingerprint.serializer().serialize(encoder, value)
    }

    override fun deserialize(decoder: Decoder): Fingerprint {
        return Fingerprint.serializer().deserialize(decoder)
    }
}
