# 3D Pipeline Specification: SLAM & Gaussian Splatting

This document details the environment mapping and 3D visualization subsystems.

## 1. SLAM (Simultaneous Localization and Mapping)

**Module:** `:feature:ar`
**Key classes:** `ArViewModel`, `ArRenderer`, `SlamManager`

Unlike full-scale photogrammetry, the pipeline focuses on rapid, sparse voxel accumulation for immediate on-device visualization.

### A. Tracking & Keyframing
The system uses the ARCore `Session` for visual odometry. We trust `camera.pose` from ARCore; no manual VIO.

**Keyframe Heuristic:**
A new keyframe is saved when the delta from the last keyframe exceeds:
* **Translation:** ΔT > 0.1 metres (10cm)
* **Rotation:** ΔR > 10.0 degrees
* **Quality:** `camera.trackingState == TRACKING`

### B. Per-Frame Data Acquisition

Executed inside `ArRenderer.onDrawFrame` each tracking frame:

1. **Camera pose** — `camera.getViewMatrix/getProjectionMatrix` → `slamManager.updateCamera()`

2. **Color frame** — `frame.acquireCameraImage()` → RGBA → `slamManager.feedColorFrame()`
   - Used for relocalization / fingerprinting; called when tracking and when not tracking.
   - The native engine handles internal optical flow as an implementation detail.

3. **Metric depth (Depth API devices)**
   - `frame.acquireDepthImage16Bits()` → DEPTH16 buffer → `slamManager.feedArCoreDepth()`
   - Native: decode millimetre depth + confidence → `processDepthFrame()` (metric-accurate voxel placement)

### C. Voxel Map Accumulation
`processDepthFrame(depthMap, colorFrame)` in `MobileGS`:
$$P_{world} = M_{view}^{-1} \times P_{view}$$
Points quantized to 20mm³ voxels; existing voxels update position (averaging) and confidence (increment). Points beyond `CULL_DISTANCE` (5m) discarded.

### D. Map Serialization (GXRM format)
Binary format written by `saveModel(path)` / `saveKeyframe(timestamp, path)`:
1. Magic header: `"GXRM"`
2. Splat count + keyframe count
3. Splat payload: 32 bytes/splat — `x, y, z, r, g, b, a, confidence`
4. Alignment matrix

---

## 2. Gaussian Splatting Visualization

SLAM splats are rendered by `slamManager.draw()` inside `ArRenderer`'s `GLSurfaceView` via OpenGL ES 3.0 `GL_POINTS`. The `BackgroundRenderer` renders the camera feed first, then splats are drawn on top in the same GL context.

---

## 3. Projection & Occlusion Logic

When in **Mockup Mode**, editor layers project onto the 3D scene:

1. **Projection:** User's 2D canvas projected as a decal using projective texture mapping.
2. **Occlusion:** Real-world geometry depth is derived from the SLAM voxel map rendered via `slamManager.draw()`. Graffiti layers are rendered with depth test enabled so geometry (pillars, edges) correctly occludes the projected image.
