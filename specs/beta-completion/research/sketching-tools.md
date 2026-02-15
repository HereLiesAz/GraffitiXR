# Research: Sketching Tools

## Objective
Implement Photoshop-like drawing tools: Brush, Eraser, Blur, Blend, Liquify, Burn, Dodge, Heal.

## Options
1.  **Custom Implementation (Canvas/Bitmap):**
    *   *Pros:* Full control, no external deps.
    *   *Cons:* "Liquify" and "Heal" are mathematically complex to implement from scratch efficiently in Kotlin/Java.
    *   *Feasibility:* Brush/Eraser is easy. Blend modes are easy (PorterDuff). The rest are hard.

2.  **Native (C++/OpenCV):**
    *   *Pros:* Performance, OpenCV has inpainting (Heal), warping (Liquify).
    *   *Cons:* JNI overhead for real-time interaction might be laggy if not careful.
    *   *Feasibility:* High. We already have OpenCV linked.

3.  **Android Graphics Libraries:**
    *   *Libraries:* `Coil` (transformations), `GPUImage` (filters).
    *   *Cons:* Usually filter-based, not "brush-based" local editing.

## Recommendation
*   **Basic Tools (Brush, Eraser, Solid Color):** Implement in Kotlin/Compose `Canvas`.
*   **Complex Tools (Liquify, Heal, Burn/Dodge):** Use OpenCV via JNI.
    *   *Burn/Dodge:* Pixel shaders or simple pixel arithmetic (Kotlin is fine for simple circular brushes, or Fragment Shader).
    *   *Liquify:* Mesh warp or displacement map. OpenCV `remap` or OpenGL shader.
    *   *Heal:* OpenCV `inpaint`.

## Implementation Plan
*   **Brush/Eraser:** `Path` on a `Canvas` backing a `Bitmap`.
*   **Burn/Dodge:** `Paint` with custom `PorterDuffXfermode` or `ColorFilter`.
*   **Blur/Blend:** RenderScript (deprecated) or Vulkan/OpenGL shader. *Decision: Use our existing Vulkan/GLES engine or simple box blur in Kotlin for small radii.*
*   **Liquify:** Grid-based mesh warp (already have `WarpableImage` logic? Can we reuse?).
*   **Heal:** Send bitmap region to C++, run `cv::inpaint`, return result.
