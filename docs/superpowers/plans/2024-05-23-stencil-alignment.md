# Stencil Layer Alignment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix stencil alignment drift by compositing in anchor-layer space instead of screen space.

**Architecture:** Use matrix inversion to map linked layers from screen space to the anchor layer's local pixel space. Synchronize the new stencil layer's transformation properties with the anchor layer.

**Tech Stack:** Kotlin, Android Bitmap/Canvas/Matrix, Jetpack Compose.

---

### Task 1: Refactor ExportManager with Layer-Space Compositing

**Files:**
- Modify: `feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/export/ExportManager.kt`

- [ ] **Step 1: Extract Screen-Space Matrix calculation logic**
  Introduce a private `getLayerScreenMatrix` method to centralize the `ContentScale.Fit` and user transformation logic.

- [ ] **Step 2: Add `compositeToLayerSpace` method**
  Implement the matrix inversion logic to project linked layers onto a canvas matching the anchor layer's dimensions.

- [ ] **Step 3: Update existing `compositeLayers` to use the new helper**
  Ensure consistency by using `getLayerScreenMatrix` in the standard screen-export path.

- [ ] **Step 4: Commit**
  `git commit -m "feat(editor): add anchor-relative compositing to ExportManager"`

### Task 2: Update EditorViewModel to use Source-Space Pipeline

**Files:**
- Modify: `feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/EditorViewModel.kt`

- [ ] **Step 1: Update `onGenerateStencil` to use `compositeToLayerSpace`**
  Modify the `onGenerateStencil` method to identify the anchor layer (the selected layer) and use the new compositing method.

- [ ] **Step 2: Initialize new Stencil Layer with anchor properties**
  Update `runStencilPipeline` to copy the `scale`, `offset`, and `rotation` properties from the source layer to the new stencil layer.

- [ ] **Step 3: Commit**
  `git commit -m "feat(editor): align stencil layers to source using anchor properties"`

### Task 3: Verification

- [ ] **Step 1: Verify the alignment**
  Manually verify that a newly created stencil layer perfectly overlaps its source and moves/scales with it.
