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

A single OpenGL ES 3.0 render path runs inside `ArRenderer`'s `GLSurfaceView`:

| Step | Call | Content |
|---|---|---|
| 1 | `backgroundRenderer.draw(frame)` | ARCore camera feed (full-screen, `EXTERNAL_OES`) |
| 2 | `slamManager.draw()` | SLAM voxel splats (`GL_POINTS`) drawn on top in the same GL context |

`ArRenderer.onDrawFrame` owns both steps. There is no separate surface for splat rendering.

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
*   **LRU Pruning**: `pruneMap()` is implemented — when `splatData.size() >= MAX_SPLATS`, it evicts the lowest-confidence 10% of splats using `std::partial_sort`. This prevents OOM crashes on long scans.
*   **Distance Culling**: Points further than `CULL_DISTANCE` (5m) are ignored.

## JNI Interface (`GraffitiJNI.cpp` → `SlamManager.kt`)

All data is passed as `ByteBuffer` (never raw pointers); native side calls `GetDirectBufferAddress`.

| Kotlin method | Description |
|---|---|
| `updateCamera(view, proj)` | Store current ARCore view + projection matrices |
| `setArCoreTrackingState(isTracking)` | Notify native engine of ARCore tracking state; adjusts behavior when lost |
| `updateAnchorTransform(transform)` | Apply teleological correction — updates global map alignment transform |
| `feedColorFrame(buf, w, h)` | RGBA color frame for relocalization / fingerprinting; called both when tracking and when not tracking |
| `feedArCoreDepth(buf, w, h)` | DEPTH16 decode → `processDepthFrame()` (metric depth, Depth API devices only) |
| `feedStereoData(l, r, w, h)` | Stereo disparity → depth → `processDepthFrame()` |
| `draw()` | OpenGL ES render (splat `GL_POINTS`) — called by `ArRenderer` each frame |

### ARCore DEPTH16 Encoding
```
uint16_t raw     = depthBuffer[r * width + c]
uint16_t depthMm = raw & 0x1FFF          // 13-bit depth in millimetres
uint8_t  conf    = (raw >> 13) & 0x7     // 3-bit confidence (0 = invalid, 7 = high)
```
Pixels with `conf == 0` or `depthMm == 0` are skipped. The depth map is resized to match the colour frame before being passed to `processDepthFrame()`.
