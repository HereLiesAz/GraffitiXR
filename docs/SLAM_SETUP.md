# SLAM & MobileGS Configuration

This document outlines the configuration and tuning of the custom Gaussian Splatting engine.

## The Engine (`MobileGS.cpp`)

The engine operates on a **Sparse Voxel Hashing** system. It stores a hash map of 20mm³ voxels, not a dense point cloud.

### Key Parameters

Defined in `MobileGS.h`; tune for performance vs. accuracy.

| Parameter | Value | Description |
| :--- | :--- | :--- |
| `VOXEL_SIZE` | `0.02f` (20mm) | Physical size of a single splat. Tuned for wall-scale scanning. |
| `CONFIDENCE_THRESHOLD` | `0.6f` | Voxel must reach this opacity before being considered "solid". |
| `CONFIDENCE_INCREMENT` | `0.05f` | Opacity gained per frame of observation. |
| `MAX_SPLATS` | `500,000` | Hard limit on the instance buffer to maintain 60 FPS on mid-range devices. |
| `CULL_DISTANCE` | `5.0f` (metres) | Points further than 5m are ignored to keep the map local to the wall. |

## Sensor Input Pipeline

Each tracking frame, `ArRenderer.onDrawFrame` feeds the engine in this order:

### Step 1 — Motion calibration (`setCameraMotion`)
```kotlin
slamManager.setCameraMotion(
    focalLengthPx = camera.imageIntrinsics.focalLength[0],
    translationM  = magnitude(camera.pose.translation - prevPose.translation)
)
```
Sets `kScale = focalLengthPx × translationM` in native, used by optical flow depth.

### Step 2 — Monocular feed (`feedMonocularData`)
Y-plane (luma) from `frame.acquireCameraImage()` → `nativeFeedMonocularData`:
1. Convert to grayscale `cv::Mat`.
2. Run `cv::goodFeaturesToTrack` to detect features.
3. Run `cv::calcOpticalFlowPyrLK` against the previous frame.
4. Compute sparse depth: `depth = kScale / flow_px` (clamped 0.3–8.0m).
5. Call `gSlamEngine->processDepthFrame(depthMap, colorFrame)`.
6. Update `gPrevGray` / `gPrevFeatures` for next frame.

### Step 3 — Metric depth (`feedArCoreDepth`, Depth API devices only)
DEPTH16 buffer from `frame.acquireDepthImage16Bits()` → `nativeFeedArCoreDepth`:
1. Decode: `depthMm = raw & 0x1FFF`, `conf = (raw >> 13) & 0x7`.
2. Skip pixels where `conf == 0`.
3. Convert to metres `cv::Mat`, resize to match colour frame.
4. Call `gSlamEngine->processDepthFrame(depthMap, colorFrame)`.

When ARCore depth is available it **replaces** the optical-flow estimate for that frame, providing metric-accurate voxel placement.

### Step 4 — Camera pose (`updateCamera`)
```kotlin
slamManager.updateCamera(viewMatrix, projMatrix)
```
Stores the view/projection matrices used by `processDepthFrame` for unprojection.

## Tuning Guide

### "The map is drifting / double vision"
*   **Cause:** Learning rate too high, or `VOXEL_SIZE` too large.
*   **Fix:** Decrease `VOXEL_SIZE` to `0.010f`. Ensure the user moves slowly.

### "The map takes too long to appear"
*   **Cause:** `CONFIDENCE_THRESHOLD` too high.
*   **Fix:** Lower it to `0.3f`, but ghost points (noise) will increase.

### "Depth looks wildly inaccurate (optical flow path)"
*   **Cause:** `kScale` is miscalibrated — the device isn't reporting intrinsics or is stationary.
*   **Check:** Logcat for `AR_DEBUG` tag. `translationM` should be 0.01–0.05m per frame at a slow walk.
*   **Fix:** Ensure `setCameraMotion` is being called each tracking frame before `feedMonocularData`.

### "App crashes after scanning for 2 minutes"
*   **Cause:** `MAX_SPLATS` exceeded.
*   **Fix:** Implement an LRU culling strategy in `MobileGS::pruneMap()` to delete old voxels.
