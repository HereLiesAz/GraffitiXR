package com.hereliesaz.graffitixr.feature.ar

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeStateOwnerTest {
    @Test fun `nothing is owned until a loader finishes`() {
        val o = NativeStateOwner()
        assertFalse(o.owns("A"))
        o.markLoaded("A", stillCurrent = true)
        assertTrue(o.owns("A"))
    }

    @Test fun `a switch drops ownership before native is touched`() {
        val o = NativeStateOwner()
        o.markLoaded("A", true)
        o.beginSwitch("B")
        assertFalse("autosave in the load window must not write A's native state into B", o.owns("A"))
        assertFalse(o.owns("B"))
        o.markLoaded("B", true)
        assertTrue(o.owns("B"))
        assertFalse(o.owns("A"))
    }

    @Test fun `re-emission of the same project keeps ownership`() {
        val o = NativeStateOwner()
        o.markLoaded("A", true)
        o.beginSwitch("A")
        assertTrue(o.owns("A"))
    }

    @Test fun `a slow loader for a project already left does not claim ownership`() {
        val o = NativeStateOwner()
        o.beginSwitch("A")
        o.beginSwitch("B")
        o.markLoaded("A", stillCurrent = false)
        assertFalse(o.owns("A"))
        assertFalse(o.owns("B"))
    }

    @Test fun `clear releases ownership`() {
        val o = NativeStateOwner()
        o.markLoaded("A", true)
        o.clear()
        assertFalse(o.owns("A"))
    }
}
