package com.hereliesaz.graffitixr

import com.hereliesaz.graffitixr.common.model.EditorMode

/**
 * Returns the rail-item IDs that ConfigureRailItems registers for the given mode. Pure function; no
 * Compose. Used by tests (RailIdUniquenessTest) and the debug-only RailIntegrityCheck.
 *
 * Conditional registration in ConfigureRailItems is mirrored here. Each mode's sub-host is
 * registered whenever its mode is reachable (it is the navigation entry); that mode's tools are
 * registered only while it is active. `mode.ar` is the one sub-host that is NOT unconditional even
 * by that looser standard — it is gated on ARCore availability (`showArModeEntry` in
 * ConfigureRailItems) and can be absent from the rail entirely on a resolved-unavailable device.
 * That's harmless today only because a separate effect routes the user out of AR mode before
 * anything could address the missing id — this enumerator still includes it unconditionally
 * because the set is meant as the *universe* of legal IDs across all reachable devices, and code
 * addressing `mode.ar` elsewhere shouldn't look like it's referencing an orphan. The other two
 * items gated on more than the mode — `coop.leave` (needs an active session) and `wall.clear`
 * (needs a wall photo) — are included for the same reason.
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

/**
 * The `hostId` every sub-item/sub-host-item in ConfigureRailItems declares, keyed by the child's own
 * id. Mirrors the DSL's `hostId = "..."` argument exactly — a top-level `azRailItem`/`azRailHostItem`
 * (no `hostId`) simply has no entry here. Mode-independent: a child's declared parent doesn't change
 * across modes, only whether the child is registered at all does (see [enumerateRailItemIdRegistrations]).
 *
 * Feeds [RailIntegrityCheck]'s FATAL hostId-validity check: every entry's value must itself be a
 * registered id whenever the entry's key is registered, or the sub-item silently fails to render
 * (AzNavRail resolves an unrecognised `hostId` to nothing, with no crash and no log line) — the same
 * silent-orphan class of bug this whole enumerator exists to catch for help/guidance ids.
 */
internal val RAIL_ITEM_HOST_ID: Map<String, String> = mapOf(
    "item.open" to "mode.design",
    "host.design" to "mode.design",
    "design.adjust" to "host.design",
    "design.balance" to "host.design",
    "design.invert" to "host.design",
    "design.outline" to "host.design",
    "design.isolate" to "host.design",
    "mode.ar" to "host.modes",
    "target.create" to "mode.ar",
    "mode.ar.light" to "mode.ar",
    "mode.ar.lock" to "mode.ar",
    "mode.ar.magic" to "mode.ar",
    "coop" to "mode.ar",
    "coop.host" to "coop",
    "coop.join" to "coop",
    "coop.leave" to "coop",
    "mode.overlay" to "host.modes",
    "mode.overlay.light" to "mode.overlay",
    "mode.overlay.lock" to "mode.overlay",
    "mode.mockup" to "host.modes",
    "mockup.wall" to "mode.mockup",
    "wall.photo" to "mockup.wall",
    "wall.file" to "mockup.wall",
    "wall.clear" to "mockup.wall",
    "mode.mockup.lock" to "mode.mockup",
    "mode.trace" to "host.modes",
    "mode.trace.freeze" to "mode.trace",
    "mode.trace.lock" to "mode.trace",
    "proj.new" to "host.project",
    "proj.save" to "host.project",
    "proj.export" to "host.project",
    "proj.load" to "host.project",
    "proj.settings" to "host.project",
)
