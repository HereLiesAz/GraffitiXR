# Stencil Detail & Density Threshold Design

## Goal
Improve stencil quality by adding detail simplification (Median Blur) and tonal control (Bias) to the Stencil Mode generation pipeline, controlled by a single "Detail" slider.

## Architecture
We will modify the `StencilProcessor` to perform pre-processing on the luminance channel before K-means clustering occurs. This pre-processing is driven by an `influence` parameter (0.0 to 1.0) passed from the UI.

### Influence Mapping Logic
The `influence` parameter will simultaneously control two filters:

| Influence | Detail Level | Median Blur (ksize) | Tonal Bias (V-channel) | Resulting Look |
| :--- | :--- | :--- | :--- | :--- |
| **0.0** | Minimal | 15x15 | -40 | Large, smooth "blobs"; very light/shadow-only. |
| **0.5** | Balanced | 7x7 | 0 | Moderate detail; balanced tones. |
| **1.0** | Maximum | 1x1 (None) | +40 | High detail; noisy; heavy/dark tones. |

## Component Changes

### 1. `StencilProcessor.kt`
- Update `processSingle` and `kmeansLayers` to accept `influence: Float`.
- **Pre-processing in `kmeansLayers`**:
    - Extract V-channel (Luminance).
    - Apply `Imgproc.medianBlur(vChannel, vChannel, ksize)`.
    - Apply `vChannel.convertTo(vChannel, -1, 1.0, bias)` to shift brightness.
    - Proceed with K-means on the modified `vChannel`.

### 2. `EditorViewModel.kt`
- Update `dismissSegmentationSlider()` to pass `_uiState.value.segmentationInfluence` to `runStencilPipeline`.
- Update `runStencilPipeline` to accept and pass the influence to `stencilProcessor.processSingle`.

### 3. `AdjustmentsPanel.kt`
- Change the `SegmentationInfluenceRow` label from "Edge" to "Detail".

## Success Criteria
- Stencil layers generated at high "Detail" (1.0) preserve fine textures.
- Stencil layers generated at low "Detail" (0.0) are simplified into large, rounded shapes with no noise.
- The "Detail" slider effectively shifts the tonal balance, allowing users to "fill in" more black by sliding to the right.
