# Bolt's Journal

## 2024-05-21 - Render Loop Allocations
**Learning:** The `ArRenderer` class was performing multiple `FloatArray` allocations and `listOf` creations inside `onDrawFrame` and its helper methods (`calculateAndReportBounds`, `drawArtwork`). This violates the core principle of zero-allocation render loops in Android/OpenGL.
**Action:** Use pre-allocated reusable `FloatArray` members and flat arrays for loops to avoid GC pressure.
