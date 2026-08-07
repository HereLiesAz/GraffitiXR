package com.hereliesaz.graffitixr

import com.hereliesaz.graffitixr.common.model.EditorMode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Guards the duplicate-rail-ID crash class. AzNavRail throws IllegalArgumentException
 * ("Duplicate ID detected") the instant ConfigureRailItems registers the same ID twice,
 * crashing the app on launch (this happened once with "project.host"). If a duplicate is
 * ever reintroduced into [enumerateRailItemIdRegistrations] — which mirrors ConfigureRailItems —
 * this test fails at build time instead.
 */
class RailIdUniquenessTest {

    @Test
    fun `no rail id is registered twice in any editor mode`() {
        for (mode in EditorMode.entries) {
            val registrations = enumerateRailItemIdRegistrations(mode)
            val duplicates = registrations.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
            assertEquals(
                "Duplicate rail IDs in mode $mode would crash AzNavRail at runtime: $duplicates",
                emptySet<String>(),
                duplicates,
            )
        }
    }

    @Test
    fun `every mode registers the always-on entries exactly once`() {
        // The navigation spine: Open, the modes host with its four mode entries, the project host
        // and Help. A mode-gated tool leaking into the always-on set would show up here as a
        // registration in a mode that should not have it.
        val alwaysOn = setOf(
            "item.open", "host.modes", "mode.ar", "mode.overlay", "mode.mockup", "mode.trace",
            "host.project", "proj.new", "proj.save", "proj.export", "proj.load", "proj.settings",
            "item.help",
        )
        for (mode in EditorMode.entries) {
            val registrations = enumerateRailItemIdRegistrations(mode)
            assertEquals(
                "mode $mode is missing always-on rail entries",
                emptySet<String>(),
                alwaysOn - registrations.toSet(),
            )
        }
    }
}
