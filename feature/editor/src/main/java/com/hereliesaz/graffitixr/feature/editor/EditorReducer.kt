package com.hereliesaz.graffitixr.feature.editor

import com.hereliesaz.graffitixr.common.model.EditorMode
import com.hereliesaz.graffitixr.common.model.MuralMethod
import com.hereliesaz.graffitixr.common.model.EditorPanel
import com.hereliesaz.graffitixr.common.model.EditorUiState
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.ModeAdjustment
import com.hereliesaz.graffitixr.common.model.RotationAxis
import com.hereliesaz.graffitixr.common.model.Tool

/**
 * The pure state-transition function for the editor — the heart of its MVI design. Given the
 * current [EditorUiState] and an [EditorIntent], it returns the next state with no dependency on
 * Android, Compose, OpenCV, IO, or coroutines, which makes every transition unit-testable without
 * a single mock.
 *
 * Side effects that an intent also triggers (undo-history snapshot, persistence, co-op op
 * emission, OpenCV rasterization) live in EditorViewModel around the dispatch — keeping them out
 * of here is precisely what lets this be pure.
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
        is EditorIntent.SetRotationX -> state.mapActive { it.copy(rotationX = intent.value) }.copy(activeRotationAxis = RotationAxis.X)
        is EditorIntent.SetRotationY -> state.mapActive { it.copy(rotationY = intent.value) }.copy(activeRotationAxis = RotationAxis.Y)
        is EditorIntent.SetRotationZ -> state.mapActive { it.copy(rotationZ = intent.value) }.copy(activeRotationAxis = RotationAxis.Z)
        is EditorIntent.SetLayerTransform -> state.mapActive {
            it.copy(scale = intent.scale, offset = intent.offset, rotationX = intent.rx, rotationY = intent.ry, rotationZ = intent.rz)
        }
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
        is EditorIntent.ActivateLayer -> state.copy(activeLayerId = intent.id, activeTool = Tool.NONE)
        is EditorIntent.AddLayer -> state.copy(
            layers = state.layers + intent.layer,
            activeLayerId = intent.layer.id,
            activeTool = Tool.NONE,
            activePanel = if (intent.resetActivePanel) EditorPanel.NONE else state.activePanel,
        )
        is EditorIntent.RemoveLayer -> {
            val remaining = state.layers.filter { it.id != intent.id }
            state.copy(
                layers = remaining,
                activeLayerId = if (state.activeLayerId == intent.id) remaining.firstOrNull()?.id else state.activeLayerId,
                activeTool = Tool.NONE,
            )
        }
        is EditorIntent.ReplaceLayers -> state.copy(layers = intent.layers, activeLayerId = intent.activeId, activeTool = Tool.NONE)

        is EditorIntent.SetActiveTool -> state.copy(activeTool = intent.tool, activePanel = EditorPanel.NONE)
        EditorIntent.ToggleAdjustPanel ->
            state.copy(activePanel = if (state.activePanel == EditorPanel.ADJUST) EditorPanel.NONE else EditorPanel.ADJUST)
        EditorIntent.DismissPanel -> state.copy(activePanel = EditorPanel.NONE)
        is EditorIntent.SetGestureInProgress -> state.copy(gestureInProgress = intent.inProgress)
        is EditorIntent.SetEditorMode -> reduceEditorMode(state, intent.mode)

        is EditorIntent.SetEditingModeLayer -> state.copy(editingModeLayer = intent.editing)
        is EditorIntent.SetModeAdjustment ->
            state.copy(modeAdjustments = state.modeAdjustments + (intent.mode to intent.adjustment))
        is EditorIntent.SetAllModeAdjustments -> state.copy(modeAdjustments = intent.adjustments)
        is EditorIntent.ApplyModeTransformGesture -> {
            val cur = state.modeAdjustments[intent.mode] ?: ModeAdjustment()
            // Locked: the user pinned this mode's whole-design position, so ignore transform gestures.
            if (cur.isTransformLocked) state
            else {
                // Rotation goes to the axis selected by the double-tap cycle (activeRotationAxis): X/Y
                // tilt the whole design about its own width/height, Z spins it in-plane. Available in
                // every non-Design mode (AR/Overlay/Mockup/Trace), which all render the tilt. Pan/scale
                // always apply.
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
        EditorIntent.BeginSegmentation -> state.copy(isSegmenting = true, segmentationInfluence = 0.5f)
        EditorIntent.EndSegmentation -> state.copy(isSegmenting = false, segmentationPreview = null)
        is EditorIntent.SetSegmentationInfluence -> state.copy(segmentationInfluence = intent.value)
        is EditorIntent.SetSegmentationPreview -> state.copy(segmentationPreview = intent.preview)
        is EditorIntent.SetStencilGenerating -> state.copy(isStencilGenerating = intent.generating)
        is EditorIntent.SetStencilHintVisible -> state.copy(stencilHintVisible = intent.visible)
        is EditorIntent.SetStencilButtonPosition -> state.copy(stencilButtonPosition = intent.position)

        is EditorIntent.SetCanvasBackground -> state.copy(canvasBackground = intent.color)
        EditorIntent.ToggleHandedness -> state.copy(isRightHanded = !state.isRightHanded)
        EditorIntent.ToggleDiagOverlay -> state.copy(showDiagOverlay = !state.showDiagOverlay)
        EditorIntent.ToggleFeaturePoints -> state.copy(showFeaturePoints = !state.showFeaturePoints)
        EditorIntent.TogglePlaneGrids -> state.copy(showPlaneGrids = !state.showPlaneGrids)
        EditorIntent.ToggleVoxels -> state.copy(showVoxels = !state.showVoxels)
        EditorIntent.TogglePoints -> state.copy(showPoints = !state.showPoints)
        EditorIntent.ToggleMesh -> state.copy(showMesh = !state.showMesh)
        is EditorIntent.ApplyMethodLayerDefaults -> state.copy(
            showVoxels = intent.activeMethod == MuralMethod.VOXEL_HASH,
            showMesh = intent.activeMethod == MuralMethod.SURFACE_MESH,
            showPoints = intent.activeMethod == MuralMethod.CLOUD_OFFSET
        )
        EditorIntent.FeedbackShown -> state.copy(showRotationAxisFeedback = false)
        is EditorIntent.SetSketchThickness -> state.copy(sketchThickness = intent.value.coerceIn(1, 20))
        is EditorIntent.SetBrushSize -> state.copy(brushSize = intent.value.coerceIn(1f, 200f))
        is EditorIntent.SetBrushFeathering -> state.copy(brushFeathering = intent.value.coerceIn(0f, 1f))
        EditorIntent.ShowColorPicker -> state.copy(showColorPicker = true)
        EditorIntent.DismissColorPicker -> state.copy(showColorPicker = false)
        is EditorIntent.SetActiveColor -> state.copy(activeColor = intent.color, showColorPicker = false)
        is EditorIntent.SetLayerWarp -> state.copy(layers = LayerListOps.mapLayer(state.layers, intent.layerId) { it.copy(warpMesh = intent.mesh) })
        is EditorIntent.RenderTextLayer -> state.copy(layers = LayerListOps.mapLayer(state.layers, intent.layerId) { it.copy(bitmap = intent.bitmap, textParams = intent.params) })

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
        is EditorIntent.SetLayers -> state.copy(layers = intent.layers)
        is EditorIntent.PasteLayerModifications -> state.copy(layers = LayerListOps.mapLayer(state.layers, intent.id) {
            it.copy(
                opacity = intent.source.opacity,
                brightness = intent.source.brightness,
                contrast = intent.source.contrast,
                saturation = intent.source.saturation,
                colorBalanceR = intent.source.colorBalanceR,
                colorBalanceG = intent.source.colorBalanceG,
                colorBalanceB = intent.source.colorBalanceB,
                blendMode = intent.source.blendMode,
                warpMesh = intent.source.warpMesh,
            )
        })
        is EditorIntent.LoadedProject -> state.copy(projectId = intent.projectId, layers = intent.layers, activeTool = Tool.NONE)
        EditorIntent.ClearProject -> state.copy(projectId = null, layers = emptyList(), backgroundBitmap = null, activeTool = Tool.NONE)
    }

    /**
     * Mode is a view, not a container: layers (the document) persist and stay editable, but
     * transient mode-specific overlay state — an in-flight brush stroke, a live segmentation
     * preview — must not bleed into the next mode.
     */
    private fun reduceEditorMode(state: EditorUiState, mode: EditorMode): EditorUiState {
        if (state.editorMode == mode) return state
        return state.copy(
            editorMode = mode,
            liveStrokeLayerId = null,
            liveStrokeBitmap = null,
            liveStrokeVersion = 0,
            isSegmenting = false,
            segmentationPreview = null,
        )
    }

    /** Applies [transform] to the active layer (no-op when there is no active layer). */
    private fun EditorUiState.mapActive(transform: (Layer) -> Layer): EditorUiState {
        val id = activeLayerId ?: return this
        return copy(layers = LayerListOps.mapLayer(layers, id, transform))
    }
}
