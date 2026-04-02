# Design Spec: Stencil Layer Alignment and Source-Space Compositing

## Problem Statement
Currently, generated stencil layers suffer from alignment "drift" because they are generated in **Screen Space** (matching the device resolution) while being rendered in the **Editor/AR Space** (using a fixed 2048x2048 canvas or raw bitmap dimensions). This discrepancy in coordinate systems causes layers to diverge when scaled or rotated, even if they appear aligned at identity transforms.

## Proposed Solution: Source-Space Alignment (Approach 1)
Instead of compositing layers to the screen size, we will composite all linked layers into the **Local Coordinate Space** of the "Anchor" layer (the layer the user selected to generate the stencil from).

### 1. Anchor-Relative Compositing
We will update the stencil pipeline to create a composite bitmap that matches the `width` and `height` of the anchor layer's source bitmap.

- **Target Dimensions:** `anchor.bitmap.width` x `anchor.bitmap.height`.
- **Transformation:** Every other linked layer will be projected onto this "Anchor Canvas" using a relative transformation matrix.

### 2. Matrix Inversion Logic
To ensure the stencil looks exactly like what the user sees on screen, we must calculate the relative transform between each linked layer ($L$) and the anchor layer ($A$):

$$M_{relative} = M_A^{-1} \times M_L$$

Where:
- $M_L$ is the screen-space matrix of the linked layer (including its offset, scale, and rotation).
- $M_A^{-1}$ is the inverse of the anchor layer's screen-space matrix.

This math cancels out the screen-specific offsets and `ContentScale.Fit` scaling, leaving only the relative pixel offsets between the images.

### 3. Layer Property Synchronization
When the stencil is generated and added to the project, it will be initialized with the **identical transformation state** as the anchor:
- `scale = anchor.scale`
- `offset = anchor.offset`
- `rotationZ = anchor.rotationZ`
- `isLinked = true`

Since the stencil bitmap and the anchor bitmap now have identical pixel dimensions, these shared properties will result in a perfect stack.

## Components and Changes

### `ExportManager` (core:feature:editor)
- Add `compositeToLayerSpace(anchorLayer: Layer, linkedLayers: List<Layer>): Bitmap`.
- Refactor existing `compositeLayers` to share common matrix calculation logic.

### `EditorViewModel` (feature:editor)
- Update `onGenerateStencil` to:
    1. Identify the "Anchor" layer.
    2. Call `compositeToLayerSpace` instead of the screen-space version.
    3. Pass the resulting bitmap to `StencilProcessor`.
    4. Apply anchor properties to the new `Layer` instance upon completion.

### `StencilProcessor` (feature:editor)
- No core logic changes required, but must handle bitmaps that may now be larger or smaller than the screen (already handled by existing `downsample` logic).

## Testing Plan
- **Unit Test (ExportManager):** Verify that `compositeToLayerSpace` correctly aligns a smaller linked image relative to a larger anchor image.
- **Integration Test (EditorViewModel):** Verify that a newly created stencil layer has the exact same `scale` and `offset` as its source.
- **Visual Verification:** Manually verify that scaling a linked group in Mockup mode does not cause the stencil to "drift" from the source image.

## Risks and Mitigation
- **Memory Pressure:** If the anchor layer is extremely high resolution (e.g. 4k+), compositing may hit OOM. 
    - *Mitigation:* `ExportManager` will cap the composite size to 2048px (maintaining aspect ratio), consistent with the `SubjectIsolator` limits.
- **Clipping:** If a linked layer is far outside the anchor's bounds, it will be clipped in the stencil.
    - *Mitigation:* This is expected behavior for "individual layer bounds" (Option 2). Users can relink/reorder to change the anchor.
