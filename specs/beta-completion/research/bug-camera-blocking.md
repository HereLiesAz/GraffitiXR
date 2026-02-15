# Research: Camera Blocking Bug

## Symptom
Camera view is blocked in AR and Overlay modes.

## Potential Causes
1.  **Z-Order Issue:** `GLSurfaceView` is drawing *over* the camera preview but drawing opaque black instead of transparent.
    *   *Check:* `setZOrderMediaOverlay(true)`? Yes, it's set.
    *   *Check:* `holder.setFormat(PixelFormat.TRANSLUCENT)`? Yes, it's set.
    *   *Check:* `glClearColor(0,0,0,0)`? The C++ code had a fix: `glClearColor(0.0f, 0.0f, 0.0f, 0.0f);`.
    *   *Check:* Is the `AndroidView` for CameraPreview actually rendering?

2.  **Lifecycle Race Condition:** Camera might be unbinding/rebinding incorrectly.

3.  **Layout Issue:** In `ArView.kt`, the `AndroidView` for camera is first, then `AndroidView` for GLSurfaceView. Compose layout order = Z order (later is on top).
    *   If GLSurfaceView is on top and opaque, it blocks.
    *   If GLSurfaceView is on top and transparent, it should work (provided `setZOrderMediaOverlay` handles the SurfaceView punching).

## Action Plan
1.  Verify `MobileGS.cpp` definitely clears to `0,0,0,0`.
2.  Verify `ArRenderer.kt` doesn't draw a full-screen opaque quad (e.g. `BackgroundRenderer` might be drawing black if the texture isn't ready).
3.  Check `BackgroundRenderer` usage. In standard ARCore, `BackgroundRenderer` draws the camera feed *onto* the GLSurfaceView.
    *   *Wait:* `ArView.kt` creates a *separate* `PreviewView` for the camera (Layer 1) and a `GLSurfaceView` for AR (Layer 2).
    *   *Conflict:* Standard ARCore samples use `BackgroundRenderer` to draw camera feed *inside* the GL context.
    *   *Our Setup:* We are trying to mix `CameraX` (PreviewView) + `ARCore/OpenGL` (Transparent Overlay).
    *   *Hypothesis:* The `ArRenderer` might be drawing the "Background" (black/empty) over the transparent surface, effectively hiding the `PreviewView` behind it. Or `BackgroundRenderer` is being used when it shouldn't be (if we rely on `PreviewView`).
