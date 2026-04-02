# Stencil Mode Refinement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Intelligently choose stencil recipes, fix layer alignment (Z-plane), and improve visibility during generation with localized hints.

**Architecture:**
- `StencilProcessor` performs luminance variance assessment.
- `EditorViewModel` synchronizes transforms and manages non-blocking generation state.
- `MainActivity` tracks button position via `onGloballyPositioned` and renders localized overlays.

**Tech Stack:** Kotlin, Jetpack Compose, AzNavRail, OpenCV.

---

### Task 1: Update Editor State Model

**Files:**
- Modify: `core/common/src/main/java/com/hereliesaz/graffitixr/common/model/EditorModels.kt`

- [ ] **Step 1: Add new fields to `EditorUiState`**

Add `isStencilGenerating`, `stencilButtonPosition`, and `stencilHintVisible` to `EditorUiState`.

```kotlin
data class EditorUiState(
    // ...
    val canvasBackground: Color = Color.Black,
    val isSegmenting: Boolean = false,
    val segmentationInfluence: Float = 0.5f,
    // ADD THESE:
    val isStencilGenerating: Boolean = false,
    val stencilButtonPosition: Offset = Offset.Zero,
    val stencilHintVisible: Boolean = false
)
```

- [ ] **Step 2: Commit**

```bash
git add core/common/src/main/java/com/hereliesaz/graffitixr/common/model/EditorModels.kt
git commit -m "chore(editor): add stencil refinement fields to EditorUiState"
```

---

### Task 2: Implement Contrast Assessment

**Files:**
- Modify: `feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/stencil/StencilProcessor.kt`

- [ ] **Step 1: Implement `assessSubjectContrast`**

Add the luminance standard deviation calculation.

```kotlin
// In StencilProcessor.kt
fun assessSubjectContrast(bitmap: android.graphics.Bitmap): StencilLayerCount {
    val w = bitmap.width
    val h = bitmap.height
    val pixels = IntArray(w * h)
    bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

    val luminanceValues = mutableListOf<Float>()
    for (pixel in pixels) {
        if (android.graphics.Color.alpha(pixel) > 0) {
            // Luminance = 0.299R + 0.587G + 0.114B
            val r = android.graphics.Color.red(pixel) / 255f
            val g = android.graphics.Color.green(pixel) / 255f
            val b = android.graphics.Color.blue(pixel) / 255f
            luminanceValues.add(0.299f * r + 0.587f * g + 0.114f * b)
        }
    }

    if (luminanceValues.isEmpty()) return StencilLayerCount.ONE

    val mean = luminanceValues.average().toFloat()
    val stdDev = kotlin.math.sqrt(luminanceValues.map { (it - mean) * (it - mean) }.average()).toFloat()

    // Threshold 0.2: Detailed/High-contrast candidates use Shadows Only (ONE cluster)
    // Flat candidates use Silhouette + Highlights (TWO clusters)
    return if (stdDev > 0.2f) StencilLayerCount.ONE else StencilLayerCount.TWO
}
```

- [ ] **Step 2: Commit**

```bash
git add feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/stencil/StencilProcessor.kt
git commit -m "feat(stencil): add luminance variance assessment to StencilProcessor"
```

---

### Task 3: Refine ViewModel Pipeline

**Files:**
- Modify: `feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/EditorViewModel.kt`

- [ ] **Step 1: Call assessment in `onGenerateStencil`**

Update `onGenerateStencil` to use the assessment result for the initial count.

```kotlin
// In onGenerateStencil(~line 1380)
val isolationResult = subjectIsolator.isolate(composite).getOrNull()
if (isolationResult != null) {
    // ...
    // Determine next stencil type and count
    val existingStencils = groupLayers.filter { it.stencilType != null }
    val (nextType, totalCount) = if (existingStencils.isEmpty()) {
        val assessedCount = stencilProcessor.assessSubjectContrast(isolationResult.isolatedBitmap)
        StencilLayerType.SILHOUETTE to assessedCount
    } else {
        // Fallback to existing logic for subsequent presses
        // ...
    }
}
```

- [ ] **Step 2: Fix Alignment in `runStencilPipeline`**

Ensure new layers inherit transforms.

```kotlin
// In runStencilPipeline(~line 1435)
val sourceLayer = _uiState.value.layers.find { it.id == sourceLayerId }
val newLayer = Layer(
    id = UUID.randomUUID().toString(),
    name = "Stencil${type.order} ${type.label}",
    uri = localUri,
    bitmap = stencilLayer.bitmap,
    isLinked = true,
    stencilType = type,
    stencilSourceId = sourceLayerId,
    // ALIGNMENT FIX:
    scale = sourceLayer?.scale ?: 1.0f,
    offset = sourceLayer?.offset ?: Offset.Zero,
    rotationX = sourceLayer?.rotationX ?: 0f,
    rotationY = sourceLayer?.rotationY ?: 0f,
    rotationZ = sourceLayer?.rotationZ ?: 0f,
    warpMesh = sourceLayer?.warpMesh ?: emptyList()
)
```

- [ ] **Step 3: Manage `isStencilGenerating` and Hint Timer**

Swap `isLoading` for `isStencilGenerating` and add the 3s hint timer after `StencilProgress.Done`.

```kotlin
// In onGenerateStencil:
// _uiState.update { it.copy(isLoading = true) } -> 
_uiState.update { it.copy(isStencilGenerating = true) }

// In runStencilPipeline -> StencilProgress.Done:
// _uiState.update { it.copy(..., isLoading = false) } ->
_uiState.update { s -> 
    s.copy(..., isStencilGenerating = false, stencilHintVisible = true) 
}
viewModelScope.launch {
    delay(3000)
    _uiState.update { it.copy(stencilHintVisible = false) }
}
```

- [ ] **Step 4: Commit**

```bash
git add feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/EditorViewModel.kt
git commit -m "feat(editor): implement transform inheritance and non-blocking stencil state"
```

---

### Task 4: UI Localized Overlays

**Files:**
- Modify: `app/src/main/java/com/hereliesaz/graffitixr/MainActivity.kt`

- [ ] **Step 1: Capture Stencil Button Position**

Update the Stencil `azRailItem` to use `onGloballyPositioned`.

```kotlin
// In ConfigureRailItems (~line 1083)
azRailItem(
    id = "stencil_${layer.id}",
    text = "Stencil",
    color = navItemColor,
    shape = AzButtonShape.RECTANGLE,
    content = AzComposableContent { isEnabled ->
        Box(
            Modifier.fillMaxSize()
                .onGloballyPositioned { coords ->
                    if (coords.isAttached) {
                        editorViewModel.updateStencilButtonPosition(coords.positionInWindow())
                    }
                }
        ) {
            // Render default icon/text or empty if AzNavRail handles it
        }
    }
) {
    activate()
    editorViewModel.onGenerateStencil(layer.id)
}
```
*Note:* I'll need to add `updateStencilButtonPosition(Offset)` to `EditorViewModel`.

- [ ] **Step 2: Render Hint and Generating Indicator**

In `MainActivity`'s `onscreen` block, add the floating overlays.

```kotlin
// In onscreen { ... }
if (editorUiState.stencilHintVisible || editorUiState.isStencilGenerating) {
    val pos = editorUiState.stencilButtonPosition
    val density = LocalDensity.current
    val offset = with(density) { IntOffset(pos.x.toInt() + 100.dp.roundToPx(), pos.y.toInt()) }
    
    Box(Modifier.offset { offset }) {
        if (editorUiState.isStencilGenerating) {
            Text("GENERATING...", color = Color.Cyan, fontWeight = FontWeight.Bold)
        } else if (editorUiState.stencilHintVisible) {
            Text("Press again to add a layer.", color = Color.White, modifier = Modifier.background(Color.Black.copy(alpha = 0.7f)).padding(8.dp))
        }
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/hereliesaz/graffitixr/MainActivity.kt
git commit -m "feat(ui): add stencil hint overlay and localized progress indicator"
```

---

### Task 5: Verification

- [ ] **Step 1: Verify Alignment**
Transform an image (move/rotate), then press Stencil.
Expected: The stencil layer appears exactly over the transformed image.

- [ ] **Step 2: Verify non-blocking UI**
Press Stencil.
Expected: The original image stays visible; no full-screen spinner; "GENERATING..." text appears next to the button.

- [ ] **Step 3: Verify Assessment**
Stencil a flat image vs. a high-contrast one.
Expected: Flat image gets 2 layers; detailed image gets 1 layer.
