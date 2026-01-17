# Bolt's Journal

## 2024-05-21 - Render Loop Allocations
**Learning:** The `ArRenderer` class was performing multiple `FloatArray` allocations and `listOf` creations inside `onDrawFrame` and its helper methods (`calculateAndReportBounds`, `drawArtwork`). This violates the core principle of zero-allocation render loops in Android/OpenGL.
**Action:** Use pre-allocated reusable `FloatArray` members and flat arrays for loops to avoid GC pressure.

## 2024-05-24 - Redundant Matrix Math & Hidden Allocations
**Learning:** Even if `ArRenderer` avoids allocations, helper classes like `SimpleQuadRenderer` might not. Also, calculating the same Model matrix twice per frame (once for drawing, once for bounds) is wasteful.
**Action:** Audit all renderer classes for allocations, and consolidate matrix calculations to run once per frame where possible.

## 2024-05-25 - Native Memory Churn in Analysis Loop
**Learning:** `ArRenderer.analyzeFrameAsync` was creating short-lived `Mat` objects on the GL thread just to extract byte data, causing both JNI overhead and unnecessary GC pressure.
**Action:** Use direct `ByteBuffer` access with `isAnalyzing` guard to copy data to a reusable `ByteArray` on the GL thread, deferring all `Mat` creation to the background thread.
