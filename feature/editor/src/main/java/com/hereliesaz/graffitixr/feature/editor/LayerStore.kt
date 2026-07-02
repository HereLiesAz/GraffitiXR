package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-layer bitmap/stroke caches for the editor, extracted from EditorViewModel so the cache
 * bookkeeping has one named, thread-safe home instead of ~30 scattered raw-map accesses.
 *
 * - [baseBitmaps] holds each layer's unedited base bitmap (strokes are replayed onto a copy of it).
 * - [layerStrokes] holds the ordered brush strokes applied to each layer.
 *
 * ConcurrentHashMap because these are read/written from the project-collect coroutine, stroke
 * handlers, and the main thread (the previous plain maps threw ConcurrentModificationException).
 * The map only protects its own structure, though — each per-layer stroke list is additionally
 * guarded by synchronizing on the list itself, and [strokes] hands out snapshot copies, never
 * the live list (background compositing iterates while strokes land on other threads).
 * The compositing of base + strokes into a display bitmap stays in the ViewModel — it needs the
 * UiState, OpenCV/SLAM, and persistence — this class only owns the caches.
 */
internal class LayerStore {
    private val baseBitmaps = ConcurrentHashMap<String, Bitmap>()
    private val layerStrokes = ConcurrentHashMap<String, MutableList<StrokeCommand>>()

    /** Stores [bitmap] as the base for [layerId]. Callers pass a defensive copy if needed. */
    fun putBase(layerId: String, bitmap: Bitmap) {
        baseBitmaps[layerId] = bitmap
    }

    /** Resets [layerId]'s stroke list to empty. */
    fun initStrokes(layerId: String) {
        layerStrokes[layerId] = mutableListOf()
    }

    fun base(layerId: String): Bitmap? = baseBitmaps[layerId]

    /**
     * The strokes for [layerId] in application order (empty if the layer is unknown).
     *
     * Returns a snapshot copy: the live list keeps mutating on other threads (stroke handlers on
     * main, spectator-op replay) while DrawingEngine.composite iterates the result on
     * Dispatchers.Default — handing out the live list threw ConcurrentModificationException.
     * Stroke lists are small path metadata, so the copy is negligible next to compositing.
     */
    fun strokes(layerId: String): List<StrokeCommand> {
        val list = layerStrokes[layerId] ?: return emptyList()
        return synchronized(list) { list.toList() }
    }

    /** Appends [command] to [layerId]'s strokes, creating the list if absent. */
    fun addStroke(layerId: String, command: StrokeCommand) {
        // computeIfAbsent, not getOrPut: getOrPut on a ConcurrentHashMap is check-then-act, so two
        // racing first-strokes could each install their own list and one stroke would vanish.
        val list = layerStrokes.computeIfAbsent(layerId) { mutableListOf() }
        synchronized(list) { list.add(command) }
    }

    /**
     * Removes the most recent stroke for [layerId]. Returns false if the layer has no stroke list
     * at all (preserving the caller's early-return on an unknown layer); true otherwise, even when
     * the list was already empty.
     */
    fun removeLastStroke(layerId: String): Boolean {
        val list = layerStrokes[layerId] ?: return false
        synchronized(list) {
            if (list.isNotEmpty()) list.removeAt(list.lastIndex)
        }
        return true
    }

    /** Drops both caches for [layerId]. */
    fun remove(layerId: String) {
        baseBitmaps.remove(layerId)
        layerStrokes.remove(layerId)
    }

    /** Clears all cached bitmaps and strokes (e.g. on project unload). */
    fun clear() {
        baseBitmaps.clear()
        layerStrokes.clear()
    }
}
