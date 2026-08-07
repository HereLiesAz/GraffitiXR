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
