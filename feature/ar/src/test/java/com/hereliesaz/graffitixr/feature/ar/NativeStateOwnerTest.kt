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

    // --- finishLoad: every loader exit settles ownership -------------------------------------------

    @Test fun `A to B to A race - the unchanged early return re-claims ownership`() {
        val o = NativeStateOwner()
        // A fully loaded: native holds A.
        o.beginSwitch("A")
        o.finishLoad("A", NativeStateOwner.LoadExit.LOADED, stillCurrent = true, othersInFlight = false)
        assertTrue(o.owns("A"))
        // B becomes current: the collector drops ownership before B's loader touches native.
        o.beginSwitch("B")
        assertFalse(o.owns("A"))
        // A comes back before B's loader changed anything, so A's loader finds loadedDoodle == A
        // and returns early. Native still holds A's state; ownership must come back or ARCore
        // wall-map autosave (which requires owns(A)) is disabled for the rest of the session.
        o.beginSwitch("A")
        assertFalse(o.owns("A"))
        o.finishLoad("A", NativeStateOwner.LoadExit.UNCHANGED, stillCurrent = true, othersInFlight = false)
        assertTrue("early-return path must mark ownership", o.owns("A"))
    }

    @Test fun `an unchanged early return does not claim while another loader is still rewriting native`() {
        val o = NativeStateOwner()
        o.beginSwitch("B")
        o.finishLoad("B", NativeStateOwner.LoadExit.UNCHANGED, stillCurrent = true, othersInFlight = true)
        assertFalse(o.owns("B"))
    }

    @Test fun `an unchanged early return for a project already left does not claim`() {
        val o = NativeStateOwner()
        o.finishLoad("A", NativeStateOwner.LoadExit.UNCHANGED, stillCurrent = false, othersInFlight = false)
        assertFalse(o.owns("A"))
    }

    @Test fun `an abandoned load releases ownership`() {
        val o = NativeStateOwner()
        o.markLoaded("A", true)
        o.finishLoad("A", NativeStateOwner.LoadExit.ABANDONED, stillCurrent = true, othersInFlight = false)
        assertFalse(o.owns("A"))
    }

    @Test fun `a loaded exit claims only while still current`() {
        val o = NativeStateOwner()
        o.finishLoad("A", NativeStateOwner.LoadExit.LOADED, stillCurrent = false, othersInFlight = false)
        assertFalse(o.owns("A"))
        o.finishLoad("A", NativeStateOwner.LoadExit.LOADED, stillCurrent = true, othersInFlight = true)
        assertTrue(o.owns("A"))
    }
}
