package com.hereliesaz.graffitixr.feature.editor

import com.hereliesaz.graffitixr.common.model.EditorMode
import com.hereliesaz.graffitixr.common.model.EditorPanel
import com.hereliesaz.graffitixr.common.model.EditorUiState
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.ModeAdjustment
import com.hereliesaz.graffitixr.common.model.RotationAxis

/**
 * The pure state-transition function for the editor — the heart of its MVI design. Given the
 * current [EditorUiState] and an [EditorIntent], it returns the next state with no dependency on
 * Android, Compose, IO, or coroutines, which makes every transition unit-testable without a single
 * mock.
 *
 * Side effects that an intent also triggers (undo-history snapshot, persistence, co-op op
 * emission) live in EditorViewModel around the dispatch — keeping them out of here is precisely
 * what lets this be pure.
 */
internal object EditorReducer {

    fun reduce(state: EditorUiState, intent: EditorIntent): EditorUiState = when (intent) {
        is EditorIntent.SetOpacity -> state.mapActive { it.copy(opacity = intent.value) }
        is EditorIntent.SetBrightness -> state.mapActive { it.copy(brightness = intent.value) }
        is EditorIntent.SetContrast -> state.mapActive { it.copy(contrast = intent.value) }
        is EditorIntent.SetSaturation -> state.mapActive { it.copy(saturation = intent.value) }
        is EditorIntent.SetColorBalanceR -> state.mapActive { it.copy(colorBalanceR = intent.value) }
        is EditorIntent.SetColorBalanceG -> state.mapActive { it.copy(colorBalanceG = intent.value) }
        is EditorIntent.SetColorBalanceB -> state.mapActive { it.copy(colorBalanceB = intent.value) }
        is EditorIntent.SetScale -> state.mapActive { it.copy(scale = intent.value) }
        is EditorIntent.AddOffset -> state.mapActive { it.copy(offset = it.offset + intent.delta) }
        EditorIntent.ToggleInvert -> state.mapActive { it.copy(isInverted = !it.isInverted) }
        EditorIntent.ToggleImageLock -> state.mapActive { it.copy(isImageLocked = !it.isImageLocked) }
        EditorIntent.CycleRotationAxis -> {
            val next = when (state.activeRotationAxis) {
                RotationAxis.X -> RotationAxis.Y
                RotationAxis.Y -> RotationAxis.Z
                RotationAxis.Z -> RotationAxis.X
            }
            state.copy(activeRotationAxis = next, showRotationAxisFeedback = true)
        }

        is EditorIntent.ReorderLayers -> state.copy(layers = LayerListOps.reorder(state.layers, intent.order))
        is EditorIntent.RenameLayer -> state.copy(layers = LayerListOps.rename(state.layers, intent.id, intent.name))
        is EditorIntent.ToggleVisibility -> state.copy(layers = LayerListOps.toggleVisibility(state.layers, intent.id))
        is EditorIntent.ActivateLayer -> state.copy(activeLayerId = intent.id)
        is EditorIntent.AddLayer -> state.copy(
            layers = state.layers + intent.layer,
            activeLayerId = intent.layer.id,
            activePanel = if (intent.resetActivePanel) EditorPanel.NONE else state.activePanel,
        )
        is EditorIntent.RemoveLayer -> {
            val remaining = state.layers.filter { it.id != intent.id }
            state.copy(
                layers = remaining,
                activeLayerId = if (state.activeLayerId == intent.id) remaining.firstOrNull()?.id else state.activeLayerId,
            )
        }

        EditorIntent.ToggleAdjustPanel ->
            state.copy(activePanel = if (state.activePanel == EditorPanel.ADJUST) EditorPanel.NONE else EditorPanel.ADJUST)
        EditorIntent.ToggleLayersPanel ->
            state.copy(activePanel = if (state.activePanel == EditorPanel.LAYERS) EditorPanel.NONE else EditorPanel.LAYERS)
        EditorIntent.DismissPanel -> state.copy(activePanel = EditorPanel.NONE)
        is EditorIntent.SetGestureInProgress -> state.copy(gestureInProgress = intent.inProgress)
        is EditorIntent.SetEditorMode -> reduceEditorMode(state, intent.mode)

        is EditorIntent.SetModeAdjustment ->
            state.copy(modeAdjustments = state.modeAdjustments + (intent.mode to intent.adjustment))
        is EditorIntent.SetAllModeAdjustments -> state.copy(modeAdjustments = intent.adjustments)
        is EditorIntent.ApplyModeTransformGesture -> {
            val cur = state.modeAdjustments[intent.mode] ?: ModeAdjustment()
            // Locked: the user pinned this mode's whole-design position, so ignore transform gestures.
            if (cur.isTransformLocked) state
            else {
                // Rotation goes to the axis selected by the double-tap cycle (activeRotationAxis): X/Y
                // tilt the whole design about its width/height, Z spins it in-plane. All four
                // non-Design modes render these identically: Overlay/Mockup/Trace via Compose's
                // graphicsLayer, AR via a 2D perspective content-rotation matrix in the shader.
                val rotated = when (state.activeRotationAxis) {
                    RotationAxis.X -> cur.copy(rotationX = cur.rotationX + intent.rotation)
                    RotationAxis.Y -> cur.copy(rotationY = cur.rotationY + intent.rotation)
                    RotationAxis.Z -> cur.copy(rotation = cur.rotation + intent.rotation)
                }
                val updated = rotated.copy(
                    offsetX = rotated.offsetX + intent.pan.x,
                    offsetY = rotated.offsetY + intent.pan.y,
                    scale = (rotated.scale * intent.zoom).coerceIn(0.1f, 10f),
                )
                state.copy(modeAdjustments = state.modeAdjustments + (intent.mode to updated))
            }
        }
        is EditorIntent.ToggleModeTransformLocked -> {
            val cur = state.modeAdjustments[intent.mode] ?: ModeAdjustment()
            state.copy(modeAdjustments = state.modeAdjustments + (intent.mode to cur.copy(isTransformLocked = !cur.isTransformLocked)))
        }

        is EditorIntent.SetLoading -> state.copy(isLoading = intent.loading)
        is EditorIntent.SetBackgroundBitmap -> state.copy(backgroundBitmap = intent.bitmap)

        is EditorIntent.SetCanvasBackground -> state.copy(canvasBackground = intent.color)
        EditorIntent.ToggleHandedness -> state.copy(isRightHanded = !state.isRightHanded)
        EditorIntent.ToggleDiagOverlay -> state.copy(showDiagOverlay = !state.showDiagOverlay)
        EditorIntent.ToggleFeaturePoints -> state.copy(showFeaturePoints = !state.showFeaturePoints)
        EditorIntent.TogglePlaneGrids -> state.copy(showPlaneGrids = !state.showPlaneGrids)
        EditorIntent.TogglePoints -> state.copy(showPoints = !state.showPoints)
        EditorIntent.FeedbackShown -> state.copy(showRotationAxisFeedback = false)

        is EditorIntent.AppendLayer -> state.copy(layers = state.layers + intent.layer)
        is EditorIntent.RemoveLayerById -> state.copy(layers = state.layers.filterNot { it.id == intent.id })
        is EditorIntent.SetLayerTransformById -> state.copy(layers = LayerListOps.mapLayer(state.layers, intent.id) {
            it.copy(scale = intent.scale, offset = intent.offset, rotationX = intent.rx, rotationY = intent.ry, rotationZ = intent.rz)
        })
        is EditorIntent.SetLayerProps -> state.copy(layers = LayerListOps.mapLayer(state.layers, intent.id) {
            it.copy(
                isVisible = intent.props.isVisible,
                opacity = intent.props.opacity,
                brightness = intent.props.brightness,
                contrast = intent.props.contrast,
                saturation = intent.props.saturation,
                colorBalanceR = intent.props.colorBalanceR,
                colorBalanceG = intent.props.colorBalanceG,
                colorBalanceB = intent.props.colorBalanceB,
                isImageLocked = intent.props.isImageLocked,
                isInverted = intent.props.isInverted,
                blendMode = intent.props.blendMode,
            )
        })

        EditorIntent.ToggleColorPanel ->
            state.copy(activePanel = if (state.activePanel == EditorPanel.COLOR) EditorPanel.NONE else EditorPanel.COLOR)
        EditorIntent.BeginGesture -> state.copy(gestureInProgress = true, activePanel = EditorPanel.NONE)
        is EditorIntent.SetLayers ->
            state.copy(layers = intent.layers, activeLayerId = state.activeLayerId.stillIn(intent.layers))
        is EditorIntent.LoadedProject -> state.copy(
            projectId = intent.projectId,
            layers = intent.layers,
            // Opening a different project must not leave activeLayerId pointing at a layer from the
            // previous one: every `find { it.id == activeLayerId }` in the ViewModel would miss, so
            // adjustments silently no-op'd. Nulling it lets the UI's auto-activate effect select the
            // new project's first layer.
            activeLayerId = state.activeLayerId.stillIn(intent.layers),
        )
        EditorIntent.ClearProject -> state.copy(
            projectId = null,
            layers = emptyList(),
            activeLayerId = null,
            backgroundBitmap = null,
        )
    }

    /** This id if [layers] still contains it, else null — keeps activeLayerId from dangling. */
    private fun String?.stillIn(layers: List<Layer>): String? =
        this?.takeIf { id -> layers.any { it.id == id } }

    /**
     * Mode is a view, not a container: layers (the document) persist and stay editable, but
     * transient mode-specific overlay state must not bleed into the next mode.
     */
    private fun reduceEditorMode(state: EditorUiState, mode: EditorMode): EditorUiState {
        if (state.editorMode == mode) return state
        return state.copy(editorMode = mode)
    }

    /** Applies [transform] to the active layer (no-op when there is no active layer). */
    private fun EditorUiState.mapActive(transform: (Layer) -> Layer): EditorUiState {
        val id = activeLayerId ?: return this
        return copy(layers = LayerListOps.mapLayer(layers, id, transform))
    }
}
