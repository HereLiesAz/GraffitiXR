package com.hereliesaz.graffitixr.common.model

import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.decodeFromByteArray
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
class OpTest {

    private val cbor = Cbor

    @Test
    fun `LayerAdd round-trips through Cbor`() {
        val original: Op = Op.LayerAdd(Layer(id = "L1", name = "one"))
        val bytes = cbor.encodeToByteArray(original)
        val decoded = cbor.decodeFromByteArray<Op>(bytes)
        assertEquals(original, decoded)
    }

    @Test
    fun `LayerRemove round-trips`() {
        val original: Op = Op.LayerRemove(layerId = "L1")
        val bytes = cbor.encodeToByteArray(original)
        assertEquals(original, cbor.decodeFromByteArray<Op>(bytes))
    }

    @Test
    fun `LayerReorder preserves order`() {
        val original: Op = Op.LayerReorder(newOrder = listOf("L1", "L2", "L3"))
        val decoded = cbor.decodeFromByteArray<Op>(cbor.encodeToByteArray(original))
        assertEquals(original, decoded)
    }

    @Test
    fun `LayerTransform round-trips matrix as float list`() {
        val original: Op = Op.LayerTransform(layerId = "L1", matrix = List(16) { it.toFloat() })
        val decoded = cbor.decodeFromByteArray<Op>(cbor.encodeToByteArray(original))
        assertEquals(original, decoded)
    }

    @Test
    fun `TextContentChange round-trips`() {
        val original: Op = Op.TextContentChange(layerId = "L1", text = "hello world")
        val decoded = cbor.decodeFromByteArray<Op>(cbor.encodeToByteArray(original))
        assertEquals(original, decoded)
    }
}
