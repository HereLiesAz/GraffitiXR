package com.hereliesaz.graffitixr.common.model

import kotlinx.serialization.Serializable

/**
 * The set of layer mutations that propagate over the co-op wire from host to guest.
 * Coarse-grained: brush strokes propagate only on completion, not per-sample.
 *
 * Editor mutations not mapping to one of these are not synced. New mutation types
 * require adding an Op variant.
 */
@Serializable
sealed class Op {
    @Serializable
    data class LayerAdd(val layer: Layer) : Op()

    @Serializable
    data class LayerRemove(val layerId: String) : Op()

    @Serializable
    data class LayerReorder(val newOrder: List<String>) : Op()

    @Serializable
    data class LayerTransform(val layerId: String, val matrix: List<Float>) : Op()

    @Serializable
    data class LayerPropsChange(val layerId: String, val props: LayerProps) : Op()

    @Serializable
    data class StrokeComplete(val layerId: String, val stroke: BrushStroke) : Op()

    @Serializable
    data class TextContentChange(val layerId: String, val text: String) : Op()
}
