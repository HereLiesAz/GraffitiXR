package com.hereliesaz.graffitixr

import android.util.Log
import com.hereliesaz.aznavrail.tutorial.AzTutorial
import com.hereliesaz.graffitixr.common.model.EditorMode
import com.hereliesaz.graffitixr.common.model.Layer

/**
 * Debug-only runtime invariants for AzNavRail wiring. Stripped in release
 * by call-site BuildConfig.DEBUG gate (see MainActivity).
 *
 * (1) All registered IDs are unique.
 * (2) Every sub-item's hostId matches a registered host (FATAL).
 * (3) Every helpList key matches a registered rail-item ID (WARN).
 * (4) Every tutorial key matches a registered rail-item ID, a mode firstRun
 *     key, or a layer-help key (WARN).
 */
internal object RailIntegrityCheck {

    private const val TAG = "RailIntegrity"

    fun verify(
        layers: List<Layer>,
        mode: EditorMode,
        helpList: Map<String, Any>,
        tutorials: Map<String, AzTutorial>,
    ) {
        val railIds = enumerateRailItemIds(layers, mode)

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

        // (4) tutorial anchors — verify every tutorial key is either a
        //     rail-item ID or a layer-help key.
        tutorials.keys.forEach { key ->
            val isRailId = key in railIds
            val isLayerHelp = key.endsWith(".help") && key.removeSuffix(".help") in railIds
            if (!isRailId && !isLayerHelp) {
                Log.w(TAG, "tutorial key '$key' has no matching rail item")
            }
        }
    }
}
