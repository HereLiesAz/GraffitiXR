package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import com.hereliesaz.graffitixr.common.model.EditorMode
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.ModeAdjustment
import com.hereliesaz.graffitixr.common.model.LayerProps
import com.hereliesaz.graffitixr.common.model.TextLayerParams
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

    // ── Per-mode whole-design adjustments ─────────────────────────────────────
    data class SetEditingModeLayer(val editing: Boolean) : EditorIntent
    data class SetModeAdjustment(val mode: EditorMode, val adjustment: ModeAdjustment) : EditorIntent
    data class SetAllModeAdjustments(val adjustments: Map<EditorMode, ModeAdjustment>) : EditorIntent
    data class ApplyModeTransformGesture(val mode: EditorMode, val pan: Offset, val zoom: Float, val rotation: Float) : EditorIntent
    data class SetGestureInProgress(val inProgress: Boolean) : EditorIntent

    // ── Effect-result / transient flags (dispatched by the VM around async work) ───
    data class SetLoading(val loading: Boolean) : EditorIntent
    data class SetBackgroundBitmap(val bitmap: Bitmap?) : EditorIntent
    data object BeginSegmentation : EditorIntent
    data object EndSegmentation : EditorIntent
    data class SetSegmentationInfluence(val value: Float) : EditorIntent
    data class SetSegmentationPreview(val preview: Bitmap?) : EditorIntent
    data class SetStencilGenerating(val generating: Boolean) : EditorIntent
    data class SetStencilHintVisible(val visible: Boolean) : EditorIntent
    data class SetStencilButtonPosition(val position: Offset) : EditorIntent

    // ── Settings / tool / brush / color ───────────────────────────────────────
    data class SetCanvasBackground(val color: Color) : EditorIntent
    data object ToggleHandedness : EditorIntent
    data object ToggleDiagOverlay : EditorIntent
    data object FeedbackShown : EditorIntent
    data class SetSketchThickness(val value: Int) : EditorIntent
    data class SetBrushSize(val value: Float) : EditorIntent
    data class SetBrushFeathering(val value: Float) : EditorIntent
    data object ShowColorPicker : EditorIntent
    data object DismissColorPicker : EditorIntent
    /** Sets the active brush color and closes the color picker. */
    data class SetActiveColor(val color: Color) : EditorIntent
    data class SetLayerWarp(val layerId: String, val mesh: List<Float>) : EditorIntent
    /** Applies a freshly-rasterized text bitmap and its params to [layerId]. */
    data class RenderTextLayer(val layerId: String, val bitmap: Bitmap, val params: TextLayerParams) : EditorIntent

    // ── Spectator / remote-op application (by id; no active-layer side effects) ────
    data class AppendLayer(val layer: Layer) : EditorIntent
    data class RemoveLayerById(val id: String) : EditorIntent
    data class SetLayerTransformById(val id: String, val scale: Float, val offset: Offset, val rx: Float, val ry: Float, val rz: Float) : EditorIntent
    data class SetLayerProps(val id: String, val props: LayerProps) : EditorIntent

    // ── Panels / gestures / layer set / project lifecycle ─────────────────────
    data object ToggleColorPanel : EditorIntent
    /** A transform gesture begins: flags it and dismisses any open panel. */
    data object BeginGesture : EditorIntent
    /** Replaces just the layer list, leaving active id / tool untouched (undo restore, reload). */
    data class SetLayers(val layers: List<Layer>) : EditorIntent
    /** Copies a source layer's aesthetic modifications (incl. warp mesh) onto [id]. */
    data class PasteLayerModifications(val id: String, val source: Layer) : EditorIntent
    data class LoadedProject(val projectId: String, val layers: List<Layer>) : EditorIntent
    data object ClearProject : EditorIntent
}
