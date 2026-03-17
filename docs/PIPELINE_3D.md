# 3D Pipeline Specification: SLAM & Dense Surfel Rendering

This document details the environment mapping and 3D visualization subsystems.

## 1. SLAM (Simultaneous Localization and Mapping)

**Module:** `:feature:ar`
**Key classes:** `ArViewModel`, `ArRenderer`, `SlamManager`

Unlike full-scale photogrammetry, the pipeline focuses on rapid, highly-dense voxel accumulation for immediate on-device visualization.

### A. Tracking & Keyframing
The system uses the ARCore `Session` for visual odometry. We trust `camera.pose` from ARCore; no manual VIO.

### B. Per-Frame Data Acquisition

Executed inside `ArRenderer.onDrawFrame` each tracking frame:

1. **Camera pose** — `camera.getViewMatrix/getProjectionMatrix` → `slamManager.updateCamera()`

2. **Color frame** — `frame.acquireCameraImage()` → RGBA → `slamManager.feedColorFrame()`
   - Used for relocalization / fingerprinting.
   - Requires `cvRotateCode` to perfectly match physical display orientation.

3. **Metric depth (Depth API devices)**
   - `frame.acquireDepthImage16Bits()` → DEPTH16 buffer → `slamManager.feedArCoreDepth()`
   - Native: decode millimetre depth, apply display rotation via `cvRotateCode`, and unproject using exact CPU intrinsics to prevent perspective skew.

### C. Voxel Map Accumulation
`processDepthFrame(depthMap, colorFrame)` in `MobileGS`:
Points quantized to 5mm³ voxels; existing voxels update position (averaging) and confidence (increment). Points beyond `CULL_DISTANCE` (5m) discarded.

### D. Map Serialization (GXRM format)
Binary format written by `saveModel(path)`:
1. Magic header: `"GXRM"`
2. Version: `3` (Supports anisotropic 48-byte structs)
3. Splat count + keyframe count
4. Payload: 48 bytes/splat — `x, y, z, r, g, b, a, confidence, nx, ny, nz, radius`
5. Alignment matrix

---

## 2. Dense Surfel Visualization

**MANDATE:** We explicitly reject alpha-blended soft splats. To prevent depth-sorting artifacts and achieve a watertight look, we utilize Dense Opaque Surfels.

SLAM surfels are rendered by `slamManager.draw()` inside `ArRenderer`'s `GLSurfaceView` via OpenGL ES 3.0 `GL_POINTS`:
1. **Sizing:** The vertex shader calculates the exact pixel diameter needed to cover the voxel based on focal length and distance, multiplying by $\sqrt{2}$ so adjacent circles overlap perfectly.
2. **Opacity:** `glDisable(GL_BLEND)` and `glDepthMask(GL_TRUE)` are strictly enforced. The fragment shader produces hard-edged, 100% opaque fragments.
3. **Z-Buffering:** The GPU's hardware Z-buffer natively handles foreground/background occlusion, eliminating the need for expensive per-frame CPU depth-sorting.

---

## 3. Projection & Occlusion Logic

When in **Mockup Mode**, editor layers project onto the 3D scene:

1. **Projection:** User's 2D canvas projected as a decal using projective texture mapping.
2. **Occlusion:** Real-world geometry depth is derived from the SLAM voxel map rendered via `slamManager.draw()`. Graffiti layers are rendered with depth test enabled so geometry (pillars, edges) correctly occludes the projected image.

---
*Documentation updated on 2026-03-17 during website redesign and Stencil Mode integration phase.*
