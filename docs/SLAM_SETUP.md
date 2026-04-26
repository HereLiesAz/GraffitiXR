# SLAM & MobileGS Configuration

This document outlines the tuning of the Persistent Voxel Memory engine.

## The Engine (`MobileGS.cpp`)

The engine operates on a **Stochastic Voxel Hashing** system designed for instant relocalization.

**MANDATE:** We use opaque surfel rendering with hardware Z-buffering. Soft, alpha-blended Gaussian splatting is explicitly rejected for performance reasons.

### Key Parameters

| Parameter | Value | Description |
| :--- | :--- | :--- |
| `VOXEL_SIZE` | `0.02f` (20mm) | Physical resolution of the spatial hash. |
| `STOCHASTIC_SAMPLES`| `2048` | Random pixels processed per frame to save CPU. |
| `MAX_SPLATS` | `250,000` | Hard limit for mobile tracking memory. |

### Coordinate System & Storage

**CRITICAL MANDATE: Functional Voxel Memory**
1.  **Ingestion**: Depth maps are stochasticly sampled (random subset) to minimize overhead.
2.  **Unprojection**: Uses physical sensor intrinsics. 
    *   `xc = (c_px - cx_px) * depth / fx_px`
    *   `yc = -(r_px - cy_px) * depth / fy_px` (MANDATORY Y-FLIP)
3.  **Transformation**: All points are stored in **World Space**.
4.  **Hardware Reward**: Dual-lens HW stereo is MANDATORY. 
    *   **HW Stereo**: New voxels start with **0.9** confidence (Immutable).
    *   **Mono Depth**: New voxels start with **0.5** confidence.
5.  **Rendering**: Uses high-performance opaque `GL_POINTS`.
    *   `MVP = Projection * View`
    *   `glDisable(GL_BLEND)` and `glDepthMask(GL_TRUE)` are mandatory.

## Sensor Input Pipeline

### Step 1 — Color frame (`feedColorFrame`)
RGBA buffer for relocalization. Offloaded to the `relocThreadFunc` for background PnP matching.

### Step 2 — Metric depth (`feedArCoreDepth`)
1. **Selection**: HW Stereo is forced if available.
2. **Sampling**: 2048 random points are projected to world space.
3. **Hashing**: Spatial hash table ensures O(1) lookup speed for discovery.
4. **Locking**: Once a voxel reaches 1.0 confidence, its position is "locked" to prevent jitter.

## Tuning Guide

"The tracking doesn't snap back after pocketing"
Cause: No wall fingerprints captured.
Fix: Scan the wall slowly from multiple angles. Check "Lens Mode" in diagnostics—`MANDATORY HW` provides significantly better fingerprints than `SINGLE`.

"The map is slow or the phone is hot"
Cause: `MAX_SPLATS` is too high for this device.
Fix: The engine automatically caps at 250k. Verify that `STOCHASTIC_SAMPLES` has not been increased beyond 2048.

"The geometry looks skewed"
Cause: Incorrect rotation code in JNI.
Fix: Ensure `ArRenderer` is passing the correct `cvRotateCode` to both color and depth feeds.

---
*Documentation updated on 2026-04-24 during Persistent Voxel Memory and Pocket-Ready recovery implementation.*
