# STENCILS: Architectural Bible

This document details the design specifics, intricacies, and reasoning behind the GraffitiXR Stencil Creation feature, reflecting the active implementation.

## 1. Core Philosophy and Topology
The Stencil Creation feature in GraffitiXR is designed to generate physically reproducible stencils from digital assets. 

### Overpaint Topology
Unlike traditional single-layer stencils that require "bridges" to prevent internal "islands" from falling out, the system assumes an **OVERPAINT sequential-layering strategy**. 
- Layers are applied bottom-to-top (Base -> Detail).
- Because they are painted sequentially onto a solid substrate (e.g., wall), upper-layer cutouts (negative space) are naturally supported by the physical material. 
- Due to this, the algorithm does not need to compute artificial bridging.

## 2. Binary Tonal Stencil Pairs
In the past, stencil layer counts fluctuated based on subject contrast, leading to incomplete, "hole-filled" masks. The pipeline (`StencilProcessor.kt`) now enforces exactly **two functional layers** per stencil pair to create a complete tonal foundation.

### The Pipeline (`StencilProcessor`)
The extraction relies on evaluating the subject image and applying K-means clustering (K=2) on the HSV V-channel (luminance), running efficiently on `Dispatchers.Default`. The pipeline consists of four main stages:

#### Stage 1: Tonal Assessment
The system analyzes the pixels of the pre-isolated subject to calculate average luminance.
- **Dark-Dominant (Mean Luminance ≤ 0.5):** Base = Solid Black Silhouette; Detail = White Highlight.
- **Light-Dominant (Mean Luminance > 0.5):** Base = Solid White Silhouette; Detail = Black Shadow.
This guarantees the correct pair based purely on overall image brightness.

#### Stage 2: Subject Masking
The alpha channel of the isolated subject is directly converted into a binary ARGB_8888 mask. This mask delineates the exact absolute boundary for the Solid Base layer, entirely filling it with the designated base color from Stage 1. 

#### Stage 3: K-Means Detail Extraction
Inside the subject mask bounds, lighting features are extracted from the V-channel (brightness).
- **Detail Simplification:** A median blur is applied to reduce noise, creating more cohesive shapes rather than spotty artifacts.
- **Tonal Bias:** Brightness is shifted slightly based on a user-defined `influence` setting (0.0 to 1.0) to capture more or fewer details.
- **Clustering:** K-Means clustering (K=2) operates on the subject pixels. The cluster that contrasts the most with the Solid Base is populated as the Detail Layer.

#### Stage 4: Morphological Smoothing
To make the stencil practical for physical cutting (preventing excessively jagged edges):
- A morphological "closing" operation—dilation followed by erosion using a fixed kernel size—is applied exclusively to the **detail layer(s)**.
- The silhouette base mask is intentionally left untouched to perfectly preserve its original outer bounds.

## 3. Source-Space Compositing and Alignment
To prevent "alignment drift"—where stencil layers progressively diverge from their parent layers when scaled/rotated in the Editor—the system utilizes **Anchor-Relative Alignment**.

### Anchor-Relative Alignment (`ExportManager`)
Previously, compositing operated in *Screen Space*, tying the generated bitmap directly to the exact zoom and scroll of the editor view. The robust solution explicitly performs matrix math in the **Local Space** of the specific "Anchor" layer.

1. **Target Dimensions:** The composite canvas matches the original dimensions (width and height) of the anchor's underlying bitmap. To avoid out-of-memory (OOM) crashes on huge source images, dimensions are capped relative to 2048px while maintaining identical aspect ratios.
2. **Matrix Inversion:** The system fetches the anchor layer's screen-space matrix ($M_A$) and calculates its strict inverse ($M_A^{-1}$). 
3. **Relative Transformation:** For all linked layers ($L$), the system projects them onto the anchor canvas using the resultant relative matrix: $M_{relative} = M_A^{-1} \times M_L$ 
   *This mathematically cancels out standard screen offsets and dynamic canvas zoom/pan.*
4. **Property Synchronization:** Once processing finishes, the new Stencil Layer object inherits identical `scale`, `offset`, and `rotationZ` transform parameters as the original anchor layer. 

**Result:** Sharing identically scaled pixel grids and synchronized UI transformation properties ensures the Stencil output accurately mirrors the source element and behaves identically inside the Editor/AR space.
