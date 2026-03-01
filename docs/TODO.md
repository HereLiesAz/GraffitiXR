# GraffitiXR TODO

## Phase 1: Core Architecture & Setup `[DONE]`
- [x] Multi-module project structure.
- [x] Native C++ engine (`MobileGS`) integration via JNI.
- [x] AzNavRail navigation system.
- [x] Local-only project storage (.gxr).

## Phase 2: Feature Implementation `[DONE]`
- [x] **AR Projection:** 6DOF tracking with ARCore.
- [x] **Mockup Mode:** Image manipulation with 3x3 Mesh Warp.
- [x] **Trace (Lightbox):** Full-screen image overlay with transparency.
- [x] **Overlay Screen:** Layer management and blend modes.

## Phase 3: Advanced Rendering & CV `[DONE]`
- [x] **Gaussian Splatting:** 3D point cloud rendering with depth falloff.
- [x] **Teleological Loop:** Fingerprint-based auto-alignment (OpenCV).
- [x] **Voxel Mapping:** Efficient spatial hashing for large-scale maps.
- [x] **Occlusion:** LiDAR-based mesh generation for real-world object masking.
- [x] **Light Estimation:** Realistic lighting for virtual projections.

## Phase 4: Final Polishing & Optimization `[DONE]`
- [x] **Vulkan Backend:** Scaffolded for high-density splat rasterization (Lifecycle refined).
- [x] **Multi-Lens Depth:** Support for dual-camera depth sensing.
- [x] **3D Imports:** .glb/.gltf support (Stub improved).
- [x] **Photogrammetry:** Keyframe capture pipeline fully implemented (OpenCV + Pose metadata).
- [x] **CI/CD:** Static analysis (Checkstyle) and build hardening.

## Production Readiness Refinement `[DONE]`
- [x] **Thread Safety:** Fixed critical race condition in `MobileGS::uploadSplatData`.
- [x] **Performance:** Optimized splat sorting and VBO uploads based on camera movement.
- [x] **UI Integration:** Added "Keyframe" capture entry point to the navigation rail.
- [x] **Code Quality:** Resolved compiler warnings and cleaned up redundant null checks in `ArRenderer`.

## AR Pipeline — End-to-End Wiring `[DONE]`
- [x] **ARCore session wiring:** `ArViewModel.initArSession/resume/pauseArSession`; session lifecycle via `DisposableEffect` (mode) + `MainActivity.onResume/onPause` (activity).
- [x] **Camera background:** `BackgroundRenderer` wired to `ArRenderer`; real ARCore camera feed renders in AR mode. CameraX no longer active in AR mode.
- [x] **Monocular SLAM feed:** `frame.acquireCameraImage()` Y-plane → `feedMonocularData` → native optical flow → `processDepthFrame` — SLAM engine receives real data for the first time.
- [x] **ARCore Depth API:** `frame.acquireDepthImage16Bits()` → `feedArCoreDepth` → DEPTH16 decode → `processDepthFrame` (metric override).
- [x] **Dynamic kScale:** `camera.imageIntrinsics.focalLength` + inter-frame `camera.pose` delta → `setCameraMotion` → native `kScale = focalLengthPx × translationM` (replaces fixed 24.0 constant).
- [x] **Tracking state feedback:** `ArRenderer.onTrackingChanged` callback → `arViewModel.updateTrackingState` → live colored chip overlay in `ArViewport`.
- [x] **Mode-based camera ownership:** AR = ARCore; Overlay = CameraX; `DisposableEffect` pauses ARCore session when leaving AR mode to release camera.
- [x] **Unit tests:** `TeleologicalTrackerTest`, `DualAnalyzerTest`, `ProjectManagerTest` — covering CV pipeline, SLAM callbacks, and project I/O.

## Ongoing
- [ ] UI/UX refinement for one-handed operation.
- [ ] Performance benchmarking on mid-range devices.
- [ ] Community feedback loop.
- [ ] LRU culling in `MobileGS::pruneMap()` to handle `MAX_SPLATS` overflow on long scans.
- [ ] `nativeGetSplatCount()` JNI function to surface live voxel count in the tracking state HUD.
