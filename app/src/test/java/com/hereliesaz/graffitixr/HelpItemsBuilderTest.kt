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
    fun `result contains top-level main rail keys only`() {
        val map = buildHelpItems(strings(), emptyList())
        // Per the AzNavRail v8.10 HelpOverlay scope: only top-level rail items
        // get help cards. Host sub-items (mode.ar, design.addImg, project.new,
        // etc.) are inline expansions, not rail items, so they are intentionally
        // absent from the helpList.
        listOf(
            "mode.host",
            "target.host",
            "design.host",
            "project.host.main",
            "tool.light",
            "tool.lockTrace",
            "tool.helpMain",
        ).forEach { id ->
            assertTrue("expected key '$id' in helpItems", id in map)
        }
        listOf(
            "mode.ar", "mode.overlay", "mode.mockup", "mode.trace",
            "target.scanModeToggle", "target.create",
            "design.addImg", "design.addDraw", "design.addText", "design.wall",
            "project.new", "project.save", "project.load", "project.export", "project.settings",
            "wearable.main",
        ).forEach { id ->
            assertFalse("did not expect host sub-item '$id' in helpItems", id in map)
        }
    }

    @Test
    fun `result contains every dynamic key per layer using layerId convention`() {
        val layers = listOf(Layer(id = "L1", name = "one"), Layer(id = "L2", name = "two"))
        val map = buildHelpItems(strings(), layers)
        val toolKeys = listOf(
            null, "editText", "size.brush", "size.text", "font", "color", "kern",
            "bold", "italic", "outline", "shadow", "stencil", "blend",
            "adj", "invert", "balance", "eraser", "blur", "liquify",
            "dodge", "burn", "iso", "line", "help",
        )
        layers.forEach { layer ->
            toolKeys.forEach { tool ->
                val expected = when (tool) {
                    null -> layerId(layer)
                    "help" -> "${layerId(layer)}.help"
                    else -> layerId(layer, tool)
                }
                assertTrue("expected '$expected' in map", expected in map)
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
