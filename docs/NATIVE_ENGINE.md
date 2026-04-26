# Native Engine (MobileGS) Documentation

## Overview
The `MobileGS` engine is a high-performance C++ library designed for real-time 3D mapping and tracking recovery on mobile devices. 

**ARCHITECTURAL MANDATE:** `MobileGS` utilizes a **Persistent Voxel Memory** approach. By relying on exact perspective-scaled points and hardware Z-buffering, it creates solid surface representations that act as the app's spatial memory, enabling instant relocalization after tracking loss (the "Pocket-Ready" workflow).

## Key Components

### 1. Spatial Hash Table (`mSpatialHash`)
*   **Type**: Fixed-size `int32_t[HASH_SIZE]` (Zero-allocation).
*   **Purpose**: Maps 3D spatial hashes to indices in the `mSplatData` vector.
*   **Performance**: Provides O(1) constant-time lookup for discovery and voxel-averaging without the performance stutters of dynamic maps like `std::unordered_map`.
*   **Logic**:
    *   The world is divided into cubes of size `VOXEL_SIZE` (`20mm`).
    *   Stochastic Sampling: Only 2048 random pixels per depth frame are processed, reducing CPU overhead by ~90%.
    *   If a voxel is empty, a new surfel is instantiated instantly with high-fidelity color and normal orientation.

### 2. Splat (Surfel) Structure
Contains (44 bytes):
*   `x, y, z`: Position in World Space (metres).
*   `r, g, b, a`: Color and opacity (MANDATE: Opaque rendering).
*   `nx, ny, nz`: Surface Normal for alignment and occlusion.
*   `confidence`: Quality score (0.9 for HW Stereo, 0.5 for Mono).

### 3. Rendering Pipeline

A single OpenGL ES 3.0 render path runs inside `ArRenderer`:

| Step | Call | Content |
|---|---|---|
| 1 | `backgroundRenderer.draw()` | ARCore camera feed (full-screen, `EXTERNAL_OES`) |
| 2 | `slamManager.draw()` | Persistent Voxel Memory (`GL_POINTS`) drawn on top |

**Crucial Shader Logic:** The vertex shader calculates the exact pixel diameter needed to interlock surfels based on focal length (`uFocalY`) and depth. The fragment shader uses `if (length(coord) > 0.5) discard;` to create hard-edged circular discs. Alpha blending is `DISABLED` to maintain 60fps and depth stability.

### 4. Relocalization Thread (`relocThreadFunc`)
*   **Goal**: The "Snap-Back" behavior.
*   **Process**: A background thread continuously matches the current camera frame against "Spatial Fingerprints" (ORB descriptors) stored at high-confidence voxel locations.
*   **Correction**: Upon a successful match, the global anchor transform is updated to realign the AR mural with the physical world instantly.

## Memory Management
*   **Limits**: `MAX_SPLATS` is set to 250,000.
*   **Efficiency**: The fixed spatial hash avoids heap fragmentation during active mapping.
*   **Distance Culling**: Depth points further than 6m are ignored.

## JNI Interface (`GraffitiJNI.cpp` → `SlamManager.kt`)

| Kotlin method | Description |
|---|---|
| `feedArCoreDepth(...)` | Metric depth feed. Receives rotation code and a `confidence` weight (0.5 vs 0.9). |
| `updateAnchorTransform(mat)` | Teleological correction — snaps the global mural alignment. |
| `saveModel(path)` | Binary dump of the `mSplatData` vector and spatial hash. |
| `draw()` | OpenGL ES render — called by `ArRenderer` each frame. |

---
*Documentation updated on 2026-04-24 during Persistent Voxel Memory and Pocket-Ready recovery implementation.*
