package com.hereliesaz.graffitixr.design.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KnobProgressTest {

    @Test
    fun `accessibility progress is bracketed by start and finished`() {
        val events = mutableListOf<String>()
        val changed = applyKnobProgress(
            target = 0.75f,
            current = 0.25f,
            range = 0f..1f,
            onStart = { events += "start" },
            onValueChange = { events += "change:$it" },
            onFinished = { events += "finished" },
        )
        assertTrue(changed)
        assertEquals(listOf("start", "change:0.75", "finished"), events)
    }

    @Test
    fun `target is clamped to the range before it is applied`() {
        var applied = Float.NaN
        applyKnobProgress(9f, 0.5f, 0f..1f, null, { applied = it }, null)
        assertEquals(1f, applied, 0f)
        applyKnobProgress(-9f, 0.5f, 0f..1f, null, { applied = it }, null)
        assertEquals(0f, applied, 0f)
    }

    @Test
    fun `an unchanged value reports false and fires nothing`() {
        var calls = 0
        val changed = applyKnobProgress(
            target = 0.5f,
            current = 0.5f,
            range = 0f..1f,
            onStart = { calls++ },
            onValueChange = { calls++ },
            onFinished = { calls++ },
        )
        assertFalse(changed)
        assertEquals(0, calls)
    }

    @Test
    fun `a target that clamps to the current value is a no-op`() {
        var calls = 0
        val changed = applyKnobProgress(5f, 1f, 0f..1f, { calls++ }, { calls++ }, { calls++ })
        assertFalse(changed)
        assertEquals(0, calls)
    }
}
