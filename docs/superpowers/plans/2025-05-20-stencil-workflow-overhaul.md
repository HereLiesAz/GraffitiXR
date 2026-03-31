# Stencil Workflow Overhaul Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Scrap the dedicated Stencil wizard and integrate stencil generation directly into the layer editing tools. Users can generate stencil layers (Silhouette, Highlight, Midtone) from any image/text/sketch layer group.

**Architecture:**
- Extend the `Layer` model to identify stencil types and their source layers.
- Refactor `StencilProcessor` for single-layer extraction from composite bitmaps.
- Update `EditorViewModel` with logic for sequential stencil generation and poster export.
- Update `MainActivity` to add the "Stencil" button and "Generate Poster" context menu.
- Remove legacy `StencilViewModel` and `StencilScreen`.

**Tech Stack:** Kotlin, Jetpack Compose, AzNavRail, OpenCV (for stencil processing), Android PdfDocument.

---

### Task 1: Update Layer Model and State

**Files:**
- Modify: `core/common/src/main/java/com/hereliesaz/graffitixr/common/model/EditorModels.kt`

- [ ] **Step 1: Add stencil properties to Layer data class**

```kotlin
data class Layer(
    val id: String,
    val name: String,
    // ... existing properties ...
    val stencilType: StencilLayerType? = null,
    val stencilSourceId: String? = null,
    // ...
)
```

- [ ] **Step 2: Commit**

```bash
git add core/common/src/main/java/com/hereliesaz/graffitixr/common/model/EditorModels.kt
git commit -m "model: add stencil properties to Layer"
```

### Task 2: Refactor StencilProcessor for Single Layer Generation

**Files:**
- Modify: `feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/stencil/StencilProcessor.kt`

- [ ] **Step 1: Update `process` signature to support single layer extraction**

```kotlin
fun processSingle(
    isolatedBitmap: Bitmap,
    type: StencilLayerType,
    totalCount: StencilLayerCount
): Flow<StencilProgress> = flow {
    // Similar to current process() but returns only the requested 'type'
}
```

- [ ] **Step 2: Implement logic to extract specific tonal layer**

- [ ] **Step 3: Commit**

```bash
git add feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/stencil/StencilProcessor.kt
git commit -m "feat: update StencilProcessor for single layer generation"
```

### Task 3: Implement Stencil Generation in EditorViewModel

**Files:**
- Modify: `feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/EditorViewModel.kt`

- [ ] **Step 1: Implement `onGenerateStencil(layerId: String)`**
  - Composite linked group.
  - Check existing stencils.
  - Determine next type (Stencil1 black, Stencil2 white, Stencil3 gray).
  - Invoke `StencilProcessor`.
  - Add new layer with `isLinked = true`.

- [ ] **Step 2: Implement `onGeneratePoster(layerId: String)`**
  - Collect all stencil layers in the group.
  - Launch the poster options dialog (handled in Task 4).

- [ ] **Step 3: Commit**

```bash
git add feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/EditorViewModel.kt
git commit -m "feat: implement stencil generation logic in EditorViewModel"
```

### Task 4: Integrate Stencil UI into Rail and Context Menu

**Files:**
- Modify: `app/src/main/java/com/hereliesaz/graffitixr/MainActivity.kt`

- [ ] **Step 1: Add "Stencil" button to `ConfigureRailItems`**
  - Visible for Image, Text, and Sketch layers.
  - Calls `editorViewModel.onGenerateStencil(layer.id)`.

- [ ] **Step 2: Add "Generate Poster" to stencil layer hidden menu**
  - Visible when `layer.stencilType != null`.
  - Calls `editorViewModel.onGeneratePoster(layer.id)`.

- [ ] **Step 3: Implement Poster Options Dialog**
  - Prompt for size and layer selection.
  - Call `StencilPrintEngine`.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/hereliesaz/graffitixr/MainActivity.kt
git commit -m "ui: integrate stencil buttons and poster dialog"
```

### Task 5: Cleanup Legacy Stencil Code

**Files:**
- Delete: `feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/stencil/StencilScreen.kt`
- Delete: `feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/stencil/StencilViewModel.kt`

- [ ] **Step 1: Delete old files**

- [ ] **Step 2: Remove Stencil route from Navigation**

- [ ] **Step 3: Commit**

```bash
git commit -m "cleanup: remove legacy stencil mode code"
```
