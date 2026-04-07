# Design Spec: Binary Tonal Stencil Pair and Solid Silhouette Base

## Problem Statement
Currently, the stencil pipeline generates an inconsistent number of layers based on "Subject Contrast," which often results in a single black shadow layer (as seen in the user's screenshot). Without its corresponding highlights, this layer looks like a "hole-filled" mask on a grey background. Additionally, there is no solid "base" layer to serve as a primer for the mural.

## Proposed Solution: Binary Tonal Pair Strategy
The app will now **ALWAYS** generate exactly two layers to ensure a complete tonal foundation for the mural: a **Solid Base** and a **Contrast Detail**.

### 1. Dominant Luminance Assessment
The app will calculate the average luminance of the isolated subject (or the entire image if no subject is found).
- **Dark-Dominant (Luminance ≤ 0.5):** 
    - **Base:** Solid Black (Silhouette or Background).
    - **Detail:** White Highlights (extracted from light pixels).
- **Light-Dominant (Luminance > 0.5):**
    - **Base:** Solid White (Silhouette or Background).
    - **Detail:** Black Shadows (extracted from dark pixels).

### 2. Solid Base Generation
Instead of a tonal layer, the **Base Layer** will be a solid "shape" of the subject.
- **Silhouette "to be had":** Use the subject mask (segmentation) and fill it 100% with the chosen dominant color (Black or White).
- **No Silhouette:** Fill the entire image canvas with the chosen color.
This creates the "Solid Primer" the user requested.

### 3. Contrast Detail Extraction
The **Detail Layer** will be generated using K-Means clustering (K=2) on the subject's pixels.
- If the Base is Black, the Detail Layer will contain the **lightest** cluster (White).
- If the Base is White, the Detail Layer will contain the **darkest** cluster (Black).
This ensures the "negative space" seen in the user's screenshot is filled with the correct detail.

### 4. Automatic Canvas Synchronization
To provide immediate visual feedback in the Editor:
- The `EditorViewModel` will automatically update the `EditorUiState.canvasBackground` to match the color of the generated **Base Layer**.
- This ensures the "holes" in the detail layer correctly reveal the background color, matching the user's mental model.

## Components and Changes

### `StencilProcessor` (feature:editor)
- Update `process` to always return exactly two layers: `Base` and `Detail`.
- Modify `assessSubjectContrast` to return a `TonalPolarity` (Dark vs. Light) instead of a layer count.
- Update `kmeansLayers` to handle the generation of the solid base from the subject mask.

### `EditorViewModel` (feature:editor)
- Update `onGenerateStencil` to handle the two-layer output.
- Implement automatic `canvasBackground` updates based on the base layer color.
- Ensure both layers are added to the stack and linked correctly.

## Testing Plan
- **Unit Test (StencilProcessor):** Verify that every image (dark or light) results in exactly one black and one white layer.
- **Integration Test (EditorViewModel):** Verify that the canvas background color changes when a new stencil pair is generated.
- **Visual Verification:** Confirm that generating a stencil for the portrait in the user's screenshot results in a solid black silhouette + white highlights.

## Risks and Mitigation
- **Flat Images:** Images with zero contrast may result in empty detail layers.
    - *Mitigation:* `StencilProcessor` will add a tiny amount of noise or fallback to a default detail pattern if K-Means fails.
