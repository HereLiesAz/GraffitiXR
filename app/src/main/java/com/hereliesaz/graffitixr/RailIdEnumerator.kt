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
 *   - coop / coop.host / coop.join only registered in AR mode (coop.leave is
 *     additionally gated on an active session, so it is omitted here)
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
        "host.modes", "mode.ar", "mode.overlay", "mode.mockup", "mode.trace", "mockup.wall", "mode.trace.freeze"
    )
    if (mode == EditorMode.AR) {
        ids += "target.create"
        ids += "mode.ar.light"
        ids += listOf("coop", "coop.host", "coop.join")
    }
    if (mode == EditorMode.OVERLAY) {
        ids += "mode.overlay.light"
    }

    // Design menu
    ids += listOf(
        "host.design", "design.addImg", "design.addDraw", "design.addText"
    )

    // Project menu
    ids += listOf(
        "host.project", "proj.new", "proj.save", "proj.export", "proj.load", "proj.settings", "proj.extensions"
    )

    // Global tools
    ids += "item.help"

    // Per-layer: the layer item, its tool-category folders, the union of every
    // layer-type tool suffix (see CAVEAT above), and the per-layer help item.
    layers.forEach { layer ->
        ids += layerId(layer)
        ids += listOf(
            "grp.text", "grp.retouch", "grp.adjust", "grp.effects",
        ).map { layerId(layer, it) }
        ids += listOf(
            "editText", "font", "size.text", "color", "kern", "bold", "italic",
            "outline", "shadow", "size.brush", "eraser", "blur", "liquify",
            "dodge", "burn", "adj", "balance", "blend", "invert", "stencil",
            "iso", "line", "magic", "edges",
        ).map { layerId(layer, it) }
        ids += "${layerId(layer)}.help"
    }

    return ids
}
