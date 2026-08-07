package com.hereliesaz.graffitixr

import com.hereliesaz.graffitixr.common.model.EditorMode
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
        every { it.nav } returns mockk(relaxed = true)
    }

    @Test
    fun `every help key names a rail item that is actually registered`() {
        // The contract RailIntegrityCheck enforces at runtime, pinned at build time. The builder
        // used to carry ~30 keys for a toolbox this app does not have (brush, eraser, liquify,
        // stencil, text authoring, per-layer nested rails) — help text for controls nobody can
        // reach. The union across modes is the universe of legal keys.
        val allRailIds = EditorMode.entries.flatMap { enumerateRailItemIds(it) }.toSet()
        val orphans = buildHelpItems(strings()).keys - allRailIds
        assertEquals("help keys with no matching rail item", emptySet<String>(), orphans)
    }

    @Test
    fun `every registered rail item has help text`() {
        // The other direction: an item with no entry is silently absent from the Help overlay.
        val map = buildHelpItems(strings())
        val allRailIds = EditorMode.entries.flatMap { enumerateRailItemIds(it) }.toSet()
        assertEquals("rail items with no help entry", emptySet<String>(), allRailIds - map.keys)
    }

    @Test
    fun `result contains the static main-rail keys`() {
        val map = buildHelpItems(strings())
        listOf(
            "item.open",
            "host.modes", "mode.ar", "mode.overlay", "mode.mockup", "mode.trace",
            "target.create", "mockup.wall", "mode.trace.freeze",
            "host.project", "proj.new", "proj.save", "proj.export", "proj.load", "proj.settings",
            "proj.extensions",
            "item.help",
        ).forEach { id ->
            assertTrue("expected key '$id' in helpItems", id in map)
        }
    }

    @Test
    fun `no value is null`() {
        buildHelpItems(strings()).entries.forEach { (k, v) -> assertNotNull("null for $k", v) }
    }

    @Test
    fun `map is not empty`() {
        assertFalse(buildHelpItems(strings()).isEmpty())
    }
}
