package com.hereliesaz.graffitixr

import com.hereliesaz.graffitixr.common.model.EditorMode

/**
 * Returns the rail-item IDs that ConfigureRailItems registers for the given mode. Pure function; no
 * Compose. Used by tests (RailIdUniquenessTest) and the debug-only RailIntegrityCheck.
 *
 * Conditional registration in ConfigureRailItems is mirrored here. Each mode's sub-host is always
 * registered (it is the navigation entry); that mode's tools are registered only while it is active.
 * The two items gated on more than the mode — `coop.leave` (needs an active session) and
 * `wall.clear` (needs a wall photo) — are included, because this set is used as the *universe* of
 * legal IDs, and omitting a real item would make a correct help/guidance key look like an orphan.
 *
 * This used to also emit a per-layer block: a `layerId(layer)` entry per layer plus the union of
 * every layer-menu tool suffix (editText / font / eraser / blur / liquify / stencil / dodge / burn
 * and the rest). ConfigureRailItems registers none of them. Those tools belong to the companion
 * design app — the painting, stencil, outline and text-authoring pipelines were removed from this
 * one (see EditorViewModel's class doc) — so the enumerator was describing a rail that had not
 * existed for some time, and the integrity check it feeds could not tell a real orphan from the
 * fiction.
 */
internal fun enumerateRailItemIds(mode: EditorMode): Set<String> =
    enumerateRailItemIdRegistrations(mode).toSet()

/**
 * Same registrations as [enumerateRailItemIds] but in registration order and WITH duplicates
 * preserved.
 *
 * AzNavRail throws IllegalArgumentException at runtime the moment an ID is registered twice — a
 * duplicate "project.host" once took the whole app down on launch. The Set form above hides such
 * collisions; this list form lets a unit test (RailIdUniquenessTest) catch them at build time.
 */
internal fun enumerateRailItemIdRegistrations(mode: EditorMode): List<String> {
    val ids = mutableListOf<String>()

    // Open (a sub-item of the top-level Design folder).
    ids += "item.open"

    // Modes menu. Every mode's sub-host is always registered (they are the navigation entries);
    // each mode's TOOLS are registered only while that mode is active.
    ids += listOf("host.modes", "mode.ar", "mode.overlay", "mode.mockup", "mode.trace")
    if (mode == EditorMode.AR) {
        ids += listOf("target.create", "mode.ar.light", "mode.ar.lock", "mode.ar.magic")
        ids += listOf("coop", "coop.host", "coop.join", "coop.leave")
    }
    if (mode == EditorMode.OVERLAY) {
        ids += listOf("mode.overlay.light", "mode.overlay.lock")
    }
    if (mode == EditorMode.MOCKUP) {
        ids += listOf("mockup.wall", "wall.photo", "wall.file", "wall.clear", "mode.mockup.lock")
    }
    if (mode == EditorMode.TRACE) {
        ids += listOf("mode.trace.freeze", "mode.trace.lock")
    }

    // Design menu (the top-level workspace entry, plus its per-design controls: the adjust/colour
    // panels and invert)
    ids += listOf(
        "mode.design", "host.design", "design.adjust", "design.balance", "design.invert",
        "design.outline", "design.isolate",
    )

    // Project menu
    ids += listOf(
        "host.project", "proj.new", "proj.save", "proj.export", "proj.load", "proj.settings",
    )

    // Global tools
    ids += "item.help"

    return ids
}
