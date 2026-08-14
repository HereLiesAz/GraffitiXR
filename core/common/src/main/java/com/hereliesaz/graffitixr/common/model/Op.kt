package com.hereliesaz.graffitixr.common.model

import kotlinx.serialization.Serializable

/**
 * The set of design mutations that propagate over the co-op wire from host to guest.
 *
 * There is exactly one design, so these carry no layer id: the add/remove/reorder ops that a layer
 * LIST needed went with the list itself. Coarse-grained: brush strokes propagate only on
 * completion, not per-sample.
 *
 * Editor mutations not mapping to one of these are not synced. New mutation types require adding
 * an Op variant.
 */
@Serializable
sealed class Op {
    /** The design was chosen or replaced outright — the guest adopts it wholesale. */
    @Serializable
    data class DesignReplace(val design: Layer) : Op()

    @Serializable
    data class DesignTransform(val matrix: List<Float>) : Op()

    /**
     * The whole-design adjustment for one mode (AR/Overlay/Mockup/Trace) changed — the counterpart
     * to [DesignTransform] for placement that lives on [ModeAdjustment] rather than the design
     * layer. Outside DESIGN mode every gesture and tone control writes here, not to the design, so
     * before this variant existed a co-op guest never received the host's actual on-wall placement
     * — only [DesignTransform]s carrying the design's own (untouched, still-identity) transform.
     *
     * [mode] is the mode's name ([EditorMode.name]), not the enum directly: [EditorMode] isn't
     * `@Serializable`, and every other on-disk/on-wire representation of a mode in this codebase
     * already uses its name for the same reason (see `GraffitiProject.modeAdjustments`).
     */
    @Serializable
    data class ModeTransform(val mode: String, val adjustment: ModeAdjustment) : Op()

    @Serializable
    data class DesignProps(val props: LayerProps) : Op()

    @Serializable
    data class StrokeComplete(val stroke: BrushStroke) : Op()

    @Serializable
    data class TextContentChange(val text: String) : Op()

    /**
     * Wholesale replacement of the design's pixels (PNG-encoded), for mutations that don't map to a
     * replayable [StrokeComplete] — undo/redo of the bitmap, and any effect applied to it. The
     * guest decodes [png] and uses it as the new base, dropping any local stroke history.
     */
    @Serializable
    data class DesignBitmapReplace(val png: ByteArray) : Op() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is DesignBitmapReplace) return false
            return png.contentEquals(other.png)
        }
        override fun hashCode(): Int = png.contentHashCode()
    }
}
