# Stencil Mode — Implementation Plan
**GraffitiXR v1.21.0**
**Status:** Implemented

---

## 0. Overview

Stencil Mode is a guided multi-layer stencil generation and print pipeline built into
GraffitiXR's existing editor architecture. The user picks a source image, the pipeline
automatically separates it into 2 layers by default (optionally 1 or 3), previews each
layer side-by-side in the familiar layers panel, then prints all layers as a tiled,
outline-only PDF sized for US Letter paper at any user-specified real-world dimension.

**Layer count options:**
| Option | Layers Generated |
|--------|-----------------|
| 1 | Silhouette only |
| 2 (default) | Silhouette + Highlight |
| 3 | Silhouette + Midtone + Highlight |

**Island handling:** OVERPAINT sequential-layering strategy (per research doc) — no bridging.
Islands in upper layers are physically supported by the full sheet material around the cut holes.
No bridging algorithms required.

---

## 1. Architecture Integration Map

### New files to create

```
core/common/src/main/java/com/hereliesaz/graffitixr/common/model/StencilModels.kt
feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/stencil/
    StencilProcessor.kt        — image pipeline (segmentation → layer bitmaps)
    StencilPrintEngine.kt      — PDF tile generation
    StencilScreen.kt           — Compose UI screen
    StencilViewModel.kt        — state + coroutine orchestration
```

### Files to modify

| File | Change |
|------|--------|
| `core/common/.../EditorModels.kt` | Add `STENCIL` to `EditorMode` enum |
| `core/design/.../NavStrings.kt` | Add stencil nav strings |
| `app/.../MainActivity.kt` | Register `EditorMode.STENCIL` composable in `AzNavHost`; add mode sub-item to rail |
| `feature/editor/.../EditorUi.kt` | Handle STENCIL branch in `EditorOverlay` |

---

## 2. Data Models
**File:** `core/common/src/main/java/com/hereliesaz/graffitixr/common/model/StencilModels.kt`

```kotlin
enum class StencilLayerType {
    SILHOUETTE,   // Layer 1 — always present. Solid subject outline.
    MIDTONE,      // Layer 2 — optional (3-layer mode only). Mid-luminance band.
    HIGHLIGHT     // Layer 2 (2-layer) or Layer 3 (3-layer). Peak luminance.
}

data class StencilLayer(
    val type: StencilLayerType,
    val bitmap: Bitmap,             // Processed binary bitmap, ARGB_8888
    val label: String               // e.g. "Layer 1 – Silhouette"
)

enum class StencilLayerCount(val count: Int) { ONE(1), TWO(2), THREE(3) }

data class StencilUiState(
    val sourceLayerId: String? = null,           // Layer from editor this was built from
    val layerCount: StencilLayerCount = StencilLayerCount.TWO,
    val stencilLayers: List<StencilLayer> = emptyList(),
    val activeStencilLayerIndex: Int = 0,
    val isProcessing: Boolean = false,
    val processingProgress: Float = 0f,          // 0..1, for progress indicator
    val processingStage: String = "",            // e.g. "Segmenting subject…"
    val printWidthMm: Float = 300f,              // User-specified real-world width
    val printDimensionIsWidth: Boolean = true,   // false = height is the locked dimension
    val printPreviewPageCount: Int = 0,          // computed, shown in UI
    val exportError: String? = null,
    val exportedPdfUri: Uri? = null
)
```

---

## 3. Image Processing Pipeline
**File:** `feature/editor/.../stencil/StencilProcessor.kt`

Runs entirely on `Dispatchers.Default`. Emits progress via `Flow<StencilProgress>`.

### Stage 1 — Source prep
- Retrieve the active layer's `Bitmap` from `EditorUiState.layers`
- Downsample to max 2048px on longest edge to stay within ML Kit's recommended input size
  (full-res preserved separately for final bitmap generation)
- Convert to `ARGB_8888` if not already

### Stage 2 — Subject segmentation (OpenCV GrabCut)
- Inject and call `BackgroundRemover.removeBackground(bitmap)` — this is the existing
  OpenCV GrabCut pipeline, fully offline, no ML Kit dependency
- The returned `Bitmap` has the background erased (transparent pixels = background,
  opaque pixels = subject)
- Derive binary mask from alpha channel: `alpha > 0` → subject (white), else background (black)
- Output: `subjectMaskBitmap: Bitmap` (`ALPHA_8` config, white = subject, black = background)

  > **Note:** ML Kit `SubjectSegmenter` was deliberately removed from this project.
  > `BackgroundRemover` is the single source of truth for segmentation.

### Stage 3 — Contrast crush + posterization
- Apply `ColorMatrixColorFilter` with alpha = 2.5f, beta = -80f (per research doc params)
  to the source image, restricted to subject area via mask
- For 2-layer: clamp to binary (above/below 50% luminance)
- For 3-layer: posterize to 3 bands — shadows (<33%), midtones (33–66%), highlights (>66%)
  Using `ColorMatrixColorFilter` trick: multiply by 3.0, cast to int, divide back

### Stage 4 — Layer extraction (Porter-Duff compositing)

**1-layer:**
- `SILHOUETTE`: Subject mask, flood-filled solid black on white background

**2-layer:**
- `SILHOUETTE`: `DST_IN` — subject mask applied to pure black fill
- `HIGHLIGHT`: `SRC_IN` — highlight band masked to subject bounds, logical AND with mask
  → morphological closing (5×5 kernel, OpenCV `Imgproc.morphologyEx`) to smooth cut edges

**3-layer:**
- `SILHOUETTE`: same as 2-layer
- `MIDTONE`: midtone band, `SRC_IN` with subject mask, morphological closing
- `HIGHLIGHT`: highlight band, `SRC_IN` with subject mask, morphological closing

### Stage 5 — Registration marks injection
- Compute bounding box of `SILHOUETTE` layer
- Inject identical crosshair marks (+12px stroke, 0.35mm at output DPI) at all 4 corners
  of the bounding box into **every layer's bitmap**
- Marks are drawn pure black on every layer so they print on all sheets
- This enables physical alignment when layering sheets during spray application

### Stage 6 — Output
Return `List<StencilLayer>` ordered bottom-to-top (Silhouette first, Highlight last).
Each bitmap is `ARGB_8888`, background white, subject content black, marks black.

---

## 4. UI — StencilScreen
**File:** `feature/editor/.../stencil/StencilScreen.kt`

Mirrors the existing LAYERS panel UX. The AzNavRail right-side rail drives all controls.
No new navigation paradigm — fully consistent with the rest of the app.

### Layout
```
┌──────────────────────────────────────┬────────┐
│                                      │        │
│   StencilPreviewCanvas               │  Rail  │
│   (shows active layer bitmap,        │        │
│    full-screen, black on white bg)   │        │
│                                      │        │
│   Layer indicator strip at bottom:   │        │
│   [ ● Layer 1 ]  [ ○ Layer 2 ]       │        │
└──────────────────────────────────────┴────────┘
```

The layer indicator strip uses the same thumbnail style as the existing layers panel.
Tapping a thumbnail activates that layer for preview. The rail updates contextually.

### Rail structure (STENCIL mode)

```
▸ Stencil           ← host
    ▸ Layers: 1/2/3  ← segmented control, updates layer count + re-triggers pipeline
    ▸ Rebuild        ← re-run StencilProcessor on current source layer
▸ Print             ← host
    ▸ Width / Height ← toggle (tap to switch locked dimension)
    ▸ [Size input]   ← drag knob: up/down changes value in cm, shows page count live
    ▸ Print PDF      ← triggers StencilPrintEngine, opens share sheet on completion
▸ Export            ← host
    ▸ Save Layers    ← saves each StencilLayer bitmap to gallery as PNG
    ▸ Back to Editor ← pops back to MOCKUP mode
```

The "Width" / "Height" toggle and size knob follow the same drag-to-adjust pattern
already used for brush size sliders elsewhere in the app.

---

## 5. PDF Print Engine
**File:** `feature/editor/.../stencil/StencilPrintEngine.kt`

Pure Kotlin, no external library. Uses `android.graphics.pdf.PdfDocument`.

### Page spec
- Paper: US Letter — `8.5 × 11 in` = `2550 × 3300 px` at 300 DPI
- Printable area (accounting for typical printer margins, 0.25in each side):
  `2400 × 3150 px` (printable), leaving margins for label text at bottom

### Tile algorithm

```
Given:
  outputWidthPx   = user-specified real-world width in mm → converted to px at 300 DPI
                    (or height, whichever dimension is locked)
  bitmapW, bitmapH = source StencilLayer bitmap dimensions

1. Scale source bitmap to outputWidthPx × outputHeightPx (proportional)
2. overlapPx = ceil(3mm × 300/25.4)  = 36 px   ← 3mm bleed overlap
3. tileW = 2400 px (printable width)
4. tileH = 3000 px (printable height minus label zone at bottom = 150px reserved)
5. stride horizontal = tileW - overlapPx
6. stride vertical   = tileH - overlapPx
7. cols = ceil(outputWidthPx  / stride)
8. rows = ceil(outputHeightPx / stride)
9. Total pages per layer = rows × cols
```

### Per-page content

Each page is rendered to a `PdfDocument.Page` canvas:

1. **White background fill** — `canvas.drawColor(Color.WHITE)`
2. **Outline rendering** — do NOT fill solid shapes. Instead:
   - Take the tile's sub-bitmap
   - Run `Imgproc.Canny` (OpenCV) on the tile: threshold1=50, threshold2=150, apertureSize=3
   - OR (if OpenCV overhead is a concern): use Android `Paint` with `MaskFilter` set to
     `BlurMaskFilter(0f, BlurMaskFilter.Blur.INNER)` + `Xfermode(MULTIPLY)` to stroke edges
     **Preferred approach:** OpenCV `findContours` + draw contour paths with `strokeWidth = 1f`
     at the output DPI, giving a physical 0.35mm line (1pt at 300 DPI ≈ 4.2px → round to 4px)
3. **Overlap zone hatching** — draw a thin dashed line at the 3mm overlap boundary on
   all tiled edges (right edge and bottom edge of each tile) so the user knows where to
   align sheets when assembling
4. **Label block** (bottom 150px strip):
   - Layer name: e.g. `LAYER 1 / SILHOUETTE`
   - Grid position: e.g. `ROW 2  COL 3`
   - Total grid: e.g. `of 3×4 (12 pages)`
   - Print at 24pt bold, using `Typeface.MONOSPACE` — maximum legibility when trimming pages
5. **Registration mark** — if this tile contains a registration mark crosshair (detected by
   pixel presence at the expected corner coordinates), call it out with a small label arrow:
   `⊕ ALIGN MARK`

### Output structure

```
Layer 1 – Silhouette
  Page 1:  Row 1, Col 1
  Page 2:  Row 1, Col 2
  ...
  Page N:  Row R, Col C
[DIVIDER PAGE — plain text: "CUT HERE — END OF LAYER 1 / SILHOUETTE"]
Layer 2 – Highlight
  Page N+1: Row 1, Col 1
  ...
```

Divider pages are full-page text only — large font, white background, no ink-heavy content.

### Ink savings summary
- **Outline-only rendering** (no fills) — primary saving, estimated 85–95% ink reduction
  on large solid areas vs. a naive solid-fill print
- **White background** — no background ink ever
- **3mm overlap** — minimal bleed, sized to allow easy scissor/knife alignment without
  large wasted ink zones
- Registration marks are small crosshairs (~12px), not large corner brackets

---

## 6. ViewModel
**File:** `feature/editor/.../stencil/StencilViewModel.kt`
`@HiltViewModel`, `@Inject constructor(private val stencilProcessor: StencilProcessor, private val printEngine: StencilPrintEngine)`

### State
```kotlin
private val _uiState = MutableStateFlow(StencilUiState())
val uiState: StateFlow<StencilUiState> = _uiState.asStateFlow()
```

### Key actions
```kotlin
fun initFromLayer(layerId: String, bitmap: Bitmap)
fun setLayerCount(count: StencilLayerCount)
fun rebuild()                         // re-triggers full pipeline
fun setPrintDimension(mm: Float, isWidth: Boolean)
fun exportPdf(context: Context)
fun saveLayersToGallery(context: Context)
```

### Processing flow
```
rebuild() →
  launch(Dispatchers.Default) {
    stencilProcessor.process(sourceBitmap, layerCount)
      .collect { progress -> _uiState.update { it.copy(...progress fields) } }
  }
```

---

## 7. NavStrings additions
**File:** `core/design/.../NavStrings.kt`

```kotlin
val stencil: String = "Stencil",
val stencilInfo: String = "Generate printable multi-layer stencils from your artwork.",
val stencilLayers: String = "Layers",
val stencilLayersInfo: String = "Choose how many stencil layers to generate: 1 (silhouette only), 2 (silhouette + highlight), or 3 (silhouette + midtone + highlight).",
val stencilRebuild: String = "Rebuild",
val stencilRebuildInfo: String = "Re-run the stencil generation pipeline on the current source layer.",
val stencilPrint: String = "Print",
val stencilPrintInfo: String = "Configure the real-world print size and generate a tiled PDF for US Letter paper.",
val stencilPrintSize: String = "Size",
val stencilPrintSizeInfo: String = "Drag up/down to set the stencil's real-world width or height in centimetres. Page count updates live.",
val stencilPrintDimToggle: String = "W / H",
val stencilPrintDimToggleInfo: String = "Tap to switch between locking the width or the height of the output.",
val stencilExportPdf: String = "Print PDF",
val stencilExportPdfInfo: String = "Generate a tiled PDF of all stencil layers, ready to print and cut.",
val stencilSaveLayers: String = "Save PNGs",
val stencilSaveLayersInfo: String = "Save each stencil layer as a separate PNG image to your gallery.",
val stencilBack: String = "← Editor",
val stencilBackInfo: String = "Return to the main editor. Your stencil layers are preserved in the session.",
```

---

## 8. EditorMode registration

### `EditorModels.kt`
```kotlin
enum class EditorMode {
    TRACE, MOCKUP, OVERLAY, AR,
    STENCIL   // ← add
}
```

### `MainActivity.kt` — AzNavHost
```kotlin
composable(EditorMode.STENCIL.name) { StencilScreen(stencilViewModel, editorUiState) }
```

### Rail — mode sub-item
```kotlin
azRailSubItem(
    id = "stencil", hostId = "mode_host",
    text = navStrings.stencil,
    route = EditorMode.STENCIL.name,
    color = Color.White,
    shape = AzButtonShape.NONE,
    info = navStrings.stencilInfo
)
```

### `EditorUi.kt` — HelpModeText
```kotlin
EditorMode.STENCIL -> "Stencil Mode"
// description:
EditorMode.STENCIL -> "Generate and print multi-layer stencils from your artwork."
```

---

## 9. Dependencies

No new external dependencies required. Everything uses:
- `BackgroundRemover` + `ImageProcessor` (OpenCV GrabCut) — already in the project,
  handles all subject segmentation. ML Kit was intentionally removed from this project.
- `org.opencv:opencv` — already in the project (`:opencv` module)
- `android.graphics.pdf.PdfDocument` — Android SDK, API 19+
- `android.graphics.Canvas`, `Paint`, `PorterDuffXfermode` — existing patterns

---

## 10. Implementation Phases

### Phase A — Models + enum plumbing
1. Add `STENCIL` to `EditorMode`
2. Create `StencilModels.kt`
3. Add NavStrings
4. Register composable destination in `AzNavHost` (stub screen only)
5. Add mode rail sub-item
**Gate:** App builds, STENCIL appears in mode switcher, navigates to stub screen.

### Phase B — StencilProcessor
1. Implement Stage 1–3 (source prep, ML Kit segmentation, contrast crush)
2. Implement Stage 4 (layer extraction for 2-layer default)
3. Implement Stage 5 (registration marks)
4. Add 1-layer and 3-layer variants
5. Write `StencilProcessorTest` (JVM unit tests, mock `BackgroundRemover`, use synthetic bitmaps)
**Gate:** `StencilProcessorTest` green. Manual visual check on 2-layer output.

### Phase C — StencilViewModel + StencilScreen
1. `StencilViewModel` with state, progress flow, `rebuild()` action
2. `StencilScreen` — preview canvas + layer indicator strip
3. Rail: Layers count control, Rebuild button
4. Wire `initFromLayer()` call when entering STENCIL mode (pass active layer bitmap)
**Gate:** Full guided flow works end-to-end. Layer switching previews correct bitmaps.

### Phase D — StencilPrintEngine + PDF rail
1. Implement tile algorithm
2. Implement per-page outline rendering (OpenCV `findContours` path)
3. Implement label block + overlap hatch lines + registration mark callout
4. Implement divider pages
5. Rail: Size knob, W/H toggle, page count live preview, Print PDF action, Save PNGs action
6. Write `StencilPrintEngineTest` — verify tile count math, overlap values, page dimensions
**Gate:** PDF opens in viewer, all pages present, labels correct, overlap hatch visible.

### Phase E — Polish + edge cases
1. Handle source layer has no detectable subject (ML Kit confidence all < 0.5)
   → show error state in UI: "No subject detected. Try a photo with a clear foreground."
2. Handle very small images (< 200px) → show error: "Source image too small for stencil."
3. Handle print size so large it generates > 50 pages → warn user, suggest reducing size
4. Progress indicator during pipeline (show stage name + bar)
5. Share sheet integration for PDF export (Android `FileProvider` + `Intent.ACTION_SEND`)
6. Accessibility: content descriptions on all layer thumbnails and rail items

---

## 11. Open questions (decide before Phase D)

1. **Outline rendering method** — OpenCV `findContours` draw vs. Android `Paint` edge detection.
   OpenCV is already in the project and gives cleaner vector-like paths. Recommended unless
   the per-tile `findContours` call on 2400×3000 tiles is too slow (benchmark in Phase D).
   Fallback: Android `MaskFilter` + `MULTIPLY` xfermode on a copied tile bitmap.

2. **Print size input UX** — the drag-knob pattern (existing brush size control) works well
   for 1–500cm range. Confirm the drag sensitivity feels right at large values (e.g. 200cm),
   or add a direct numeric text input as a secondary option.

3. **PDF delivery** — share sheet (recommended) vs. save-to-Downloads silently vs. both.
   Share sheet is consistent with `ExportManager`'s existing export pattern.

---

*Plan authored: 2026-03-16. Next step: Phase A.*


---
*Documentation updated on 2026-03-17 during website redesign and Stencil Mode integration phase.*
