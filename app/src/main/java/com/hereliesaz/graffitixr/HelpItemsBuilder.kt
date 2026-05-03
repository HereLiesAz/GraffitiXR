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
        // Top-level main rail items
        "mode.host" to strings.help.modeHost,
        "target.host" to strings.help.targetHost,
        "design.host" to strings.help.designHost,
        "project.host.main" to strings.help.projectHost,
        "tool.light" to strings.help.flashlight,
        "tool.lockTrace" to strings.help.lockTrace,
        "tool.helpMain" to strings.help.helpMain,
        // Host sub-items (visible inline when the host accordion is expanded)
        "mode.ar" to strings.help.ar,
        "mode.overlay" to strings.help.overlay,
        "mode.mockup" to strings.help.mockup,
        "mode.trace" to strings.help.trace,
        "target.scanModeToggle" to strings.help.scanModeToggle,
        "target.create" to strings.help.create,
        "design.addImg" to strings.help.addImg,
        "design.addDraw" to strings.help.addDraw,
        "design.addText" to strings.help.addText,
        "design.wall" to strings.help.wall,
        "project.new" to strings.help.newProject,
        "project.save" to strings.help.saveProject,
        "project.load" to strings.help.loadProject,
        "project.export" to strings.help.exportImage,
        "project.settings" to strings.help.appSettings,
        "wearable.main" to strings.nav.wearableInfo,
    )

    layers.forEach { layer ->
        base[layerId(layer)] = strings.help.layer(layer.name)
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
        base["${layerId(layer)}.help"] = strings.help.helpLayer
    }

    return base
}
