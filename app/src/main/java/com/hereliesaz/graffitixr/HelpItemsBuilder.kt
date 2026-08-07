package com.hereliesaz.graffitixr

import com.hereliesaz.graffitixr.design.theme.AppStrings

/**
 * Builds the helpList map consumed by AzNavRail's azAdvanced(helpList = ...) parameter. Single
 * source of truth for help-overlay text.
 *
 * Keys must match rail-item IDs registered in ConfigureRailItems — [enumerateRailItemIds] is the
 * mirror of that registration, and RailIntegrityCheck warns in debug builds about any key here that
 * has no matching item.
 *
 * It previously documented a second app's toolbox: brush, eraser, blur, liquify, filter, text
 * authoring, stencil generation, dodge/burn, isolate and line-art, under `tool.*` / `adj.*` /
 * `grp.*` ids and a per-layer nested rail. None of those items exist — the painting, stencil,
 * outline-extraction and text pipelines moved to the companion design app (see EditorViewModel's
 * class doc), and no per-layer rail is registered at all. Every one of those entries was help text
 * for a control the user cannot reach, and each logged an orphan warning on every debug launch.
 * What remains is one entry per item the rail actually registers.
 */
internal fun buildHelpItems(strings: AppStrings): Map<String, Any> = mapOf(
    // Open (top-level, replaces the old Design folder)
    "item.open" to strings.help.addImg,

    // Modes menu
    "host.modes" to strings.help.modeHost,
    "mode.ar" to strings.help.ar,
    "mode.overlay" to strings.help.overlay,
    "mode.mockup" to strings.help.mockup,
    "mode.trace" to strings.help.trace,

    // AR tools
    "target.create" to strings.help.create,
    "mode.ar.light" to strings.help.flashlight,
    "mode.ar.lock" to strings.nav.lockInfo,
    "mode.ar.magic" to strings.adj.magicAlign,
    "coop" to strings.nav.coop,
    "coop.host" to strings.nav.hostCoopInfo,
    "coop.join" to strings.nav.joinCoopInfo,
    "coop.leave" to strings.nav.leaveCoopInfo,

    // Overlay tools
    "mode.overlay.light" to strings.help.flashlight,
    "mode.overlay.lock" to strings.nav.lockInfo,

    // Mockup tools
    "mockup.wall" to strings.help.wall,
    "wall.photo" to strings.nav.wallInfo,
    "wall.file" to strings.nav.wallInfo,
    "wall.clear" to strings.nav.wallClearInfo,
    "mode.mockup.lock" to strings.nav.lockInfo,

    // Trace tools
    "mode.trace.freeze" to strings.help.lockTrace,
    "mode.trace.lock" to strings.nav.lockInfo,

    // Design menu
    "host.design" to strings.nav.adjustInfo,
    "design.adjust" to strings.nav.adjustInfo,
    "design.balance" to strings.nav.balanceInfo,
    "design.invert" to strings.nav.invertInfo,

    // Project menu
    "host.project" to strings.help.projectHost,
    "proj.new" to strings.help.newProject,
    "proj.save" to strings.help.saveProject,
    "proj.export" to strings.help.exportImage,
    "proj.load" to strings.help.loadProject,
    "proj.settings" to strings.help.appSettings,

    // Global
    "item.help" to strings.nav.help,
)
