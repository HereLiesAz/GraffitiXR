package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.ui.geometry.Offset
import com.hereliesaz.graffitixr.common.model.EditorMode
import com.hereliesaz.graffitixr.common.model.EditorPanel
import com.hereliesaz.graffitixr.common.model.EditorUiState
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.ModeAdjustment
import com.hereliesaz.graffitixr.common.model.RotationAxis
import com.hereliesaz.graffitixr.common.model.TransformStash

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
        is EditorIntent.SetOpacity -> state.mapDesign { it.copy(opacity = intent.value) }
        is EditorIntent.SetBrightness -> state.mapDesign { it.copy(brightness = intent.value) }
        is EditorIntent.SetContrast -> state.mapDesign { it.copy(contrast = intent.value) }
        is EditorIntent.SetSaturation -> state.mapDesign { it.copy(saturation = intent.value) }
        is EditorIntent.SetColorBalanceR -> state.mapDesign { it.copy(colorBalanceR = intent.value) }
        is EditorIntent.SetColorBalanceG -> state.mapDesign { it.copy(colorBalanceG = intent.value) }
        is EditorIntent.SetColorBalanceB -> state.mapDesign { it.copy(colorBalanceB = intent.value) }
        is EditorIntent.SetScale -> state.mapDesign { it.copy(scale = intent.value) }.movedSinceReset()
        is EditorIntent.AddOffset -> state.mapDesign { it.copy(offset = it.offset + intent.delta) }.movedSinceReset()
        EditorIntent.ToggleInvert -> state.mapDesign { it.copy(isInverted = !it.isInverted) }
        EditorIntent.ToggleImageLock -> state.mapDesign { it.copy(isImageLocked = !it.isImageLocked) }
        EditorIntent.CycleRotationAxis -> {
            val next = when (state.activeRotationAxis) {
                RotationAxis.X -> RotationAxis.Y
                RotationAxis.Y -> RotationAxis.Z
                RotationAxis.Z -> RotationAxis.X
            }
            state.copy(activeRotationAxis = next, showRotationAxisFeedback = true)
        }

        is EditorIntent.SetDesign -> state.copy(
            design = intent.layer,
            activePanel = if (intent.resetActivePanel) EditorPanel.NONE else state.activePanel,
            transformStash = null,
        )

        EditorIntent.ToggleTransformReset -> reduceTransformReset(state)

        EditorIntent.ToggleAdjustPanel ->
            state.copy(activePanel = if (state.activePanel == EditorPanel.ADJUST) EditorPanel.NONE else EditorPanel.ADJUST)
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
                    .movedSinceReset()
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

        is EditorIntent.SetDesignTransform -> state.mapDesign {
            it.copy(scale = intent.scale, offset = intent.offset, rotationX = intent.rx, rotationY = intent.ry, rotationZ = intent.rz)
        }.movedSinceReset()
        is EditorIntent.SetDesignProps -> state.mapDesign {
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
        }

        EditorIntent.ToggleColorPanel ->
            state.copy(activePanel = if (state.activePanel == EditorPanel.COLOR) EditorPanel.NONE else EditorPanel.COLOR)
        EditorIntent.BeginGesture -> state.copy(gestureInProgress = true, activePanel = EditorPanel.NONE)
        is EditorIntent.RestoreDesign -> state.copy(design = intent.design, transformStash = null)
        is EditorIntent.LoadedProject ->
            state.copy(projectId = intent.projectId, design = intent.design, transformStash = null)
        EditorIntent.ClearProject -> state.copy(
            projectId = null,
            design = null,
            backgroundBitmap = null,
            transformStash = null,
        )
    }

    /**
     * Drops any pending Reset stash. Called from every branch that moves the design: once the user
     * has repositioned, "put it back where it was" would put it somewhere they have deliberately
     * left, so the next Reset press should reset rather than restore.
     */
    private fun EditorUiState.movedSinceReset(): EditorUiState =
        if (transformStash == null) this else copy(transformStash = null)

    /**
     * The Reset button, which is a toggle rather than a destructive action.
     *
     * Placement lives in two places at once: the design carries its own scale/offset/rotation, and
     * the mode in view carries a whole-design [ModeAdjustment] transform on top of it. Both are what
     * the user means by "where it is", so Reset flattens both — and neither tone, colour balance nor
     * the effect flags are part of it, so both presses leave those exactly as they were.
     *
     * First press stashes the current placement and goes to identity. Second press restores the
     * stash. A stash taken in another mode is not restorable (the mode transform it describes is not
     * the one on screen), so that case resets afresh instead.
     */
    private fun reduceTransformReset(state: EditorUiState): EditorUiState {
        val stash = state.transformStash
        if (stash != null && stash.mode == state.editorMode) {
            return state
                .mapDesign {
                    it.copy(
                        scale = stash.designScale,
                        offset = stash.designOffset,
                        rotationX = stash.designRotationX,
                        rotationY = stash.designRotationY,
                        rotationZ = stash.designRotationZ,
                    )
                }
                .mapModeTransform {
                    it.copy(
                        offsetX = stash.modeOffsetX,
                        offsetY = stash.modeOffsetY,
                        scale = stash.modeScale,
                        rotation = stash.modeRotation,
                        rotationX = stash.modeRotationX,
                        rotationY = stash.modeRotationY,
                    )
                }
                .copy(transformStash = null)
        }

        val design = state.design
        val modeAdj = state.modeAdjustments[state.editorMode] ?: ModeAdjustment()
        val taken = TransformStash(
            mode = state.editorMode,
            designScale = design?.scale ?: 1f,
            designOffset = design?.offset ?: Offset.Zero,
            designRotationX = design?.rotationX ?: 0f,
            designRotationY = design?.rotationY ?: 0f,
            designRotationZ = design?.rotationZ ?: 0f,
            modeOffsetX = modeAdj.offsetX,
            modeOffsetY = modeAdj.offsetY,
            modeScale = modeAdj.scale,
            modeRotation = modeAdj.rotation,
            modeRotationX = modeAdj.rotationX,
            modeRotationY = modeAdj.rotationY,
        )
        return state
            .mapDesign {
                it.copy(scale = 1f, offset = Offset.Zero, rotationX = 0f, rotationY = 0f, rotationZ = 0f)
            }
            .mapModeTransform {
                it.copy(offsetX = 0f, offsetY = 0f, scale = 1f, rotation = 0f, rotationX = 0f, rotationY = 0f)
            }
            .copy(transformStash = taken)
    }

    /** Applies [transform] to the current mode's whole-design adjustment, creating it if absent. */
    private fun EditorUiState.mapModeTransform(transform: (ModeAdjustment) -> ModeAdjustment): EditorUiState {
        val cur = modeAdjustments[editorMode] ?: ModeAdjustment()
        return copy(modeAdjustments = modeAdjustments + (editorMode to transform(cur)))
    }

    /**
     * Mode is a view, not a container: the design (the document) persists and stays editable, but
     * transient mode-specific overlay state must not bleed into the next mode.
     */
    private fun reduceEditorMode(state: EditorUiState, mode: EditorMode): EditorUiState {
        if (state.editorMode == mode) return state
        return state.copy(editorMode = mode)
    }

    /** Applies [transform] to the design (no-op when no image has been chosen yet). */
    private fun EditorUiState.mapDesign(transform: (Layer) -> Layer): EditorUiState =
        design?.let { copy(design = transform(it)) } ?: this
}
