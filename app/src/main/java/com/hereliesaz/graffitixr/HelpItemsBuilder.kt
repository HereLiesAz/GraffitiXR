package com.hereliesaz.graffitixr

import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.design.theme.AppStrings

/**
 * Builds the helpList map consumed by AzNavRail's azAdvanced(helpList = ...)
 * parameter. Single source of truth for help-overlay text.
 *
 * Keys must match rail-item IDs registered in ConfigureRailItems.
 * Layer-derived keys use the layerId() helper.
 */
internal fun buildHelpItems(strings: AppStrings, layers: List<Layer>): Map<String, Any> {
    val base = mutableMapOf<String, Any>(
        "mode.host" to strings.help.modeHost,
        "mode.ar" to strings.help.ar,
        "mode.overlay" to strings.help.overlay,
        "mode.mockup" to strings.help.mockup,
        "mode.trace" to strings.help.trace,
        "target.host" to strings.help.targetHost,
        "target.scanModeToggle" to strings.help.scanModeToggle,
        "target.create" to strings.help.create,
        "design.host" to strings.help.designHost,
        "design.addImg" to strings.help.addImg,
        "design.addDraw" to strings.help.addDraw,
        "design.addText" to strings.help.addText,
        "design.wall" to strings.help.wall,
        "project.host" to strings.help.projectHost,
        "project.new" to strings.help.newProject,
        "project.save" to strings.help.saveProject,
        "project.load" to strings.help.loadProject,
        "project.export" to strings.help.exportImage,
        "project.settings" to strings.help.appSettings,
        "tool.light" to strings.help.flashlight,
        "tool.lockTrace" to strings.help.lockTrace,
        "tool.helpMain" to strings.help.helpMain,
    )

    layers.forEach { layer ->
        val id = layer.id
        base["layer_$id"] = strings.help.layer(layer.name)
        base["edit_text_$id"] = strings.help.editText
        base["size_$id"] = strings.help.size
        base["font_$id"] = strings.help.font
        base["color_$id"] = strings.help.color
        base["kern_$id"] = strings.help.kern
        base["bold_$id"] = strings.help.bold
        base["italic_$id"] = strings.help.italic
        base["outline_$id"] = strings.help.outline
        base["shadow_$id"] = strings.help.shadow
        base["stencil_$id"] = strings.help.stencilGen
        base["blend_$id"] = strings.help.blend
        base["adj_$id"] = strings.help.adj
        base["invert_$id"] = strings.help.invert
        base["balance_$id"] = strings.help.balance
        base["eraser_$id"] = strings.help.eraser
        base["blur_$id"] = strings.help.blur
        base["liquify_$id"] = strings.help.liquify
        base["dodge_$id"] = strings.help.dodge
        base["burn_$id"] = strings.help.burn
        base["iso_$id"] = strings.help.iso
        base["line_$id"] = strings.help.line
        base["help_layer_$id"] = strings.help.helpLayer
    }

    return base
}
