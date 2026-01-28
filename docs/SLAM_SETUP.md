# SLAM & MobileGS Configuration

This document outlines the configuration and tuning of the custom Gaussian Splatting engine.

## The Engine (`MobileGS.cpp`)

The engine operates on a **Sparse Voxel Hashing** system. It does not store a dense point cloud; it stores a hash map of 5mm³ voxels.

### Key Parameters

These constants are defined in `MobileGS.h` and can be tweaked for performance vs. accuracy.

| Parameter | Value | Description |
| :--- | :--- | :--- |
| `VOXEL_SIZE` | `0.02f` (20mm) | The physical size of a single splat. Tuned for wall-scale scanning. |
| `CONFIDENCE_THRESHOLD` | `0.6f` | A voxel must reach this opacity before being considered "solid". |
| `CONFIDENCE_INCREMENT` | `0.05f` | Opacity gained per frame of observation. |
| `MAX_SPLATS` | `500,000` | Hard limit on the instance buffer size to maintain 60 FPS on mid-range devices. |
| `CULL_DISTANCE` | `5.0f` (Meters) | Points further than 5m are ignored to keep the map local to the wall. |

## Sensor Input

The engine requires two synchronized streams from ARCore:
1.  **Depth Map (16-bit):** Used for unprojection. Values > 6500mm are discarded.
2.  **Camera Pose (Matrix4x4):** The precise location of the device in world space.

## Tuning Guide

### "The Map is drifting / Double Vision"
* **Cause:** Learning rate is too high, or `VOXEL_SIZE` is too large.
* **Fix:** Decrease `VOXEL_SIZE` to `0.003f`. Ensure the user is moving slowly.

### "The Map takes too long to appear"
* **Cause:** `CONFIDENCE_THRESHOLD` is too high.
* **Fix:** Lower it to `3`, but be warned that "ghost points" (noise) will increase.

### "App crashes after scanning for 2 minutes"
* **Cause:** `MAX_SPLATS` exceeded.
* **Fix:** Implement a "Least Recently Used" (LRU) culling strategy in `MobileGS::pruneMap()` to delete old voxels.