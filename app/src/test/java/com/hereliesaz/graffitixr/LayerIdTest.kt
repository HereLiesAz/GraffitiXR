package com.hereliesaz.graffitixr

import com.hereliesaz.graffitixr.common.model.Layer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LayerIdTest {

    private fun layer(id: String) = Layer(id = id, name = "test")

    @Test
    fun `layerId without tool returns layer dot prefix plus id`() {
        assertEquals("layer.abc123", layerId(layer("abc123"), null))
    }

    @Test
    fun `layerId with tool appends tool segment`() {
        assertEquals("layer.abc123.tool.size", layerId(layer("abc123"), "size"))
    }

    @Test
    fun `sanitize passes through ascii alphanumeric underscore hyphen`() {
        assertEquals("Abc-123_xyz", sanitize("Abc-123_xyz"))
    }

    @Test
    fun `sanitize replaces non-ascii with stable hash prefix`() {
        val out = sanitize("layer 🎨")
        assertTrue("expected hash prefix, got $out", out.startsWith("h"))
        // Stable across calls
        assertEquals(out, sanitize("layer 🎨"))
    }

    @Test
    fun `sanitize produces different hashes for different inputs`() {
        assertNotEquals(sanitize("layer 🎨"), sanitize("layer ✏️"))
    }

    @Test
    fun `layerId is deterministic`() {
        val a = layerId(layer("foo bar"), "size")
        val b = layerId(layer("foo bar"), "size")
        assertEquals(a, b)
    }
}
