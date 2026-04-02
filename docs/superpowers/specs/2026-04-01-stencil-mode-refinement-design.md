# Stencil Mode Refinement Design

**Goal:** Improve Stencil Mode by adding automatic subject assessment, fixing layer alignment (Z-plane), and improving visibility during processing with a localized "Press again" hint.

## 1. Automatic Subject Assessment

The app will intelligently choose the best stencil "recipe" for an image based on its tonal characteristics.

### 1.1 Assessment Heuristic
A new method `StencilProcessor.assessSubjectContrast(Bitmap)` will calculate the standard deviation of luminance ($L$) for all non-transparent pixels.

- **High Variance ($\sigma > 0.2$):** Candidate for **Shadows Only**.
    - Result: `StencilLayerCount.ONE`.
    - Pipeline produces a single `SILHOUETTE` layer containing only the darkest cluster (shadows).
- **Low Variance ($\sigma \le 0.2$):** Candidate for **Silhouette + Highlights**.
    - Result: `StencilLayerCount.TWO`.
    - Pipeline produces a solid black `SILHOUETTE` layer (subject boundary) and a white `HIGHLIGHT` layer (peak tones).

### 1.2 Integration
`EditorViewModel.onGenerateStencil` will call this assessment after the initial isolation.
If stencils already exist for the source layer, subsequent presses will increment the complexity (e.g., adding midtones) regardless of the heuristic.

## 2. Layer Alignment (The "Z-Plane" Fix)

Stencil layers must share the spatial context of their source image to avoid "jumping" when created.

### 2.1 Logic
In `EditorViewModel.runStencilPipeline`, the construction of the new `Layer` object will explicitly inherit the transform parameters of the `sourceLayer`:
- `scale`
- `offset`
- `rotationX`, `rotationY`, `rotationZ`
- `warpMesh`

## 3. "Press Again" Instruction Hint

A non-blocking instruction will appear next to the Stencil button to guide the user toward multi-layer stencils.

### 3.1 Button Tracking
In `MainActivity.kt`, the Stencil `azRailItem` will use `AzComposableContent` containing a `Box` with `onGloballyPositioned`. This captures the button's screen coordinates into `EditorUiState.stencilButtonPosition`.

### 3.2 UI Overlay
A floating `Box` in the `onscreen` block of `MainActivity` will render:
- **Text:** "Press again to add a layer."
- **Trigger:** `EditorUiState.stencilHintVisible` set to `true` for 3000ms after a generation finishes.
- **Positioning:** Offset based on `stencilButtonPosition`.

## 4. Non-Blocking Visibility

The full-screen spinner currently hides the canvas, preventing the user from seeing the results in context.

### 4.1 State Change
- Remove `isLoading = true` from the stencil pipeline.
- Add `isStencilGenerating: Boolean` to `EditorUiState`.

### 4.2 Localized Indicator
In `MainActivity.kt`, while `isStencilGenerating` is true, show a small progress indicator or "GENERATING..." label next to the Stencil button using the same `stencilButtonPosition` logic.

## 5. Components and Data Flow

- **StencilProcessor**: Adds `assessSubjectContrast`.
- **EditorUiState**: Adds `isStencilGenerating`, `stencilButtonPosition`, and `stencilHintVisible`.
- **EditorViewModel**: Manages the refined pipeline and state transitions.
- **MainActivity**: Implements the coordinate tracking and instruction overlay.
