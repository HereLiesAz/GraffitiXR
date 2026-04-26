# Performance Guide

GraffitiXR is optimized for a rock-solid 60fps tracking experience on mobile hardware by prioritizing functional mapping over visual fluff.

## The Rendering Loop (16ms Budget)

### 1. Opaque Pipeline
We render the world map using opaque `GL_POINTS` with hardware Z-buffering.
* **Win:** Eliminates the need for expensive back-to-front sorting.
* **Win:** O(1) rendering time relative to pixel coverage, not layer count.
* **MANDATE:** `glDisable(GL_BLEND)` must be called before `slamManager.draw()`.

### 2. Stochastic Integration
Instead of processing every pixel of the $160 \times 120$ depth map, we sample a random subset of 2048 pixels.
* **Optimization:** Reduces depth processing load by ~90%.
* **Stability:** Still provides enough data to build a dense voxel map within seconds.

### 3. Zero-Allocation Spatial Hash
We use a fixed-size `int32_t` array for spatial voxel indexing.
* **Optimization:** Eliminates heap allocations and garbage collection pauses during active mapping.
* **Speed:** Lookup time is O(1) constant, regardless of map size.

### 4. Mandatory Hardware Stereo
The app forces dual-lens depth mapping on supported devices.
* **Benefit:** Significantly more stable tracking and relocalization fingerprints.
* **Efficiency:** High-quality hardware depth allows for faster "locking" of voxels, reducing optimization churn.

## Battery & Thermal Management
ARCore and 3D reconstruction are power-intensive.
* **JNI Throttle:** We feed frames to the native engine at 10Hz to save battery while the user is stationary.
* **Background Offloading:** Relocalization (PnP matching) and persistent saving are handled on dedicated low-priority threads.

---
*Documentation updated on 2026-04-24 during Persistent Voxel Memory and Pocket-Ready recovery implementation.*
