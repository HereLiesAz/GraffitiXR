package com.hereliesaz.graffitixr

import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.design.theme.AppStrings

/**
 * Builds the helpList map consumed by AzNavRail's azAdvanced(helpList = ...)
 * parameter. Single source of truth for help-overlay text.
 *
 * Per the AzNavRail v8.10 guide (HelpOverlay filter rule): a help card is
 * shown for an item when it has a `helpList` entry, an `info` string, or a
 * tutorial. To scope help to "items on the main rail" vs "items in the open
 * nested rail", this map is restricted to:
 *   - Top-level rail items the user can see on the main rail
 *   - Per-layer popup items (visible only when that layer's nested rail is open)
 * Host sub-items (e.g. mode.ar, design.addImg) are intentionally omitted —
 * they're inline expansions of host accordions, not items the user views as
 * "on the main rail".
 *
 * Keys must match rail-item IDs registered in ConfigureRailItems.
 */
internal fun buildHelpItems(strings: AppStrings, layers: List<Layer>): Map<String, Any> {
    val base = mutableMapOf<String, Any>(
        // Top-level main rail items — what's visible on the rail itself.
        "mode.host" to strings.help.modeHost,
        "target.host" to strings.help.targetHost,
        "design.host" to strings.help.designHost,
        "project.host.main" to strings.help.projectHost,
        "tool.light" to strings.help.flashlight,
        "tool.lockTrace" to strings.help.lockTrace,
        "tool.helpMain" to strings.help.helpMain,
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
