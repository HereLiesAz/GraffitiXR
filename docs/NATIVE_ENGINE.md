# Native Engine (MobileGS) Documentation

## Overview
The `MobileGS` (Mobile Gaussian Splatting) engine is a custom C++ library designed for real-time 3D mapping and rendering on mobile devices. It uses a **Sparse Voxel Hashing** approach to store and render a point cloud as "splats" (disks) with confidence scores.

## Key Components

### 1. Voxel Grid (`mVoxelGrid`)
*   **Type**: `std::unordered_map<VoxelKey, int, VoxelKeyHash>`
*   **Purpose**: Maps 3D integer coordinates (voxels) to an index in the `mSplats` vector.
*   **Logic**:
    *   The world is divided into cubes of size `VOXEL_SIZE` (defined in `MobileGS.h`).
    *   When a new depth point is detected, we calculate its voxel key.
    *   If the voxel is empty, a new splat is created.
    *   If the voxel is occupied, the existing splat is updated (position averaging and confidence boost).

### 2. Splat Structure
Contains:
*   `x, y, z`: Position in world space (meters).
*   `r, g, b, a`: Color and Opacity.
*   `confidence`: A score indicating how many times this voxel has been observed. Used for opacity blending.

### 3. Rendering Pipeline
The engine uses **GL_POINTS** for efficient rendering of the voxel map.
*   **Geometry**: Points are drawn from a vertex buffer (`m_DrawBuffer`) populated from `m_Splats`.
*   **Shader**: A custom shader (`VS_SRC`, `FS_SRC`) handles projection (MVP) and coloring.
*   **Attributes**: `vPosition` (Location 0) and `vColor` (Location 1).

### 4. Depth Processing (`processDepthFrame`)
*   **Input**: 16-bit depth buffer from ARCore (in millimeters) + Camera View & Projection matrices.
*   **Unprojection**:
    *   Depth pixels are unprojected to View Space using camera intrinsics derived from the Projection Matrix.
    *   View Space points are transformed to World Space using the Inverse View Matrix.
*   **Voxel Hashing**:
    *   World points are quantized into voxels.
    *   `mVoxelGrid` is queried to update existing points or add new ones.

## Memory Management
*   **Limits**: `MAX_SPLATS` is set to 500,000 to prevent OOM.
*   **Culling**: Points further than `CULL_DISTANCE` (5m) are ignored.

## JNI Interface
The engine is exposed to Kotlin via `GraffitiJNI.cpp`, which maps native pointers to Java `long` handles.

**Key Methods:**
*   `updateCamera(viewMtx, projMtx)`: Updates the camera pose using raw OpenGL matrices.
*   `feedDepthData(buffer, width, height)`: Ingests the 16-bit depth buffer for voxel unprojection.
*   `draw()`: Renders the current state of the voxel map.
*   `saveModel(path)` / `loadModel(path)`: Serializes/Deserializes the map to binary format.
