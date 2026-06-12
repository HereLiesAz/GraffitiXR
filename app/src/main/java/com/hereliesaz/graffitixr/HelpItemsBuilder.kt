package com.hereliesaz.graffitixr

import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.design.theme.AppStrings

/**
 * Builds the helpList map consumed by AzNavRail's azAdvanced(helpList = ...)
 * parameter. Single source of truth for help-overlay text.
 *
 * Per the AzNavRail v8.11 HelpOverlay scoping rule: when a nested rail is
 * open, only its `nestedRailItems` are shown; otherwise all main-rail items
 * (top-level + host sub-items) are shown. So this map can safely contain:
 *   - All main-rail items (top-level + host sub-items) — surfaced on main Help
 *   - Per-layer popup items — surfaced only when that layer's nested rail is open
 *
 * Keys must match rail-item IDs registered in ConfigureRailItems.
 */
internal fun buildHelpItems(strings: AppStrings, layers: List<Layer>): Map<String, Any> {
    val base = mutableMapOf<String, Any>(
        // Modes menu
        "host.modes" to strings.help.modeHost,
        "mode.ar" to strings.help.ar,
        "mode.overlay" to strings.help.overlay,
        "mode.mockup" to strings.help.mockup,
        "mode.trace" to strings.help.trace,
        "target.create" to strings.help.create,
        "mode.mockup.wall" to strings.help.wall,

        // Design menu
        "host.design" to strings.help.designHost,
        "design.addImg" to strings.help.addImg,
        "design.addDraw" to strings.help.addDraw,
        "design.addText" to strings.help.addText,
        "mode.trace.freeze" to strings.help.lockTrace,

        // Tools menu
        "sub.design.tools" to strings.help.designHost, // Use design host help for tools parent
        "grp.paint" to strings.help.addDraw,
        "tool.brush" to strings.help.size,
        "tool.eraser" to strings.help.eraser,
        "grp.retouch" to strings.help.blur,
        "tool.blur" to strings.help.blur,
        "tool.liquify" to strings.help.liquify,
        "grp.color" to strings.help.adj,
        "adj.invert" to strings.help.invert,
        "adj.balance" to strings.help.balance,
        "adj.blend" to strings.help.blend,
        "tool.filter" to strings.help.adj,

        // Project menu
        "host.project" to strings.help.projectHost,
        "proj.new" to strings.help.newProject,
        "proj.save" to strings.help.saveProject,
        "proj.load" to strings.help.loadProject,
        "proj.settings" to strings.help.appSettings,

        // Global
        "item.help" to strings.nav.help
    )

    layers.forEach { layer ->
        base[layerId(layer)] = strings.help.layer(layer.name)
        // Tool category folders (host items) inside the per-layer nested rail.
        base[layerId(layer, "grp.paint")] = strings.help.addDraw
        base[layerId(layer, "grp.retouch")] = strings.help.blur
        base[layerId(layer, "grp.adjust")] = strings.help.adj
        base[layerId(layer, "grp.effects")] = strings.help.stencilGen
        base[layerId(layer, "editText")] = strings.help.editText
        base[layerId(layer, "size.brush")] = strings.help.size
        base[layerId(layer, "size.text")] = strings.help.size
        base[layerId(layer, "font")] = strings.help.font
        base[layerId(layer, "color")] = strings.help.color
        base[layerId(layer, "kern")] = strings.help.kern
        base[layerId(layer, "bold")] = strings.help.bold
        base[layerId(layer, "italic")] = strings.help.italic
        base[layerId(layer, "outline")] = strings.help.outline
        base[layerId(layer, "shadow")] = strings.help.shadow
        base[layerId(layer, "stencil")] = strings.help.stencilGen
        base[layerId(layer, "blend")] = strings.help.blend
        base[layerId(layer, "adj")] = strings.help.adj
        base[layerId(layer, "invert")] = strings.help.invert
        base[layerId(layer, "balance")] = strings.help.balance
        base[layerId(layer, "eraser")] = strings.help.eraser
        base[layerId(layer, "blur")] = strings.help.blur
        base[layerId(layer, "liquify")] = strings.help.liquify
        base[layerId(layer, "dodge")] = strings.help.dodge
        base[layerId(layer, "burn")] = strings.help.burn
        base[layerId(layer, "iso")] = strings.help.iso
        base[layerId(layer, "line")] = strings.help.line
    }

    return base
}
