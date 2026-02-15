# Research: Export Logic

## Objective
Export project as:
1.  Single Image (UI hidden).
2.  Folder of Layer Images.

## Current State
*   Currently likely taking a `PixelCopy` of the SurfaceView or View.
*   Problem: Captures UI elements.

## Solution
1.  **Single Image:**
    *   Create a separate `Canvas` / `Bitmap` off-screen.
    *   Iterate all visible `Layer` objects.
    *   Draw them onto the canvas with their respective transforms (Scale, Rotate, Translate) and Blend Modes.
    *   Save this Bitmap.
    *   *Note:* This bypasses the screen resolution limit and allows high-res export.

2.  **Layer Export:**
    *   Iterate `uiState.layers`.
    *   For each layer, save its source Bitmap (or the processed version if destructive edits were made) to a file: `{ProjectName}/{LayerName}_{Order}.png`.

## Constraints
*   Must handle `BlendMode` correctly in the off-screen render.
*   Must respect the "Mesh Warp" if applied (this is the tricky part—if the warp is a shader effect, we need to reproduce it on the CPU or use an offscreen GL buffer). *Check: `WarpableImage` implementation.*
