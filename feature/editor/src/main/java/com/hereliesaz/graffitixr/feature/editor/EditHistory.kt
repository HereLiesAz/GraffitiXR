package com.hereliesaz.graffitixr.feature.editor

import com.hereliesaz.graffitixr.common.model.Layer

/**
 * Owns the undo/redo stacks for the editor. Pure logic — no Android, Compose, or OpenCV
 * dependencies — so it is fully unit-testable in isolation (unlike most of EditorViewModel,
 * whose tests need native OpenCV). Extracted from EditorViewModel to shrink that god-class
 * and give the history mechanics a single, testable home.
 *
 * The *application* of a command (rebuilding bitmaps, restoring layer props) stays in the
 * ViewModel; this class only manages the stacks. When popping, the caller supplies the
 * counterpart entry to record on the opposite stack via [popUndo]/[popRedo], because that
 * entry depends on the ViewModel's current state.
 */
internal class EditHistory(private val maxStackSize: Int = 20) {
    private val undoStack = ArrayDeque<EditCommand>()
    private val redoStack = ArrayDeque<EditCommand>()

    val undoCount: Int get() = undoStack.size
    val redoCount: Int get() = redoStack.size

    /**
     * Records a layer-property snapshot. Deduplicated: a snapshot identical to the most recent
     * one is ignored (returns false). Pushing clears the redo stack.
     */
    fun pushProperty(layersWithoutBitmaps: List<Layer>): Boolean {
        val last = undoStack.lastOrNull()
        if (last is EditCommand.PropertyChange && last.oldLayers == layersWithoutBitmaps) return false
        undoStack.addLast(EditCommand.PropertyChange(layersWithoutBitmaps))
        trim()
        redoStack.clear()
        return true
    }

    /** Records a completed brush stroke. Pushing clears the redo stack. */
    fun pushDraw(layerId: String, command: StrokeCommand) {
        undoStack.addLast(EditCommand.Draw(layerId, command))
        trim()
        redoStack.clear()
    }

    /**
     * Pops the most recent undoable command, recording [counterEntry] of it on the redo stack.
     * Returns null (and records nothing) when there is nothing to undo.
     */
    fun popUndo(counterEntry: (EditCommand) -> EditCommand): EditCommand? {
        val command = undoStack.removeLastOrNull() ?: return null
        redoStack.addLast(counterEntry(command))
        return command
    }

    /** Symmetric to [popUndo]: pops the most recent redoable command onto the undo stack. */
    fun popRedo(counterEntry: (EditCommand) -> EditCommand): EditCommand? {
        val command = redoStack.removeLastOrNull() ?: return null
        undoStack.addLast(counterEntry(command))
        return command
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
    }

    private fun trim() {
        if (undoStack.size > maxStackSize) undoStack.removeFirst()
    }
}
