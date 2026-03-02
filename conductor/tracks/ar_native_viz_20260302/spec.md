# AR Native Visualization Enhancement

## Overview
This track replaces the current text-based "Tracking/Paused" indicator with real AR visualizations rendered directly by the native `MobileGS` engine. The goals are to provide immediate visual feedback that AR is functional and to show the current understanding of the physical environment (voxels/splats and surface mesh).

## Functional Requirements
- **Remove Text Indicator**: Remove the Compose-based `TrackingStatusChip` from the AR viewport in `MainScreen.kt`.
- **Native Point Cloud Rendering**: Implement `MobileGS::draw()` in C++ to render a point cloud (dots) representing the current voxel hash or splat map.
- **Native Surface Mesh Rendering**: Implement C++ rendering for a wireframe representation of the surface mesh/polygons generated from depth data.
- **Always On**: These visualizations should be active whenever the AR session is in `TRACKING` state.

## Non-Functional Requirements
- **Performance**: Native rendering should minimize CPU/GPU overhead to maintain a high frame rate.
- **Accuracy**: Visualizations should accurately reflect the internal state of the SLAM engine and depth maps.

## Acceptance Criteria
- [ ] No "Tracking" or "Paused" text is visible in the top left corner during AR mode.
- [ ] A point cloud (dots) is visible in the AR view, representing mapped environment features.
- [ ] A wireframe mesh is visible on physical surfaces, indicating depth map reconstruction.
- [ ] The visualizations update in real-time as the user move the device.
- [ ] The app maintains stable performance (no significant FPS drops) during AR mode.

## Out of Scope
- Customizing colors or point sizes via the UI.
- Toggle buttons or HUD controls for these visualizations (they are "Always On").
