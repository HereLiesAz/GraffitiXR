package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import com.hereliesaz.graffitixr.common.model.EditorMode
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.ModeAdjustment
import com.hereliesaz.graffitixr.common.model.LayerProps

/**
 * State-changing user intents for the editor — the "Intent" of MVI. Each is handled by the pure
 * [EditorReducer] to produce the next [com.hereliesaz.graffitixr.common.model.EditorUiState].
 *
 * These cover the state-only transitions. Side effects an intent may also require (history
 * snapshot, persistence, co-op op emission) are orchestrated by EditorViewModel around the
 * dispatch — they are intentionally not part of the intent or reducer.
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
    data object ToggleInvert : EditorIntent
    data object ToggleImageLock : EditorIntent
    data object CycleRotationAxis : EditorIntent

    // ── The design ────────────────────────────────────────────────────────────
    /** Replaces the design with [layer]. [resetActivePanel] dismisses any open panel. */
    data class SetDesign(val layer: Layer, val resetActivePanel: Boolean = true) : EditorIntent

    // ── Panel / mode / gesture ────────────────────────────────────────────────
    /**
     * The Reset button. First press flattens placement — the design's own transform and the current
     * mode's whole-design transform — to identity, stashing what was there. Second press puts the
     * stash back. Tone, colour balance and effects are never touched by either press.
     */
    data object ToggleTransformReset : EditorIntent

    data object ToggleAdjustPanel : EditorIntent
    data object DismissPanel : EditorIntent
    data class SetEditorMode(val mode: EditorMode) : EditorIntent

    // ── Per-mode whole-design adjustments ─────────────────────────────────────
    data class SetModeAdjustment(val mode: EditorMode, val adjustment: ModeAdjustment) : EditorIntent
    data class SetAllModeAdjustments(val adjustments: Map<EditorMode, ModeAdjustment>) : EditorIntent
    data class ApplyModeTransformGesture(val mode: EditorMode, val pan: Offset, val zoom: Float, val rotation: Float) : EditorIntent
    data class ToggleModeTransformLocked(val mode: EditorMode) : EditorIntent
    data class SetGestureInProgress(val inProgress: Boolean) : EditorIntent

    // ── Effect-result / transient flags (dispatched by the VM around async work) ───
    data class SetLoading(val loading: Boolean) : EditorIntent
    data class SetBackgroundBitmap(val bitmap: Bitmap?) : EditorIntent

    // ── Settings / perception layers ──────────────────────────────────────────
    data class SetCanvasBackground(val color: Color) : EditorIntent
    data object ToggleHandedness : EditorIntent
    data object ToggleDiagOverlay : EditorIntent
    data object ToggleFeaturePoints : EditorIntent
    data object TogglePlaneGrids : EditorIntent
    data object TogglePoints : EditorIntent
    /**
     * Sets the method-specific perception layers to their defaults for [activeMethod]: the layer
     * matching the active mural method on, the other two off. Always-applicable layers (feature
     * points, plane grids) are untouched. Dispatched on AR entry and on method change; the user
     * may then turn any layer back on manually until the method changes again.
     */
    data object FeedbackShown : EditorIntent

    // ── Spectator / remote-op application (no panel or gesture side effects) ──
    data class SetDesignTransform(val scale: Float, val offset: Offset, val rx: Float, val ry: Float, val rz: Float) : EditorIntent
    data class SetDesignProps(val props: LayerProps) : EditorIntent

    // ── Panels / gestures / layer set / project lifecycle ─────────────────────
    data object ToggleColorPanel : EditorIntent
    /** A transform gesture begins: flags it and dismisses any open panel. */
    data object BeginGesture : EditorIntent
    /** Replaces the design outright (undo restore, reload). Null clears it. */
    data class RestoreDesign(val design: Layer?) : EditorIntent
    data class LoadedProject(val projectId: String, val design: Layer?) : EditorIntent
    data object ClearProject : EditorIntent
}
