package com.hereliesaz.graffitixr

import com.hereliesaz.graffitixr.common.model.EditorMode
import com.hereliesaz.graffitixr.common.model.Layer
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

    private fun layer(id: String) = Layer(id = id, name = "test")

    @Test
    fun `no rail id is registered twice in any editor mode`() {
        // Two distinct layers exercise the per-layer registration block.
        val layers = listOf(layer("layer-uuid-1"), layer("layer-uuid-2"))

        for (mode in EditorMode.entries) {
            val registrations = enumerateRailItemIdRegistrations(layers, mode)
            val duplicates = registrations.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
            assertEquals(
                "Duplicate rail IDs in mode $mode would crash AzNavRail at runtime: $duplicates",
                emptySet<String>(),
                duplicates,
            )
        }
    }

    @Test
    fun `no rail id collides across layers with distinct ids`() {
        val layers = (1..5).map { layer("uuid-$it") }
        val registrations = enumerateRailItemIdRegistrations(layers, EditorMode.MOCKUP)
        val duplicates = registrations.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        assertEquals(emptySet<String>(), duplicates)
    }
}
