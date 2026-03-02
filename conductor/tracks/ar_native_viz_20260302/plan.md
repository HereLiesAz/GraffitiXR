# Implementation Plan: AR Native Visualization Enhancement

## Overview
This plan implements native point cloud and surface mesh rendering in the `MobileGS` engine and removes the placeholder text-based tracking indicator.

## Phases

### Phase 1: Cleanup & Preparation
**Goal**: Remove the text indicator from the UI and prepare `ArRenderer` for the new visualizations.
- [ ] Task: Remove `TrackingStatusChip` from `MainScreen.kt`.
- [ ] Task: Remove `trackingState` text update in `ArRenderer.kt`.
- [ ] Task: Update `ArUiState` to remove `trackingState` (or keep it if needed elsewhere, but don't show it).
- [ ] Task: Conductor - User Manual Verification 'Phase 1: Cleanup & Preparation' (Protocol in workflow.md)

### Phase 2: Native Point Cloud Rendering
**Goal**: Implement GLES rendering for the point cloud (voxels/splats) in the native engine.
- [ ] Task: Write a unit test to verify `SlamManager.draw()` can be called safely and returns no errors.
- [ ] Task: Implement basic GLES vertex buffer management in `MobileGS.cpp` for the point cloud.
- [ ] Task: Implement `MobileGS::draw()` to render the accumulated voxels as dots.
- [ ] Task: Verify point cloud visibility in AR mode.
- [ ] Task: Conductor - User Manual Verification 'Phase 2: Native Point Cloud Rendering' (Protocol in workflow.md)

### Phase 3: Native Surface Mesh Rendering
**Goal**: Implement GLES rendering for the surface mesh (wireframe) from depth data.
- [ ] Task: Write a unit test to verify depth data feeding into the native engine results in a valid mesh state.
- [ ] Task: Implement depth-to-mesh vertex generation in `MobileGS.cpp`.
- [ ] Task: Implement wireframe rendering for the generated mesh in `MobileGS::draw()`.
- [ ] Task: Verify mesh visibility in AR mode.
- [ ] Task: Conductor - User Manual Verification 'Phase 3: Native Surface Mesh Rendering' (Protocol in workflow.md)

### Phase 4: Final Verification & Performance Polish
**Goal**: Ensure both visualizations are stable and the app performs well.
- [ ] Task: Optimize vertex buffer updates to minimize JNI-to-Native overhead.
- [ ] Task: Final cross-device performance check.
- [ ] Task: Conductor - User Manual Verification 'Phase 4: Final Verification & Performance Polish' (Protocol in workflow.md)
