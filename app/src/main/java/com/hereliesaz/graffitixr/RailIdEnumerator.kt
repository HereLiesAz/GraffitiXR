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

    // Modes menu
    ids += listOf(
        "host.modes", "mode.ar", "mode.overlay", "mode.mockup", "mode.trace", "mode.mockup.wall", "mode.trace.freeze"
    )
    if (mode == EditorMode.AR) {
        ids += "target.create"
    }

    // Design menu
    ids += listOf(
        "host.design", "design.addImg", "design.addDraw", "design.addText"
    )

    // Project menu
    ids += listOf(
        "host.project", "proj.new", "proj.save", "proj.load", "proj.settings"
    )

    // Global tools
    ids += "item.help"

    // Sub-design tools
    if (layers.isNotEmpty()) {
        ids += listOf(
            "sub.design.tools",
            "grp.paint", "tool.brush", "tool.eraser",
            "grp.retouch", "tool.blur", "tool.liquify",
            "grp.color", "adj.invert", "adj.balance", "adj.blend",
            "tool.filter"
        )
    }

    // Per-layer
    layers.forEach { layer ->
        ids += listOf(
            "layer.${layer.id}",
            "layer.${layer.id}.edit",
            "layer.${layer.id}.hide",
            "layer.${layer.id}.del"
        )
    }

    return ids
}
