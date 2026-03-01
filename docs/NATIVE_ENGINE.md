# Native Engine (MobileGS) Documentation

## Overview
The `MobileGS` (Mobile Gaussian Splatting) engine is a custom C++ library designed for real-time 3D mapping and rendering on mobile devices. It uses a **Sparse Voxel Hashing** approach to store and render a point cloud as "splats" with confidence scores.

## Key Components

### 1. Voxel Grid (`mVoxelGrid`)
*   **Type**: `std::unordered_map<VoxelKey, int, VoxelKeyHash>`
*   **Purpose**: Maps 3D integer coordinates (voxels) to an index in the `mSplats` vector.
*   **Logic**:
    *   The world is divided into cubes of size `VOXEL_SIZE` (defined in `MobileGS.h`).
    *   When a new depth point is detected, its voxel key is calculated.
    *   If the voxel is empty, a new splat is created.
    *   If the voxel is occupied, the existing splat is updated (position averaging and confidence boost).

### 2. Splat Structure
Contains:
*   `x, y, z`: Position in world space (metres).
*   `r, g, b, a`: Colour and opacity.
*   `confidence`: Observation count used for opacity blending.

### 3. Rendering Pipeline

Two parallel render paths exist:

| Path | Surface | When active |
|---|---|---|
| OpenGL ES 3.0 (`ArRenderer` / `GLSurfaceView`) | `BackgroundRenderer` draws ARCore camera feed | Always in AR mode — camera background only |
| Vulkan (`GsViewer` / `SurfaceView`) | `VulkanBackend` renders voxel splats | Whenever `GsViewer` surface is alive |

`ArRenderer` no longer calls `slamManager.draw()`. `GsViewer` sits above `ArRenderer` with `setZOrderMediaOverlay(true)` and owns all SLAM voxel rendering.

### 4. Depth Processing (`processDepthFrame`)
*   **Input**: `cv::Mat` depth (metres, CV_32F) + colour frame (`gLastColorFrame`).
*   **Unprojection**: Depth pixels → View Space → World Space using the view matrix stored by `nativeUpdateCamera`.
*   **Voxel Hashing**: World points quantized into voxels; `mVoxelGrid` updated.

### 5. Optical Flow Depth Estimation (`computeOpticalFlowDepth`)
Used as a fallback when ARCore Depth API frames are unavailable.

*   **Algorithm**: Lucas-Kanade sparse optical flow (`cv::calcOpticalFlowPyrLK`, window 21×21, 3 pyramid levels).
*   **Formula**: `depth ≈ kScale / flow_px`, where `kScale = gFocalLengthPx × gTranslationM`.
*   **Dynamic kScale**: Updated every tracking frame via `nativeSetCameraMotion(focalLengthPx, translationM)` from ARCore `camera.imageIntrinsics.focalLength[0]` and the magnitude of `camera.pose.translation` delta. Falls back to `1200px × 0.02m = 24` before the first ARCore frame.
*   **Limits**: `kMinFlow = 0.5px` (sub-pixel noise rejection), `kMinDep = 0.3m`, `kMaxDep = 8.0m`.

## Memory Management
*   **Limits**: `MAX_SPLATS` is set to 500,000 to prevent OOM.
*   **Culling**: Points further than `CULL_DISTANCE` (5m) are ignored.

## JNI Interface (`GraffitiJNI.cpp` → `SlamManager.kt`)

All data is passed as `ByteBuffer` (never raw pointers); native side calls `GetDirectBufferAddress`.

| Kotlin method | Description |
|---|---|
| `updateCamera(view, proj)` | Store current ARCore view + projection matrices |
| `setCameraMotion(fxPx, tM)` | Update optical-flow `kScale` from ARCore intrinsics and inter-frame translation magnitude |
| `feedMonocularData(buf, w, h)` | Y-plane → optical flow → sparse depth → `processDepthFrame()` |
| `feedArCoreDepth(buf, w, h)` | DEPTH16 decode → `processDepthFrame()` (overrides optical-flow estimates) |
| `feedStereoData(l, r, w, h)` | Stereo disparity → depth → `processDepthFrame()` |
| `feedLocationData(lat, lon, alt)` | GPS geo-anchor |
| `draw()` | OpenGL ES render (splat `GL_POINTS`) — called by GL path only |
| `saveModel(path)` / `loadModel(path)` | Serialize / deserialize to GXRM binary |
| `saveKeyframe(ts, path)` | Write GXRM with current pose metadata |
| `initVulkanEngine(surface, assets, w, h)` | Initialize Vulkan backend |
| `resizeVulkanSurface(w, h)` | Handle surface resize |
| `destroyVulkanEngine()` | Tear down Vulkan backend |

### ARCore DEPTH16 Encoding
```
uint16_t raw     = depthBuffer[r * width + c]
uint16_t depthMm = raw & 0x1FFF          // 13-bit depth in millimetres
uint8_t  conf    = (raw >> 13) & 0x7     // 3-bit confidence (0 = invalid, 7 = high)
```
Pixels with `conf == 0` or `depthMm == 0` are skipped. The depth map is resized to match the colour frame before being passed to `processDepthFrame()`.
