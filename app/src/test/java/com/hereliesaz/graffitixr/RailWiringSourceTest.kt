package com.hereliesaz.graffitixr

import com.hereliesaz.graffitixr.common.model.EditorMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Ties [RailIntegrityCheck]'s inputs to the real rail rather than to a mirror of it.
 *
 * ConfigureRailItems (MainActivity.kt) reads every sub-item's hostId from [RAIL_ITEM_HOST_ID] via
 * [railHostOf]. This reads the DSL source and fails if a call site restates a hostId as a literal,
 * passes another item's id to railHostOf, or registers an id the enumerator doesn't know — the three
 * ways the hand-written mirror used to drift from what was actually registered.
 */
class RailWiringSourceTest {

    private val source by lazy {
        File("src/main/java/com/hereliesaz/graffitixr/MainActivity.kt").readText()
    }

    private val railBlock by lazy {
        val start = source.indexOf("fun AzNavHostScope.ConfigureRailItems(")
        check(start >= 0) { "ConfigureRailItems not found in MainActivity.kt" }
        source.substring(start)
    }

    @Test
    fun `no hostId is spelled as a literal in the rail DSL`() {
        val literals = Regex("""hostId\s*=\s*"[^"]*"""").findAll(railBlock).map { it.value }.toList()
        assertEquals("hostIds must come from railHostOf(): $literals", emptyList<String>(), literals)
    }

    @Test
    fun `every sub-item takes its own id's host`() {
        val calls = Regex("""id\s*=\s*"([\w.]+)",\s*hostId\s*=\s*railHostOf\("([\w.]+)"\)""")
            .findAll(railBlock).map { it.groupValues[1] to it.groupValues[2] }.toList()
        assertTrue("no rail sub-items found — has the DSL shape changed?", calls.size > 20)
        val mismatched = calls.filter { (id, hostKey) -> id != hostKey }
        assertEquals(emptyList<Pair<String, String>>(), mismatched)
        val unmapped = calls.map { it.first }.filter { it !in RAIL_ITEM_HOST_ID }
        assertEquals("sub-items with no RAIL_ITEM_HOST_ID entry: $unmapped", emptyList<String>(), unmapped)
        // Every hostId call site is one of those matched pairs (none hide behind another shape).
        assertEquals(Regex("""railHostOf\(""").findAll(railBlock).count(), calls.size)
    }

    @Test
    fun `every id the rail registers is known to the enumerator`() {
        val registered = Regex("""\bid\s*=\s*"([\w.]+)"""").findAll(railBlock).map { it.groupValues[1] }.toSet()
        val known = EditorMode.entries.flatMap { enumerateRailItemIds(it) }.toSet()
        assertEquals("rail ids missing from RailIdEnumerator: ${registered - known}", emptySet<String>(), registered - known)
    }
}
