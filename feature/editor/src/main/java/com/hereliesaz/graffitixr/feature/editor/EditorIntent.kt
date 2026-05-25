package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.ui.geometry.Offset
import com.hereliesaz.graffitixr.common.model.EditorMode
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.Tool

/**
 * State-changing user intents for the editor — the "Intent" of MVI. Each is handled by the pure
 * [EditorReducer] to produce the next [com.hereliesaz.graffitixr.common.model.EditorUiState].
 *
 * These cover the state-only transitions. Side effects an intent may also require (history
 * snapshot, persistence, co-op op emission, OpenCV rasterization) are orchestrated by
 * EditorViewModel around the dispatch — they are intentionally not part of the intent or reducer.
 */
internal sealed interface EditorIntent {
    // ── Active-layer visual properties ────────────────────────────────────────
    data class SetOpacity(val value: Float) : EditorIntent
    data class SetBrightness(val value: Float) : EditorIntent
    data class SetContrast(val value: Float) : EditorIntent
    data class SetSaturation(val value: Float) : EditorIntent
    data class SetColorBalanceR(val value: Float) : EditorIntent
    data class SetColorBalanceG(val value: Float) : EditorIntent
    data class SetColorBalanceB(val value: Float) : EditorIntent
    data class SetScale(val value: Float) : EditorIntent
    /** Pan is incremental: [delta] is ADDED to the active layer's current offset. */
    data class AddOffset(val delta: Offset) : EditorIntent
    data class SetRotationX(val value: Float) : EditorIntent
    data class SetRotationY(val value: Float) : EditorIntent
    data class SetRotationZ(val value: Float) : EditorIntent
    data class SetLayerTransform(val scale: Float, val offset: Offset, val rx: Float, val ry: Float, val rz: Float) : EditorIntent
    data object ToggleInvert : EditorIntent
    data object ToggleImageLock : EditorIntent
    data object CycleRotationAxis : EditorIntent

    // ── Layer list ────────────────────────────────────────────────────────────
    data class ReorderLayers(val order: List<String>) : EditorIntent
    data class RenameLayer(val id: String, val name: String) : EditorIntent
    data class ToggleVisibility(val id: String) : EditorIntent
    data class ActivateLayer(val id: String) : EditorIntent

    /** Appends [layer], makes it active, and clears the tool. [resetActivePanel] mirrors the
     *  two call patterns: adds dismiss the panel, duplicate leaves it as-is. */
    data class AddLayer(val layer: Layer, val resetActivePanel: Boolean = true) : EditorIntent
    /** Removes [id]; if it was active, activates the first remaining layer. Clears the tool. */
    data class RemoveLayer(val id: String) : EditorIntent
    /** Replaces the whole layer set (e.g. flatten) with [layers], activating [activeId]. */
    data class ReplaceLayers(val layers: List<Layer>, val activeId: String?) : EditorIntent

    // ── Tool / panel / mode / gesture ─────────────────────────────────────────
    data class SetActiveTool(val tool: Tool) : EditorIntent
    data object ToggleAdjustPanel : EditorIntent
    data object DismissPanel : EditorIntent
    data class SetEditorMode(val mode: EditorMode) : EditorIntent
    data class SetGestureInProgress(val inProgress: Boolean) : EditorIntent
}
