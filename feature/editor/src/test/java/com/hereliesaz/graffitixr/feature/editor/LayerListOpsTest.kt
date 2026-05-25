package com.hereliesaz.graffitixr.feature.editor

import com.hereliesaz.graffitixr.common.model.Layer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LayerListOpsTest {

    private fun lyr(id: String, name: String = id) = Layer(id = id, name = name)

    @Test
    fun `reorder arranges layers to match the id order`() {
        val layers = listOf(lyr("a"), lyr("b"), lyr("c"))
        assertEquals(listOf("c", "a", "b"), LayerListOps.reorder(layers, listOf("c", "a", "b")).map { it.id })
    }

    @Test
    fun `reorder drops ids not present in the layer list`() {
        val layers = listOf(lyr("a"), lyr("b"))
        assertEquals(listOf("b", "a"), LayerListOps.reorder(layers, listOf("ghost", "b", "a")).map { it.id })
    }

    @Test
    fun `mapLayer transforms only the matching layer`() {
        val layers = listOf(lyr("a"), lyr("b"))
        val out = LayerListOps.mapLayer(layers, "a") { it.copy(opacity = 0.5f) }
        assertEquals(0.5f, out.first { it.id == "a" }.opacity)
        assertEquals(1.0f, out.first { it.id == "b" }.opacity)
    }

    @Test
    fun `mapLayer with an unknown id leaves the list unchanged`() {
        val layers = listOf(lyr("a"), lyr("b"))
        assertEquals(layers, LayerListOps.mapLayer(layers, "ghost") { it.copy(opacity = 0f) })
    }

    @Test
    fun `rename changes only the target layer's name`() {
        val layers = listOf(lyr("a", "Alpha"), lyr("b", "Beta"))
        val out = LayerListOps.rename(layers, "a", "Renamed")
        assertEquals("Renamed", out.first { it.id == "a" }.name)
        assertEquals("Beta", out.first { it.id == "b" }.name)
    }

    @Test
    fun `toggleVisibility flips only the target layer`() {
        val layers = listOf(lyr("a"), lyr("b")) // isVisible defaults true
        val out = LayerListOps.toggleVisibility(layers, "a")
        assertFalse(out.first { it.id == "a" }.isVisible)
        assertTrue(out.first { it.id == "b" }.isVisible)
    }
}
