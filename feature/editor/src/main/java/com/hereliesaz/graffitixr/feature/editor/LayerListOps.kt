package com.hereliesaz.graffitixr.feature.editor

import com.hereliesaz.graffitixr.common.model.Layer

/**
 * Pure, stateless transforms over the editor's layer list. Extracted from EditorViewModel so the
 * reorder / rename / visibility / single-layer-update logic has one unit-testable home with no
 * Android, Compose, or OpenCV dependencies. The ViewModel applies the returned list to its UiState.
 */
internal object LayerListOps {

    /** Reorders [layers] to match [newOrder] (by id). Ids not present in [layers] are dropped. */
    fun reorder(layers: List<Layer>, newOrder: List<String>): List<Layer> {
        val byId = layers.associateBy { it.id }
        return newOrder.mapNotNull { byId[it] }
    }

    /** Applies [transform] to the layer with [id], leaving every other layer untouched. */
    fun mapLayer(layers: List<Layer>, id: String, transform: (Layer) -> Layer): List<Layer> =
        layers.map { if (it.id == id) transform(it) else it }

    fun rename(layers: List<Layer>, id: String, name: String): List<Layer> =
        mapLayer(layers, id) { it.copy(name = name) }

    fun toggleVisibility(layers: List<Layer>, id: String): List<Layer> =
        mapLayer(layers, id) { it.copy(isVisible = !it.isVisible) }
}
