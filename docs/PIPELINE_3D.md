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

2. **Motion calibration** — `camera.imageIntrinsics.focalLength[0]` + translation magnitude from consecutive `camera.pose.translation` values → `slamManager.setCameraMotion(focalLengthPx, translationM)`. Sets native `kScale = focalLengthPx × translationM` for optical-flow depth.

3. **Monocular depth (optical flow fallback)**
   - `frame.acquireCameraImage()` → Y-plane → `slamManager.feedMonocularData()`
   - Native: Lucas-Kanade sparse flow → `depth ≈ kScale / flow_px` → `processDepthFrame()`

4. **Metric depth (Depth API devices)**
   - `frame.acquireDepthImage16Bits()` → DEPTH16 buffer → `slamManager.feedArCoreDepth()`
   - Native: decode millimetre depth + confidence → `processDepthFrame()` (overrides optical-flow)

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

**Module:** `:feature:editor`
**Key class:** `GsViewer`

`GsViewer` is a `SurfaceView` with `setZOrderMediaOverlay(true)` that initializes the Vulkan backend and sits above `ArRenderer`'s `GLSurfaceView` in the view hierarchy.

### Vulkan Rendering Stack
*   **Descriptor layout:** `binding=0` UBO (matrices), `binding=1` overlay sampler
*   **Push constants:** `{visualizationMode: int, overlayEnabled: int, vpW: float, vpH: float}` — 16 bytes
*   **Visualization modes:** `0` = RGB (default), `1` = Heatmap (confidence-based thermal gradient in fragment shader)

### Fragment Shader Splat Falloff
$$\alpha = e^{-\frac{1}{2} (x^T \Sigma^{-1} x)}$$
Fragments below the alpha threshold are discarded.

---

## 3. Projection & Occlusion Logic

When in **Mockup Mode**, editor layers project onto the 3D scene:

1. **Projection:** User's 2D canvas projected as a decal using projective texture mapping.
2. **Occlusion:**
   * `GsViewer` renders splat depth into a Depth Texture.
   * Graffiti layers are rendered with depth test enabled.
   * **Result:** Real-world geometry (pillars, edges) correctly occludes the projected image.
