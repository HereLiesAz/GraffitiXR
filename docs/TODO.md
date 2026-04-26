// FILE: docs/TODO.md
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

## Phase 3: High-Performance Mapping `[DONE]`
- [x] **Persistent Voxel Memory:** Zero-allocation spatial hash for 60fps tracking.
- [x] **Pocket-Ready Relocalization:** PnP-based snap-back recovery after screen-off.
- [x] **Stochastic Integration:** 90% reduction in depth processing overhead.
- [x] **Mandatory Dual Lens:** Forced hardware stereo for high-quality spatial memory.
- [x] **Opaque Surfel Pipeline:** O(1) rendering without expensive alpha sorting.

## Phase 4: Final Polishing & Optimization `[DONE]`
- [x] **3D Imports:** .obj point-cloud ingestion implemented natively.
- [x] **Photogrammetry:** Keyframe capture pipeline (OpenCV + Pose metadata).
- [x] **CI/CD:** Static analysis (**Detekt**) and build hardening.
- [x] **Stencil Mode:** Automatic multi-layer stencil generation.

## AR Pipeline — End-to-End Wiring `[DONE]`
- [x] **ARCore session wiring:** Mandatory hardware stereo enforcement.
- [x] **Relocalization:** Background thread for continuous fingerprint matching.
- [x] **Depth API:** Stochastic sampling with confidence rewards (0.9 HW vs 0.5 Mono).
- [x] **Mode-based camera ownership:** AR = ARCore; Overlay = CameraX.

## Ongoing
- [ ] UI/UX refinement for one-handed operation.
- [ ] Performance benchmarking on entry-level devices.
- [ ] Community feedback loop.
- [x] LRU culling in `VoxelHash` to handle `MAX_SPLATS` (250k) limits.
