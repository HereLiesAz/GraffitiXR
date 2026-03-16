// FILE: feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/stencil/StencilViewModel.kt
package com.hereliesaz.graffitixr.feature.editor.stencil

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hereliesaz.graffitixr.common.model.StencilLayerCount
import com.hereliesaz.graffitixr.common.model.StencilPrintDimension
import com.hereliesaz.graffitixr.common.model.StencilUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for Stencil Mode.
 *
 * Orchestrates the [StencilProcessor] pipeline and [StencilPrintEngine] PDF output.
 * All heavy work runs on Dispatchers.Default via the processor's Flow.
 *
 * Phase B: Pipeline + state wiring.
 * Phase C: UI integration (StencilScreen composable observes this).
 * Phase D: StencilPrintEngine wired to exportPdf / saveLayersToGallery.
 */
@HiltViewModel
class StencilViewModel @Inject constructor(
    private val stencilProcessor: StencilProcessor,
    private val printEngine: StencilPrintEngine
) : ViewModel() {

    private val _uiState = MutableStateFlow(StencilUiState())
    val uiState: StateFlow<StencilUiState> = _uiState.asStateFlow()

    /** Retains the source bitmap so Rebuild can re-run without re-receiving it. */
    private var sourceBitmap: Bitmap? = null
    private var sourceLayerId: String? = null

    /** Cancellable pipeline job — cancel before starting a new run. */
    private var processingJob: Job? = null

    // ── Entry point ───────────────────────────────────────────────────────────

    /**
     * Called when the user enters Stencil Mode with an active layer.
     * Stores the source and immediately kicks off the pipeline.
     */
    fun initFromLayer(layerId: String, bitmap: Bitmap) {
        if (sourceLayerId == layerId && _uiState.value.stencilLayers.isNotEmpty()) {
            // Same layer, layers already generated — nothing to do.
            return
        }
        sourceLayerId = layerId
        sourceBitmap = bitmap
        _uiState.update { it.copy(sourceLayerId = layerId) }
        runPipeline()
    }

    // ── User actions ──────────────────────────────────────────────────────────

    fun setLayerCount(count: StencilLayerCount) {
        if (_uiState.value.layerCount == count) return
        _uiState.update { it.copy(layerCount = count) }
        runPipeline()
    }

    fun rebuild() {
        runPipeline()
    }

    fun setActiveStencilLayer(index: Int) {
        _uiState.update { it.copy(activeStencilLayerIndex = index) }
    }

    fun setPrintSize(mm: Float) {
        val clamped = mm.coerceIn(50f, 5000f)
        val pageCount = computePageCount(clamped, _uiState.value.printDimension)
        _uiState.update { it.copy(printSizeMm = clamped, totalPageCount = pageCount) }
    }

    fun togglePrintDimension() {
        val next = if (_uiState.value.printDimension == StencilPrintDimension.WIDTH)
            StencilPrintDimension.HEIGHT else StencilPrintDimension.WIDTH
        val pageCount = computePageCount(_uiState.value.printSizeMm, next)
        _uiState.update { it.copy(printDimension = next, totalPageCount = pageCount) }
    }

    fun exportPdf(context: Context) {
        val layers = _uiState.value.stencilLayers
        if (layers.isEmpty()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isExporting = true, exportError = null) }
            val result = printEngine.generatePdf(
                context = context,
                layers = layers,
                printSizeMm = _uiState.value.printSizeMm,
                printDimension = _uiState.value.printDimension
            )
            result.fold(
                onSuccess = { uri ->
                    _uiState.update { it.copy(isExporting = false, exportedPdfUri = uri) }
                },
                onFailure = { e ->
                    _uiState.update { it.copy(isExporting = false, exportError = e.message) }
                }
            )
        }
    }

    fun saveLayersToGallery(context: Context) {
        val layers = _uiState.value.stencilLayers
        if (layers.isEmpty()) return
        viewModelScope.launch {
            printEngine.saveLayerPngs(context, layers)
        }
    }

    fun clearExportState() {
        _uiState.update { it.copy(exportedPdfUri = null, exportError = null) }
    }

    // ── Pipeline ──────────────────────────────────────────────────────────────

    private fun runPipeline() {
        val bitmap = sourceBitmap ?: return
        processingJob?.cancel()
        processingJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isProcessing = true,
                    processingProgress = 0f,
                    processingStage = "Starting…",
                    stencilLayers = emptyList()
                )
            }
            stencilProcessor.process(bitmap, _uiState.value.layerCount).collect { event ->
                when (event) {
                    is StencilProgress.Stage -> {
                        _uiState.update {
                            it.copy(
                                processingProgress = event.fraction,
                                processingStage = event.message
                            )
                        }
                    }
                    is StencilProgress.Done -> {
                        val pageCount = computePageCount(
                            _uiState.value.printSizeMm,
                            _uiState.value.printDimension,
                            event.layers.firstOrNull()?.bitmap
                        )
                        _uiState.update {
                            it.copy(
                                isProcessing = false,
                                processingProgress = 1f,
                                processingStage = "",
                                stencilLayers = event.layers,
                                activeStencilLayerIndex = 0,
                                totalPageCount = pageCount
                            )
                        }
                    }
                    is StencilProgress.Error -> {
                        _uiState.update {
                            it.copy(
                                isProcessing = false,
                                processingStage = "",
                                exportError = event.message
                            )
                        }
                    }
                }
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Computes total page count across all layers for a given print size.
     * Uses the first layer bitmap's aspect ratio if available; falls back to square.
     */
    private fun computePageCount(
        sizeMm: Float,
        dimension: StencilPrintDimension,
        referenceBitmap: Bitmap? = _uiState.value.stencilLayers.firstOrNull()?.bitmap
    ): Int {
        val layerCount = _uiState.value.stencilLayers.size.coerceAtLeast(1)
        val aspect = if (referenceBitmap != null && referenceBitmap.height > 0)
            referenceBitmap.width.toFloat() / referenceBitmap.height.toFloat()
        else 1f

        // Convert mm → px at 300 DPI
        val mmToPx = 300f / 25.4f
        val outputWidthPx: Float
        val outputHeightPx: Float
        if (dimension == StencilPrintDimension.WIDTH) {
            outputWidthPx = sizeMm * mmToPx
            outputHeightPx = outputWidthPx / aspect
        } else {
            outputHeightPx = sizeMm * mmToPx
            outputWidthPx = outputHeightPx * aspect
        }

        // US Letter printable area at 300 DPI, 0.25in margins each side
        val tileW = 2400f   // (8.5 - 0.5) * 300
        val tileH = 3000f   // label strip reserved: (11 - 0.5) * 300 - 150
        val overlapPx = 36f // 3mm * 300/25.4 ≈ 36px

        val cols = kotlin.math.ceil(outputWidthPx / (tileW - overlapPx)).toInt().coerceAtLeast(1)
        val rows = kotlin.math.ceil(outputHeightPx / (tileH - overlapPx)).toInt().coerceAtLeast(1)

        return cols * rows * layerCount
    }
}
