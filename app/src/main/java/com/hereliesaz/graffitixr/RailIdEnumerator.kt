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
        ids += setOf("target.scanModeToggle", "coop.main")
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
        "project.host.main", "project.new", "project.save", "project.load",
        "project.export", "project.settings",
    )

    // Global tools
    ids += setOf("tool.light", "tool.lockTrace", "tool.helpMain")

    // Per-layer
    layers.forEach { layer ->
        ids += setOf(
            layerId(layer),
            layerId(layer, "editText"),
            layerId(layer, "size.brush"),
            layerId(layer, "size.text"),
            layerId(layer, "font"),
            layerId(layer, "color"),
            layerId(layer, "kern"),
            layerId(layer, "bold"),
            layerId(layer, "italic"),
            layerId(layer, "outline"),
            layerId(layer, "shadow"),
            layerId(layer, "stencil"),
            layerId(layer, "blend"),
            layerId(layer, "adj"),
            layerId(layer, "invert"),
            layerId(layer, "balance"),
            layerId(layer, "eraser"),
            layerId(layer, "blur"),
            layerId(layer, "liquify"),
            layerId(layer, "dodge"),
            layerId(layer, "burn"),
            layerId(layer, "iso"),
            layerId(layer, "line"),
            "${layerId(layer)}.help",
        )
    }

    return ids
}
