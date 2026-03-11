# SLAM & MobileGS Configuration

This document outlines the configuration and tuning of the custom MobileGS engine.

## The Engine (`MobileGS.cpp`)

The engine operates on a **Sparse Voxel Hashing** system. 

**MANDATE:** We use a dense voxel grid and opaque surfel rendering. We do NOT use fuzzy, transparent Gaussian splats. 

### Key Parameters

Defined in `MobileGS.cpp`; tuned for dense watertight surfaces.

| Parameter | Value | Description |
| :--- | :--- | :--- |
| `VOXEL_SIZE` | `0.005f` (5mm) | Physical size of a single surfel. Reduced to 5mm to provide ultra-high mapping resolution and eliminate grid-like artifacts. |
| `CONFIDENCE_THRESHOLD` | `0.6f` | Voxel must reach this opacity before being considered "solid". |
| `MAX_SPLATS` | `500,000` | Hard limit on the instance buffer to maintain 60 FPS on mid-range devices. |
| `CULL_DISTANCE` | `5.0f` (metres) | Points further than 5m are ignored to keep the map local to the wall. |

## Sensor Input Pipeline

Each tracking frame, `ArRenderer.onDrawFrame` feeds the engine in this order:

### Step 1 — Color frame (`feedColorFrame`)
RGBA buffer from `frame.acquireCameraImage()` → `nativeFeedColorFrame`:
Feeds the color frame to the native engine for relocalization and fingerprinting. Receives `cvRotateCode` to ensure the image matrix strictly aligns with the display orientation.

### Step 2 — Metric depth (`feedArCoreDepth`, Depth API devices only)
DEPTH16 buffer from `frame.acquireDepthImage16Bits()` → `nativeFeedArCoreDepth`:
1. Decode: `depthMm = raw & 0x1FFF`, `conf = (raw >> 13) & 0x7`.
2. Skip pixels where `depthMm == 0`.
3. **Orientation Correction:** Rotate depth map via `cvRotateCode` to match the display orientation.
4. **Unprojection:** Use the physical CPU `CameraIntrinsics` (passed from Java) to calculate precise rays. Do NOT use the normalized NDC projection matrix, as it causes severe diagonal skewing on rotation.
5. Call `gSlamEngine->processDepthFrame(depthMap, colorFrame)`.

### Step 3 — Camera pose (`updateCamera`)
~~~kotlin
slamManager.updateCamera(viewMatrix, projMatrix)
~~~

Stores the view/projection matrices used by processDepthFrame and the GL renderer.
Tuning Guide
"The map looks like a fuzzy Monet painting"
Cause: Reverted to legacy alpha-blended rendering.
Fix: Ensure glDisable(GL_BLEND) and glDepthMask(GL_TRUE) remain in MobileGS::draw(). The fragment shader must use if (r2 > 1.0) discard; to draw hard-edged opaque circles.
"The geometry looks stretched diagonally (Skewed)"
Cause: Mismatch between sensor orientation and display orientation during unprojection.
Fix: Ensure cvRotateCode is calculated correctly in ArRenderer.kt using DisplayRotationHelper, and that feedArCoreDepth uses the true physical CPU intrinsics (fx, fy, cx, cy) to unproject rays before converting them to world space.
"App crashes after scanning for 2 minutes"
LRU pruning is implemented via pruneMap() — automatically evicts the 10% least-confident splats using std::partial_sort when MAX_SPLATS is reached. This prevents OOM crashes on long scans.

