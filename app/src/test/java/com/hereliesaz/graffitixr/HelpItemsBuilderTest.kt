package com.hereliesaz.graffitixr

import com.hereliesaz.graffitixr.common.model.Layer
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HelpItemsBuilderTest {

    private fun strings() = mockk<com.hereliesaz.graffitixr.design.theme.AppStrings>(relaxed = true).also {
        every { it.help } returns mockk(relaxed = true)
    }

    @Test
    fun `result contains every static key`() {
        val map = buildHelpItems(strings(), emptyList())
        listOf(
            "mode.host", "mode.ar", "mode.overlay", "mode.mockup", "mode.trace",
            "target.host", "target.scanModeToggle", "target.create",
            "design.host", "design.addImg", "design.addDraw", "design.addText", "design.wall",
            "project.host", "project.new", "project.save", "project.load", "project.export", "project.settings",
            "tool.light", "tool.lockTrace", "tool.helpMain",
        ).forEach { id ->
            assertTrue("expected key '$id' in helpItems", id in map)
        }
    }

    @Test
    fun `result contains 23 dynamic keys per layer`() {
        val layers = listOf(Layer(id = "L1", name = "one"), Layer(id = "L2", name = "two"))
        val map = buildHelpItems(strings(), layers)
        val perLayerKeys = listOf(
            "layer_", "edit_text_", "size_", "font_", "color_", "kern_",
            "bold_", "italic_", "outline_", "shadow_", "stencil_", "blend_",
            "adj_", "invert_", "balance_", "eraser_", "blur_", "liquify_",
            "dodge_", "burn_", "iso_", "line_", "help_layer_"
        )
        layers.forEach { layer ->
            perLayerKeys.forEach { prefix ->
                assertTrue("expected '$prefix${layer.id}'", "$prefix${layer.id}" in map)
            }
        }
    }

    @Test
    fun `keys are unique`() {
        val layers = (1..5).map { Layer(id = "L$it", name = "n$it") }
        val map = buildHelpItems(strings(), layers)
        assertEquals(map.keys.size, map.keys.toSet().size)
    }

    @Test
    fun `no value is null`() {
        val map = buildHelpItems(strings(), listOf(Layer(id = "x", name = "y")))
        map.entries.forEach { (k, v) -> assertNotNull("null for $k", v) }
    }

    @Test
    fun `empty layer list still returns static map`() {
        val map = buildHelpItems(strings(), emptyList())
        assertFalse(map.isEmpty())
    }
}
