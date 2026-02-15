# Research: Passive Triangulation (Dual Lens Support)

## Objective
Enable depth estimation using dual-lens cameras (stereo) for devices without LiDAR.

## Technical Approach
1.  **Stereo Matching:** Use OpenCV `StereoBM` or `StereoSGBM` (Semi-Global Block Matching) to compute disparity map from two rectified images.
2.  **Input:** Need synchronized frames from wide and ultra-wide cameras (or main + tele).
    *   *Challenge:* Android Camera2 API makes simultaneous access to physical cameras tricky on some devices (need `logical` multi-camera support).
3.  **Process:**
    *   Capture pair.
    *   Rectify (using intrinsics/extrinsics if available via `CameraCharacteristics`).
    *   Compute Disparity Map.
    *   Reproject to 3D (Depth Map).
    *   Feed to `MobileGS` engine (same pipeline as LiDAR).

## Feasibility
*   **High:** OpenCV has `StereoSGBM`.
*   **Performance:** Heavy. Needs downscaling (e.g., 320x240 disparity map).
*   **Integration:** Add `StereoDepthProvider` class implementing `DepthProvider` interface (alongside `LidarDepthProvider`).

## Action Plan
1.  Check `Camera2` capabilities for `REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA`.
2.  Implement `StereoDepthProvider` using OpenCV.
3.  Update `ArViewModel` to select provider based on hardware.
