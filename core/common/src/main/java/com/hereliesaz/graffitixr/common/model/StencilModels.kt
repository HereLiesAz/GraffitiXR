// FILE: core/common/src/main/java/com/hereliesaz/graffitixr/common/model/StencilModels.kt
package com.hereliesaz.graffitixr.common.model

import android.graphics.Bitmap
import android.net.Uri

/**
 * The tonal role of a generated stencil layer.
 * Layers are applied bottom-to-top: Silhouette first, Highlight last.
 * OVERPAINT topology means no bridging is required — upper-layer islands are
 * physically supported by the surrounding sheet material.
 */
enum class StencilLayerType(val label: String, val order: Int) {
    SILHOUETTE("Silhouette", 1),
    MIDTONE("Midtone", 2),
    HIGHLIGHT("Highlight", 3)
}

/**
 * A single processed stencil layer, ready for preview and PDF output.
 * Bitmap is always ARGB_8888, white background, black content, registration marks included.
 */
data class StencilLayer(
    val type: StencilLayerType,
    val bitmap: Bitmap,
    val label: String = "Layer ${type.order} – ${type.label}"
)

/**
 * How many layers the stencil pipeline should generate.
 */
enum class StencilLayerCount(val count: Int, val displayLabel: String) {
    ONE(1, "1"),
    TWO(2, "2"),
    THREE(3, "3")
}

/**
 * Which real-world dimension the user has locked for PDF tiling.
 */
enum class StencilPrintDimension { WIDTH, HEIGHT }

/**
 * The current step of the guided stencil creation wizard.
 * The AzNavRail shows only items relevant to the active step.
 */
enum class StencilWizardStep {
    PICK_SOURCE,    // Select which image layer to use as input
    ISOLATE,        // Run SubjectIsolator; confirm segmentation result
    CHOOSE_LAYERS,  // Pick 1, 2, or 3 stencil layers
    GENERATE,       // Processing in progress (no user actions)
    PREVIEW,        // Cycle through generated layers; option to rebuild
    PRINT_EXPORT    // Print size, PDF, PNG, return to editor
}

/**
 * Full UI state for the Stencil screen.
 */
data class StencilUiState(
    /** ID of the editor layer this stencil was built from. Null = none selected yet. */
    val sourceLayerId: String? = null,

    /** Current step in the stencil creation wizard. */
    val wizardStep: StencilWizardStep = StencilWizardStep.PICK_SOURCE,

    /** The isolated (background-removed) bitmap from the ISOLATE step.
     *  Always the downsampled version (≤2048px) — not the full-res original.
     *  Null until the user completes and confirms the ISOLATE step. */
    val isolatedBitmap: Bitmap? = null,

    /** Number of stencil layers to generate. */
    val layerCount: StencilLayerCount = StencilLayerCount.TWO,

    /** Generated stencil layers, ordered bottom-to-top (Silhouette first). */
    val stencilLayers: List<StencilLayer> = emptyList(),

    /** Index into stencilLayers for the currently previewed layer. */
    val activeStencilLayerIndex: Int = 0,

    /** True while StencilProcessor is running. */
    val isProcessing: Boolean = false,

    /** 0..1 fraction for the progress bar. */
    val processingProgress: Float = 0f,

    /** Human-readable description of the current pipeline stage. */
    val processingStage: String = "",

    /** Real-world size value in millimetres for the locked print dimension. */
    val printSizeMm: Float = 300f,

    /** Whether the locked dimension is width or height. */
    val printDimension: StencilPrintDimension = StencilPrintDimension.WIDTH,

    /** Computed total page count across all layers. Updated whenever printSizeMm changes. */
    val totalPageCount: Int = 0,

    /** Non-null when PDF generation failed. */
    val exportError: String? = null,

    /** Non-null when PDF has been successfully written and is ready to share. */
    val exportedPdfUri: Uri? = null,

    /** True while the PDF is being generated. */
    val isExporting: Boolean = false
)
