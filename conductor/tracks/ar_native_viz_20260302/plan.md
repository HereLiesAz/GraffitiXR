# Implementation Plan: AR Native Visualization Enhancement

## Overview
This plan implements native point cloud and surface mesh rendering in the `MobileGS` engine and removes the placeholder text-based tracking indicator and the standalone Mapping/Surveyor flow.

## Phases

### Phase 1: Cleanup & Removal
**Goal**: Remove the tracking text indicator and delete the standalone Mapping/Surveyor flow.
- [x] Task: Remove `TrackingStatusChip` from `MainScreen.kt`.
- [x] Task: Delete `feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/MappingActivity.kt` and `MappingScreen.kt`.
- [x] Task: Remove `MappingActivity` from `feature/ar/src/main/AndroidManifest.xml`.
- [x] Task: Remove "surveyor" intent handling in `MainActivity.kt`.
- [x] Task: Update `ArViewModel.kt` to remove unused tracking state update logic.
- [x] Task: Conductor - User Manual Verification 'Phase 1: Cleanup & Removal' (Protocol in workflow.md)

### Phase 2: Native Point Cloud Rendering
**Goal**: Implement GLES rendering for the point cloud (voxels/splats) in the native engine.
- [x] Task: Write a unit test to verify `SlamManager.draw()` can be called safely and returns no errors.
- [x] Task: Implement basic GLES vertex buffer management in `MobileGS.cpp` for the point cloud.
- [x] Task: Implement `MobileGS::draw()` to render the accumulated voxels as dots.
- [x] Task: Verify point cloud visibility in AR mode.
- [x] Task: Conductor - User Manual Verification 'Phase 2: Native Point Cloud Rendering' (Protocol in workflow.md)

### Phase 3: Native Surface Mesh Rendering
**Goal**: Implement GLES rendering for the surface mesh (wireframe) from depth data.
- [x] Task: Write a unit test to verify depth data feeding into the native engine results in a valid mesh state.
- [x] Task: Implement depth-to-mesh vertex generation in `MobileGS.cpp`.
- [x] Task: Implement wireframe rendering for the generated mesh in `MobileGS::draw()`.
- [x] Task: Verify mesh visibility in AR mode.
- [x] Task: Conductor - User Manual Verification 'Phase 3: Native Surface Mesh Rendering' (Protocol in workflow.md)

### Phase 4: Final Verification & Performance Polish
**Goal**: Ensure both visualizations are stable and the app performs well.
- [x] Task: Optimize vertex buffer updates to minimize JNI-to-Native overhead.
- [x] Task: Final cross-device performance check.
- [x] Task: Conductor - User Manual Verification 'Phase 4: Final Verification & Performance Polish' (Protocol in workflow.md)
