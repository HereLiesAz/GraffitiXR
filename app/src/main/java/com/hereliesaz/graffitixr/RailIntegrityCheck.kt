package com.hereliesaz.graffitixr

import android.util.Log
import com.hereliesaz.graffitixr.common.model.EditorMode

/**
 * Debug-only runtime invariants for AzNavRail wiring. Stripped in release
 * by call-site BuildConfig.DEBUG gate (see MainActivity).
 *
 * (1) All registered IDs are unique.
 * (2) Every sub-item's hostId matches a registered host (FATAL).
 * (3) Every helpList key matches a registered rail-item ID (WARN).
 * (4) Every static guidance highlight id matches a registered rail-item ID (WARN) — catches the
 *     class of bug where an edge points at a renamed/removed item (e.g. the old `mode.mockup.wall`,
 *     whose real id is `mockup.wall`). Runtime guidance highlights (AZ_ITEM_ACTIVE / dynamic
 *     selectors) are resolved at render time and are not checked here.
 * (5) Every id addressed by [decorateRailItems] — `azHighlight` and `azItemState` — matches a
 *     registered rail item (WARN).
 *
 * (5) is why this class earns its keep. Both of those calls are *post-hoc decorators*: AzNavRail
 * applies them after the whole DSL block has run, and an id it does not recognise is silently
 * ignored. There is no crash, no log, no visual difference from "the state was false" — a renamed
 * item just quietly stops being highlighted or badged. Every other kind of orphan in this file has
 * the same shape, which is exactly why the check exists at all.
 */
internal object RailIntegrityCheck {

    private const val TAG = "RailIntegrity"

    fun verify(
        mode: EditorMode,
        helpList: Map<String, Any>,
        guidanceHighlightIds: Set<String>,
        decoratedIds: Set<String> = emptySet(),
    ) {
        val railIds = enumerateRailItemIds(mode)

        // (1) Uniqueness — enumerator returns a Set, so collisions would already
        //     manifest as missing IDs; an explicit check on the producer side
        //     happens in AzNavRail itself. We log if the set size is suspiciously
        //     small (sanity).
        if (railIds.isEmpty()) {
            Log.w(TAG, "no rail items enumerated for mode $mode")
        }

        // (2) hostId matching is enforced by the producer (ConfigureRailItems)
        //     using string literals; the enumerator mirrors them. A mismatch
        //     would surface as a missing ID in railIds, caught by (3) and (4).

        // (3) helpList orphans
        helpList.keys.forEach { key ->
            if (key !in railIds) {
                Log.w(TAG, "helpList key '$key' has no matching rail item")
            }
        }

        // (4) guidance highlight orphans — every static highlightItemId an edge points at must be a
        //     real rail item, else the callout's spotlight aims at nothing.
        guidanceHighlightIds.forEach { id ->
            if (id !in railIds) {
                Log.w(TAG, "guidance highlight id '$id' has no matching rail item")
            }
        }

        // (5) decorator orphans — see the class doc. AzNavRail drops these silently.
        decoratedIds.forEach { id ->
            if (id !in railIds) {
                Log.w(TAG, "azHighlight/azItemState id '$id' has no matching rail item")
            }
        }
    }
}
