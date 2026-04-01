# Stencil Detail & Density Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement detail simplification (Median Blur) and tonal control (Bias) in the Stencil Mode generation pipeline, mapping these parameters to the existing threshold slider.

**Architecture:** Modify `StencilProcessor` to pre-process the V-channel (Luminance) using OpenCV `medianBlur` and `convertTo` based on a new `influence` parameter. Update `EditorViewModel` to pass the UI slider value into the pipeline.

**Tech Stack:** Kotlin, OpenCV (Android), Jetpack Compose.

---

### Task 1: Update StencilProcessor API

**Files:**
- Modify: `feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/stencil/StencilProcessor.kt`

- [ ] **Step 1: Add `influence` parameter to `processSingle` and `kmeansLayers`**

```kotlin
// In StencilProcessor.kt

fun processSingle(
    isolatedBitmap: Bitmap,
    type: StencilLayerType,
    totalCount: StencilLayerCount,
    influence: Float = 0.5f // Add this
): Flow<StencilProgress> = flow {
    // ...
    val allLayers = kmeansLayers(isolatedBitmap, subjectMask, totalCount, influence) // Pass it
    // ...
}

private fun kmeansLayers(
    isolated: Bitmap,
    subjectMask: Bitmap,
    layerCount: StencilLayerCount,
    influence: Float // Add this
): List<StencilLayer> {
    // ...
}
```

- [ ] **Step 2: Implement pre-processing in `kmeansLayers`**
Apply the median blur and tonal bias before extracting byte data for K-means.

```kotlin
// Inside kmeansLayers in StencilProcessor.kt

val vChannel = channels[2]   // V = luminance (index 2 in HSV)
channels[0].release(); channels[1].release()

// --- Add this block ---
// 1.5 Detail Simplification: Median Blur (ksize must be odd)
val ksizeRaw = (15 - (influence * 14).toInt())
val ksize = if (ksizeRaw % 2 == 0) ksizeRaw + 1 else ksizeRaw
if (ksize > 1) {
    Imgproc.medianBlur(vChannel, vChannel, ksize)
}

// 1.6 Tonal Bias: Shift brightness (alpha=1.0, beta=bias)
// influence = 0.0 -> -40; influence = 0.5 -> 0; influence = 1.0 -> +40
val bias = (influence - 0.5f) * 80.0
vChannel.convertTo(vChannel, -1, 1.0, bias)
// ----------------------

// 2. Get subject pixels only (mask out background) ...
```

- [ ] **Step 3: Commit**

```bash
git add feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/stencil/StencilProcessor.kt
git commit -m "feat(stencil): add influence parameter to StencilProcessor for detail control"
```

---

### Task 2: Update EditorViewModel to Pass Influence

**Files:**
- Modify: `feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/EditorViewModel.kt`

- [ ] **Step 1: Update `runStencilPipeline` signature to accept `influence`**

```kotlin
// In EditorViewModel.kt

private suspend fun runStencilPipeline(
    isolated: Bitmap,
    type: StencilLayerType,
    count: StencilLayerCount,
    sourceLayerId: String,
    projectId: String,
    influence: Float // Add this
) {
    stencilProcessor.processSingle(isolated, type, count, influence).collect { progress -> // Pass it
        // ...
    }
}
```

- [ ] **Step 2: Update `dismissSegmentationSlider` to pass influence**

```kotlin
// In EditorViewModel.kt

fun dismissSegmentationSlider() {
    // ...
    val influence = _uiState.value.segmentationInfluence // already retrieved
    // ...
    if (stencilType != null && stencilCount != null && stencilSourceLayerId != null && stencilProjectId != null) {
        _uiState.update { it.copy(isLoading = true) }
        viewModelScope.launch(dispatchers.default) {
            val isolated = if (confidence != null && source != null)
                subjectIsolator.applyConfidenceThreshold(source, confidence, influence, 0.1f)
            else source ?: return@launch
            // Pass influence here
            runStencilPipeline(isolated, stencilType, stencilCount, stencilSourceLayerId, stencilProjectId, influence)
        }
    }
}
```

- [ ] **Step 3: Update direct call to `runStencilPipeline`**
Ensure any other direct calls (like in `onGenerateStencil` failure path) also pass a default influence.

```kotlin
// In EditorViewModel.kt, inside onGenerateStencil catch/else block:
runStencilPipeline(composite, nextType, totalCount, layerId, projectId, 0.5f)
```

- [ ] **Step 4: Commit**

```bash
git add feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/EditorViewModel.kt
git commit -m "feat(stencil): pass detail influence from UI to stencil pipeline"
```

---

### Task 3: Update AdjustmentsPanel UI Label

**Files:**
- Modify: `core/design/src/main/java/com/hereliesaz/graffitixr/design/components/AdjustmentsPanel.kt`

- [ ] **Step 1: Update the label in `SegmentationInfluenceRow`**
Change "Edge" to "Detail".

```kotlin
// In AdjustmentsPanel.kt

@Composable
fun SegmentationInfluenceRow(...) {
    // ...
    Text(
        "Detail", // was "Edge"
        color = Color.White,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.width(36.dp)
    )
    // ...
}
```

- [ ] **Step 2: Commit**

```bash
git add core/design/src/main/java/com/hereliesaz/graffitixr/design/components/AdjustmentsPanel.kt
git commit -m "ui(stencil): rename Edge slider label to Detail"
```

---

### Task 4: Verification

- [ ] **Step 1: Run Stencil generation and verify the "Detail" slider affects the output.**
    1. Open a project.
    2. Add an image.
    3. Tap "Stencil".
    4. Move the slider to 0.0, tap "Done". Verify output is simple/blobby.
    5. Re-run, move the slider to 1.0, tap "Done". Verify output is detailed.
