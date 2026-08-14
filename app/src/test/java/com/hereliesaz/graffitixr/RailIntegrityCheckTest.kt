package com.hereliesaz.graffitixr

import com.hereliesaz.graffitixr.common.model.EditorMode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Guards RailIntegrityCheck's (2) hostId check — the one marked FATAL in its class doc. A sub-item
 * whose hostId doesn't match any registered host doesn't crash and doesn't warn on its own (AzNavRail
 * just never renders it); this is the class of bug the project has hit before (a duplicate/mistyped
 * id taking the rail down, or in this case silently orphaning a child instead).
 */
class RailIntegrityCheckTest {

    @Test
    fun `every registered sub-item's hostId is itself a registered id, in every mode`() {
        for (mode in EditorMode.entries) {
            val railIds = enumerateRailItemIds(mode)
            val broken = RAIL_ITEM_HOST_ID.filterKeys { it in railIds }.filterValues { it !in railIds }
            assertEquals(
                "mode $mode has sub-item(s) whose hostId is not itself a registered id — " +
                    "the sub-item would silently fail to render: $broken",
                emptyMap<String, String>(),
                broken,
            )
        }
    }

    @Test
    fun `RailIntegrityCheck verify does not throw for the real rail wiring, in any mode`() {
        for (mode in EditorMode.entries) {
            // A correct rail must pass its own FATAL check regardless of what help/guidance ids are
            // in play — pass empty sets here since (3)/(4)/(5) only log warnings and are exercised
            // by HelpItemsBuilderTest / the guidance-id tests separately.
            RailIntegrityCheck.verify(
                mode = mode,
                helpList = emptyMap(),
                guidanceHighlightIds = emptySet(),
                decoratedIds = emptySet(),
            )
        }
    }
}
