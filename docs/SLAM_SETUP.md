# SLAM & MobileGS Configuration

This document outlines the configuration and tuning of the custom MobileGS engine.

## The Engine (`MobileGS.cpp`)

The engine operates on a **Sparse Voxel Hashing** system. 

**MANDATE:** We use a dense voxel grid and opaque surfel rendering. We do NOT use fuzzy, transparent Gaussian splats. 

### Key Parameters

| Parameter | Value | Description |
| :--- | :--- | :--- |
| `VOXEL_SIZE` | `0.005f` (5mm) | Physical size of a single surfel. |
| `MIN_RENDER_CONFIDENCE` | `0.6f` | Surfel must reach this confidence before being rendered as "solid". |
| `MAX_SPLATS` | `500,000` | Hard limit on the instance buffer. |

### Coordinate System & Storage

**CRITICAL MANDATE:** All surfels and mesh vertices MUST be stored in **Anchor-Local Space**. 
*   **Conversion**: `Local = inverse(World_from_Anchor) * inverse(Camera_from_World) * Camera_Space_Point`
*   **Reasoning**: Storing in local space ensures that the map remains locked to the physical wall even if ARCore's world origin drifts. It also ensures that Teleological Corrections (anchor updates) apply instantly without needing to re-process the entire map.
*   **Rendering**: Use `MVP = Projection * View * Anchor` to project local points back to screen space.

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
Fix: Ensure `cvRotateCode` is passed to BOTH color and depth feeding in `ArRenderer.kt`. The JNI layer must rotate the depth map AND intrinsics to match the screen-aligned color buffer.

"The map is upside down or jumping"
Cause: Incorrect Y-flip in unprojection or storing in World Space without applying Anchor transform correctly.
Fix: Ensure `unproject` lambda in `MobileGS.cpp` uses `-(r - cy_px)` for the Y coordinate. Ensure points are stored in **Anchor-Local Space**.



---
*Documentation updated on 2026-03-17 during website redesign and Stencil Mode integration phase.*
