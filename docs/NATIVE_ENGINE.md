# Native Engine (MobileGS) Documentation

## Overview
The `MobileGS` (Mobile Gaussian Splatting) engine is a custom C++ library designed for real-time 3D mapping and rendering on mobile devices. It uses a **Sparse Voxel Hashing** approach to store and render a point cloud as "splats" (disks) with confidence scores.

## Key Components

### 1. Voxel Grid (`mVoxelGrid`)
*   **Type**: `std::unordered_map<VoxelKey, int, VoxelKeyHash>`
*   **Purpose**: Maps 3D integer coordinates (voxels) to an index in the `mGaussians` vector.
*   **Logic**:
    *   The world is divided into cubes of size `VOXEL_SIZE` (defined in `MobileGS.h`).
    *   When a new depth point is detected, we calculate its voxel key.
    *   If the voxel is empty, a new splat is created.
    *   If the voxel is occupied, the existing splat is updated (position averaging and confidence boost).

### 2. Splat Metadata (`SplatMetadata`)
Contains:
*   `renderData`: Struct passed to the GPU (Position, Scale, Color, Opacity).
*   `creationTime`: Timestamp for pruning logic.

### 3. Rendering Pipeline
The engine uses **Instanced Rendering** to draw thousands of splats efficiently.
*   **Geometry**: A single quad (4 vertices) is stored in `mQuadVBO`.
*   **Instance Data**: Position, Color, and Scale for every splat are stored in `mVBO`.
*   **Shader**: `VS_SRC` and `FS_SRC` (embedded in `MobileGS.cpp`) handle the billboard calculation (making splats face the camera).

### 4. Sorting Thread
Gaussian Splatting requires back-to-front sorting for correct alpha blending.
*   **Thread**: `sortThreadLoop` runs continuously in the background.
*   **Algorithm**: Calculates the distance of every splat to the camera plane, sorts them, and updates the index buffer.
*   **Synchronization**: Uses double-buffering logic to avoid stalling the render thread.

## Memory Management
*   **Pruning**: The `pruneMap()` function removes points that:
    *   Have low confidence (opacity) after a certain time (`MIN_AGE_MS`).
    *   Are the "least confident" when the `MAX_POINTS` limit is reached.

## JNI Interface
The engine is exposed to Kotlin via `GraffitiJNI.cpp`, which maps native pointers to Java `long` handles.

**Key Methods:**
*   `updateCamera(viewMtx, projMtx)`: Updates the camera pose using raw OpenGL matrices.
*   `feedDepthData(buffer, width, height)`: Ingests the 16-bit depth buffer for voxel unprojection.
