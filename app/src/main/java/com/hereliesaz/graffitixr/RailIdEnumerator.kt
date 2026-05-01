package com.hereliesaz.graffitixr

import com.hereliesaz.graffitixr.common.model.EditorMode
import com.hereliesaz.graffitixr.common.model.Layer

/**
 * Returns every rail-item ID that ConfigureRailItems will register for the
 * given mode and layer list. Pure function; no Compose. Used by tests
 * (TutorialAnchorTest) and the debug-only RailIntegrityCheck.
 *
 * Conditional registration in ConfigureRailItems is mirrored here:
 *   - target.host / target.create only registered in AR mode
 *   - design.wall only registered in MOCKUP mode
 *   - scan_mode_cycle / coop.main only in AR mode
 */
internal fun enumerateRailItemIds(layers: List<Layer>, mode: EditorMode): Set<String> {
    val ids = mutableSetOf<String>()

    // Mode menu
    ids += setOf(
        "mode.host", "mode.ar", "mode.overlay", "mode.mockup", "mode.trace",
        "wearable.main",
    )
    if (mode == EditorMode.AR) {
        ids += setOf("target.scanModeCycle", "coop.main")
    }

    // Target menu (AR only)
    if (mode == EditorMode.AR) {
        ids += setOf("target.host", "target.create")
    }

    // Design menu
    ids += setOf(
        "design.host", "design.addImg", "design.addDraw", "design.addText",
    )
    if (mode == EditorMode.MOCKUP) {
        ids += "design.wall"
    }

    // Project menu
    ids += setOf(
        "project.host", "project.new", "project.save", "project.load",
        "project.export", "project.settings",
    )

    // Global tools
    ids += setOf("tool.light", "tool.lockTrace", "tool.helpMain")

    // Per-layer
    layers.forEach { layer ->
        val id = layer.id
        ids += setOf(
            "layer_$id",
            "edit_text_$id", "size_$id", "font_$id", "color_$id",
            "kern_$id", "bold_$id", "italic_$id", "outline_$id",
            "shadow_$id", "stencil_$id", "blend_$id", "adj_$id",
            "invert_$id", "balance_$id", "eraser_$id", "blur_$id",
            "liquify_$id", "dodge_$id", "burn_$id", "iso_$id",
            "line_$id", "help_layer_$id",
        )
    }

    return ids
}
