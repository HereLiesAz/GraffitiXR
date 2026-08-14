package com.hereliesaz.graffitixr.feature.editor

import com.hereliesaz.graffitixr.common.model.EditorMode
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.ModeAdjustment

/**
 * A single undoable step: a snapshot of the design with its bitmap stripped, plus — if the edit was
 * made outside DESIGN mode — the whole-design [ModeAdjustment] for the mode it was made in.
 *
 * Since this app no longer edits pixels — the companion design app owns authoring — every undoable
 * change is a property change (transform, tone, visibility), so one snapshot type covers the whole
 * history. [oldDesign] is nullable because "no design yet" is a state the user can undo back to.
 *
 * [oldMode]/[oldModeAdjustment] exist because outside DESIGN mode, every gesture and tone control
 * writes to [ModeAdjustment], not to the design layer — a history entry that captured only the
 * design would restore an unchanged design on Undo (a visible no-op) and, worse, would leave the
 * real placement nowhere to come back from once Reset had already flattened it to identity. Both
 * null together means the snapshot was taken in DESIGN mode, where there is no mode adjustment to
 * restore.
 */
internal data class EditCommand(
    val oldDesign: Layer?,
    val oldMode: EditorMode? = null,
    val oldModeAdjustment: ModeAdjustment? = null,
)

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
     * Records a design-property snapshot, and — outside DESIGN mode — the mode adjustment snapshot
     * alongside it. Deduplicated: a snapshot identical to the most recent one (design AND mode
     * adjustment both unchanged) is ignored (returns false). Pushing clears the redo stack.
     */
    fun pushProperty(
        designWithoutBitmap: Layer?,
        mode: EditorMode? = null,
        modeAdjustment: ModeAdjustment? = null,
    ): Boolean {
        val command = EditCommand(designWithoutBitmap, mode, modeAdjustment)
        if (undoStack.isNotEmpty() && undoStack.last() == command) return false
        undoStack.addLast(command)
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
