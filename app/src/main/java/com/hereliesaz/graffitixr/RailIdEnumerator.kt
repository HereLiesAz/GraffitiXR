package com.hereliesaz.graffitixr

import com.hereliesaz.graffitixr.common.model.EditorMode
import com.hereliesaz.graffitixr.common.model.Layer

/**
 * Returns the rail-item IDs that ConfigureRailItems registers for the given mode and layer list.
 * Pure function; no Compose. Used by tests (RailIdUniquenessTest) and the debug-only
 * RailIntegrityCheck.
 *
 * Conditional registration in ConfigureRailItems is mirrored here:
 *   - target.host / target.create only registered in AR mode
 *   - design.wall only registered in MOCKUP mode
 *   - target.scanModeToggle / coop.main / coop.join only in AR mode
 *
 * CAVEAT: the per-layer block below emits the UNION of every layer-menu suffix, whereas
 * ConfigureRailItems registers a different suffix subset per layer type (text vs sketch vs
 * image). So this faithfully covers the static/global IDs (where the real "project.host"
 * duplicate crash lived) but does NOT model the per-layer-type branching. A duplicate that
 * exists only inside one layer-type branch would not be caught here. Modeling those branches
 * is a follow-up.
 */
internal fun enumerateRailItemIds(layers: List<Layer>, mode: EditorMode): Set<String> =
    enumerateRailItemIdRegistrations(layers, mode).toSet()

/**
 * Same registrations as [enumerateRailItemIds] but in registration order and WITH
 * duplicates preserved.
 *
 * AzNavRail throws IllegalArgumentException at runtime the moment an ID is registered
 * twice — a duplicate "project.host" once took the whole app down on launch. The Set form
 * above hides such collisions; this list form lets a unit test (RailIdUniquenessTest) catch
 * them at build time instead of in users' hands.
 */
internal fun enumerateRailItemIdRegistrations(layers: List<Layer>, mode: EditorMode): List<String> {
    val ids = mutableListOf<String>()

    // Mode menu
    ids += listOf(
        "mode.host", "mode.ar", "mode.overlay", "mode.mockup", "mode.trace",
        "wearable.main",
    )
    if (mode == EditorMode.AR) {
        ids += listOf("target.scanModeToggle", "coop.main", "coop.join")
    }

    // Target menu (AR only)
    if (mode == EditorMode.AR) {
        ids += listOf("target.host", "target.create")
    }

    // Design menu
    ids += listOf(
        "design.host", "design.addImg", "design.addDraw", "design.addText",
    )
    if (mode == EditorMode.MOCKUP) {
        ids += "design.wall"
    }

    // Project menu
    ids += listOf(
        "project.host.main", "project.new", "project.save", "project.load",
        "project.export", "project.settings",
    )

    // Global tools
    ids += listOf("tool.light", "tool.lockTrace", "tool.helpMain")

    // Per-layer
    layers.forEach { layer ->
        ids += listOf(
            layerId(layer),
            layerId(layer, "editText"),
            layerId(layer, "size.brush"),
            layerId(layer, "size.text"),
            layerId(layer, "font"),
            layerId(layer, "color"),
            layerId(layer, "kern"),
            layerId(layer, "bold"),
            layerId(layer, "italic"),
            layerId(layer, "outline"),
            layerId(layer, "shadow"),
            layerId(layer, "stencil"),
            layerId(layer, "blend"),
            layerId(layer, "adj"),
            layerId(layer, "invert"),
            layerId(layer, "balance"),
            layerId(layer, "eraser"),
            layerId(layer, "blur"),
            layerId(layer, "liquify"),
            layerId(layer, "dodge"),
            layerId(layer, "burn"),
            layerId(layer, "iso"),
            layerId(layer, "line"),
            "${layerId(layer)}.help",
        )
    }

    return ids
}
