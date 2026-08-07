package com.hereliesaz.graffitixr.feature.editor

import com.hereliesaz.graffitixr.common.model.Layer

/**
 * A single undoable step: a snapshot of the design with its bitmap stripped.
 *
 * Since this app no longer edits pixels — the companion design app owns authoring — every undoable
 * change is a property change (transform, tone, visibility), so one snapshot type covers the whole
 * history. Nullable because "no design yet" is a state the user can undo back to.
 */
internal data class EditCommand(val oldDesign: Layer?)

/**
 * Owns the undo/redo stacks for the editor. Pure logic — no Android or Compose dependencies — so
 * it is fully unit-testable in isolation.
 *
 * The *application* of a command (restoring layer props) stays in the ViewModel; this class only
 * manages the stacks. When popping, the caller supplies the counterpart entry to record on the
 * opposite stack, because that entry depends on the ViewModel's current state.
 */
internal class EditHistory(private val maxStackSize: Int = 20) {
    private val undoStack = ArrayDeque<EditCommand>()
    private val redoStack = ArrayDeque<EditCommand>()

    val undoCount: Int get() = undoStack.size
    val redoCount: Int get() = redoStack.size

    /**
     * Records a design-property snapshot. Deduplicated: a snapshot identical to the most recent one
     * is ignored (returns false). Pushing clears the redo stack.
     */
    fun pushProperty(designWithoutBitmap: Layer?): Boolean {
        if (undoStack.isNotEmpty() && undoStack.last().oldDesign == designWithoutBitmap) return false
        undoStack.addLast(EditCommand(designWithoutBitmap))
        trim()
        redoStack.clear()
        return true
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
