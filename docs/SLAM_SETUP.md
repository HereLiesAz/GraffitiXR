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

**CRITICAL MANDATE: World-Space Pipeline (Restored Stable Version)**
1.  **Ingestion**: Depth maps are processed in their raw orientation.
2.  **Unprojection**: Uses pixel-space intrinsics recovered from the OpenGL projection matrix or physical sensor parameters.
    *   `xc = (c_px - cx_px) * depth / fx_px`
    *   `yc = -(r_px - cy_px) * depth / fy_px` (MANDATORY Y-FLIP)
3.  **Transformation**: Stored points use **World Space** positions. 
4.  **Storage**: Voxel hashing operates on world coordinates with a 20mm resolution (`VOXEL_SIZE = 0.02f`).
5.  **Rendering**: Uses **High-Performance Opaque Surfels**. We do NOT use fuzzy, transparent Gaussian splats or sorting. 
    *   `MVP = Projection * View`
    *   `glDisable(GL_BLEND)` and `glDepthMask(GL_TRUE)` are mandatory.
    *   Physical Point Size: `sz = (diameter_m * focal_y_px) / depth_m`.

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
