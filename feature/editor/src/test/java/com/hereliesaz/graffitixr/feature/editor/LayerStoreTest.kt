package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import androidx.compose.ui.unit.IntSize
import com.hereliesaz.graffitixr.common.model.Tool
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LayerStoreTest {

    private val store = LayerStore()

    private fun bmp(): Bitmap = mockk(relaxed = true)
    private fun stroke() = StrokeCommand(
        path = emptyList(),
        canvasSize = IntSize(1, 1),
        tool = Tool.NONE,
        brushSize = 1f,
        brushColor = 0,
        intensity = 0.5f,
    )

    @Test
    fun `base returns null for an unknown layer`() {
        assertNull(store.base("ghost"))
    }

    @Test
    fun `putBase then base round-trips the same instance`() {
        val b = bmp()
        store.putBase("a", b)
        assertSame(b, store.base("a"))
    }

    @Test
    fun `strokes returns empty for an unknown layer`() {
        assertTrue(store.strokes("ghost").isEmpty())
    }

    @Test
    fun `addStroke creates the list and appends in order`() {
        val s1 = stroke()
        val s2 = stroke()
        store.addStroke("a", s1)
        store.addStroke("a", s2)
        assertEquals(listOf(s1, s2), store.strokes("a"))
    }

    @Test
    fun `initStrokes resets the stroke list to empty`() {
        store.addStroke("a", stroke())
        store.initStrokes("a")
        assertTrue(store.strokes("a").isEmpty())
    }

    @Test
    fun `removeLastStroke returns false for an unknown layer`() {
        assertFalse(store.removeLastStroke("ghost"))
    }

    @Test
    fun `removeLastStroke drops the most recent stroke and returns true`() {
        val s1 = stroke()
        val s2 = stroke()
        store.addStroke("a", s1)
        store.addStroke("a", s2)
        assertTrue(store.removeLastStroke("a"))
        assertEquals(listOf(s1), store.strokes("a"))
    }

    @Test
    fun `removeLastStroke on an empty but present list returns true and is a no-op`() {
        store.initStrokes("a") // present, empty
        assertTrue(store.removeLastStroke("a"))
        assertTrue(store.strokes("a").isEmpty())
    }

    @Test
    fun `remove drops both base and strokes for the layer`() {
        store.putBase("a", bmp())
        store.addStroke("a", stroke())
        store.remove("a")
        assertNull(store.base("a"))
        assertTrue(store.strokes("a").isEmpty())
    }

    @Test
    fun `clear empties every layer's caches`() {
        store.putBase("a", bmp())
        store.addStroke("b", stroke())
        store.clear()
        assertNull(store.base("a"))
        assertTrue(store.strokes("b").isEmpty())
    }
}
