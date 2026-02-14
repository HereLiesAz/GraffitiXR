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
- [x] **Vulkan Backend:** Scaffolded for high-density splat rasterization.
- [x] **Multi-Lens Depth:** Support for dual-camera depth sensing.
- [x] **3D Imports:** .glb/.gltf support (JNI stubbed for v1.0).
- [x] **Photogrammetry:** Keyframe capture pipeline for external reconstruction.
- [x] **CI/CD:** Static analysis (Checkstyle) and build hardening.

## Ongoing
- [ ] UI/UX refinement for one-handed operation.
- [ ] Performance benchmarking on mid-range devices.
- [ ] Community feedback loop.