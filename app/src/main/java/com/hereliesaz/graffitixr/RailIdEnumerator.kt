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
        "mode_host", "ar", "overlay", "mockup", "trace",
        "wearable",
    )
    if (mode == EditorMode.AR) {
        ids += setOf("scan_mode_cycle", "coop_main")
    }

    // Target menu (AR only)
    if (mode == EditorMode.AR) {
        ids += setOf("target_host", "create")
    }

    // Design menu
    ids += setOf(
        "design_host", "add_img", "add_draw", "add_text",
    )
    if (mode == EditorMode.MOCKUP) {
        ids += "wall"
    }

    // Project menu
    ids += setOf(
        "project_host", "new", "save", "load", "export", "settings",
    )

    // Global tools
    ids += setOf("light", "lock_trace", "help_main")

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
