~~~ FILE: ./docs/NATIVE_ENGINE.md ~~~
# Native Engine (MobileGS) Documentation

## Overview
The `MobileGS` engine is a custom C++ library designed for real-time 3D mapping and rendering on mobile devices. 

**ARCHITECTURAL MANDATE:** `MobileGS` utilizes a **Dense Opaque Surfel** rendering approach, abandoning soft alpha-blended splatting. By relying on exact pixel-radius sizing and hardware Z-buffering (opaque depth writes), it creates solid, watertight surface meshes rather than fuzzy point clouds.

## Key Components

### 1. Voxel Grid (`mVoxelGrid`)
*   **Type**: `std::unordered_map<VoxelKey, int, VoxelKeyHash>`
*   **Purpose**: Maps 3D integer coordinates (voxels) to an index in the `mSplats` vector.
*   **Logic**:
    *   The world is divided into ultra-dense cubes of size `VOXEL_SIZE` (`5mm`, defined in `MobileGS.cpp`).
    *   When a new depth point is detected, its voxel key is calculated.
    *   If the voxel is empty, a new surfel is created.
    *   If the voxel is occupied, the existing surfel is updated (position averaging and confidence boost).

### 2. Splat (Surfel) Structure
Contains (48 bytes):
*   `x, y, z`: Position in world space (metres).
*   `r, g, b, a`: Colour and opacity.
*   `confidence`: Observation count.
*   `nx, ny, nz`: Surface Normal.
*   `radius`: Calculated physical scale.

### 3. Rendering Pipeline

A single OpenGL ES 3.0 render path runs inside `ArRenderer`'s `GLSurfaceView`:

| Step | Call | Content |
|---|---|---|
| 1 | `backgroundRenderer.draw(frame)` | ARCore camera feed (full-screen, `EXTERNAL_OES`) |
| 2 | `slamManager.draw()` | Dense SLAM Surfels (`GL_POINTS`) drawn on top in the same GL context |

**Crucial Shader Logic:** The vertex shader calculates the exact pixel diameter needed to cover the surfel based on the physical focal length (`uFocalY`) and depth (`clip.w`). The fragment shader aggressively discards fragments outside the radius and writes to the depth buffer completely opaquely. Alpha blending is `DISABLED`.

### 4. Depth Processing (`processDepthFrame`)
*   **Input**: `cv::Mat` depth (metres, CV_32F) + colour frame + CPU Intrinsics + Rotation Delta.
*   **Orientation Fix**: Depth maps are strictly rotated (`cv::rotate`) via the `cvRotateCode` parameter to match the active display orientation, preventing diagonal perspective skew.
*   **Unprojection**: Uses the exact physical CPU intrinsics (`fx, fy, cx, cy`) to unproject pixels into Camera Space, then transforms to World Space using the `mViewMatrix`.

### 5. Optical Flow Depth Estimation (`computeOpticalFlowDepth`)
Used as a fallback when ARCore Depth API frames are unavailable.

## Memory Management
*   **Limits**: `MAX_SPLATS` is set to 500,000 to prevent OOM.
*   **LRU Pruning**: `pruneMap()` is implemented — when `splatData.size() >= MAX_SPLATS`, it evicts the lowest-confidence 10% of surfels using `std::partial_sort`. This prevents OOM crashes on long scans.
*   **Distance Culling**: Points further than `CULL_DISTANCE` (5m) are ignored.

## JNI Interface (`GraffitiJNI.cpp` → `SlamManager.kt`)

All data is passed as `ByteBuffer` (never raw pointers); native side calls `GetDirectBufferAddress`.

| Kotlin method | Description |
|---|---|
| `updateCamera(view, proj)` | Store current ARCore view + projection matrices |
| `setArCoreTrackingState(isTracking)` | Notify native engine of ARCore tracking state |
| `updateAnchorTransform(transform)` | Apply teleological correction — updates global map alignment |
| `feedColorFrame(...)` | RGBA color frame for relocalization / fingerprinting |
| `feedArCoreDepth(...)` | Metric depth feed. Receives rotation code and physical CPU intrinsics. |
| `feedStereoData(...)` | Stereo disparity → depth → `processDepthFrame()` |
| `draw()` | OpenGL ES render (surfel `GL_POINTS`) — called by `ArRenderer` each frame |

### ARCore DEPTH16 Encoding
uint16_t raw = depthBuffer[r * width + c]
uint16_t depthMm = raw & 0x1FFF // 13-bit depth in millimetres
uint8_t conf = (raw >> 13) & 0x7 // 3-bit confidence (0 = invalid, 7 = high)

