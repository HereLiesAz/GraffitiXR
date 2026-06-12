package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import com.hereliesaz.graffitixr.common.model.Tool
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import com.hereliesaz.graffitixr.feature.editor.util.ImageProcessor

/**
 * The stroke-compositing pipeline, extracted from EditorViewModel: turns a base bitmap plus
 * recorded [StrokeCommand]s into a rendered bitmap. This is the CPU/OpenCV-bound "how strokes
 * become pixels" logic; the ViewModel still owns when to invoke it, the dispatcher hop, the
 * UiState update, and persistence.
 *
 * Pure with respect to the editor's state (input bitmap + strokes → output bitmap); its only
 * dependencies are the OpenCV [ImageProcessor] and [SlamManager] (for Liquify warps). Callers
 * run these on a background dispatcher — they are CPU-heavy and must not touch the main thread.
 */
internal class DrawingEngine(private val slamManager: SlamManager) {

    /** Replays [strokes] in order onto a fresh mutable copy of [base], returning the result. */
    suspend fun composite(base: Bitmap, strokes: List<StrokeCommand>): Bitmap {
        var current = base.copy(Bitmap.Config.ARGB_8888, true)
        for (stroke in strokes) {
            val next = if (stroke.tool == Tool.LIQUIFY) applyLiquify(current, stroke)
            else applyTool(current, stroke, replaceExisting = true)
            // Recycle the superseded intermediate so a long stroke history doesn't churn many
            // full-resolution bitmaps into an OOM. Never recycle `base` (the caller owns it) or
            // a tool that mutated `current` in place and returned it.
            if (next !== current && current !== base) current.recycle()
            current = next
        }
        return current
    }

    /**
     * Applies a single freshly-drawn [command] onto [base] without re-replaying history — the
     * commit path for a just-finished stroke. (`replaceExisting = false` matches the original
     * incremental behavior.)
     */
    suspend fun applySingleStroke(base: Bitmap, command: StrokeCommand): Bitmap =
        applyTool(base, command, replaceExisting = false)

    private suspend fun applyTool(bitmap: Bitmap, stroke: StrokeCommand, replaceExisting: Boolean): Bitmap {
        val mapped = ImageProcessor.mapScreenToBitmap(
            stroke.path, stroke.canvasSize.width, stroke.canvasSize.height, bitmap.width, bitmap.height,
            stroke.layerScale, stroke.layerOffset, stroke.layerRotationZ
        )
        // brushSize is stored in screen px (what the rail size preview shows). Convert it to bitmap
        // space with the same scale the coordinates use, so the painted stroke renders at exactly the
        // previewed on-screen diameter regardless of the layer's resolution/scale.
        val brushScale = ImageProcessor.screenToBitmapScale(
            stroke.canvasSize.width, stroke.canvasSize.height, bitmap.width, bitmap.height, stroke.layerScale
        )
        return ImageProcessor.applyToolToBitmap(
            bitmap, mapped, stroke.tool, stroke.brushSize * brushScale, stroke.brushColor, stroke.intensity,
            replaceExisting, stroke.feathering
        )
    }

    private suspend fun applyLiquify(bitmap: Bitmap, stroke: StrokeCommand): Bitmap {
        slamManager.prepareLiquify(bitmap)
        val mapped = ImageProcessor.mapScreenToBitmap(
            stroke.path, stroke.canvasSize.width, stroke.canvasSize.height, bitmap.width, bitmap.height,
            stroke.layerScale, stroke.layerOffset, stroke.layerRotationZ
        )
        val flatArr = FloatArray(mapped.size * 2)
        mapped.forEachIndexed { i, pt -> flatArr[i * 2] = pt.x; flatArr[i * 2 + 1] = pt.y }
        slamManager.applyLiquify(flatArr, stroke.brushSize, 0.5f)
        val baked = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        slamManager.bakeLiquify(baked)
        return baked
    }
}
