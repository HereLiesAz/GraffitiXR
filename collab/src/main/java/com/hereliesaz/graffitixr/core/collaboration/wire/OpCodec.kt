package com.hereliesaz.graffitixr.core.collaboration.wire

import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
internal object OpCodec {

    private val cbor = Cbor

    inline fun <reified T> encode(value: T): ByteArray = cbor.encodeToByteArray(value)
    inline fun <reified T> decode(bytes: ByteArray): T = cbor.decodeFromByteArray(bytes)
}
