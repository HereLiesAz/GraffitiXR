package com.hereliesaz.graffitixr

import com.hereliesaz.graffitixr.common.model.EditorMode
import com.hereliesaz.graffitixr.common.model.Layer

/**
 * Returns the rail-item IDs that ConfigureRailItems registers for the given mode and layer list.
 * Pure function; no Compose. Used by tests (RailIdUniquenessTest) and the debug-only
 * RailIntegrityCheck.
 *
 * Conditional registration in ConfigureRailItems is mirrored here. Each mode's sub-host is always
 * registered (it is the navigation entry); that mode's tools are registered only while it is active:
 *   - target.create / mode.ar.light / mode.ar.lock / coop / coop.host / coop.join in AR mode
 *     (coop.leave is additionally gated on an active session, so it is omitted here)
 *   - mode.overlay.light / mode.overlay.lock in OVERLAY mode
 *   - mockup.wall / wall.photo / wall.file / mode.mockup.lock in MOCKUP mode
 *     (wall.clear is additionally gated on a wall photo existing, so it is omitted here)
 *   - mode.trace.freeze / mode.trace.lock in TRACE mode
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

    // Modes menu. Every mode's sub-host is always registered (they are the navigation entries); each
    // mode's TOOLS are registered only while that mode is active.
    ids += listOf("host.modes", "mode.ar", "mode.overlay", "mode.mockup", "mode.trace")
    if (mode == EditorMode.AR) {
        ids += "target.create"
        ids += "mode.ar.light"
        ids += "mode.ar.lock"
        ids += listOf("coop", "coop.host", "coop.join")
    }
    if (mode == EditorMode.OVERLAY) {
        ids += "mode.overlay.light"
        ids += "mode.overlay.lock"
    }
    if (mode == EditorMode.MOCKUP) {
        // wall.clear is additionally gated on a wall photo existing, so it is omitted here for the
        // same reason as coop.leave.
        ids += listOf("mockup.wall", "wall.photo", "wall.file", "mode.mockup.lock")
    }
    if (mode == EditorMode.TRACE) {
        ids += listOf("mode.trace.freeze", "mode.trace.lock")
    }

    // Open (top-level, replaces the old Design folder — no sub-items)
    ids += "item.open"

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
