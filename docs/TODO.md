# GraffitiXR Roadmap & Backlog

## 🔴 High Priority (The Core)

- [x] **Voxel Map Culling:** `MobileGS` garbage collection for old points.
- [x] **Serialization Speed:** Async map saving.
- [x] **Modularization:** Complete refactoring into `:core` and `:feature` modules.
- [x] **Unit Testing:** Increase coverage for new ViewModels and Repositories.

## 🟡 Medium Priority (Features)

- [x] **"Ghost" Toggle:** Toggle point cloud visibility.
- [x] **Fingerprint Aging:** Handle outdated ORB descriptors.
- [x] **Left-Handed Mode:** Invert AzNavRail alignment.
- [x] **Mockup Mode - Mesh Warp:** Non-linear warping for curved surfaces.

## 🟢 Low Priority (Polish)

- [x] **Occlusion:** Person/Object masking.

## Completed Features (Archive)

### V1.16 Rectify Image Targeting
- [x] Rectify Image mode.
- [x] `UnwarpScreen` with OpenCV perspective transform.

### V1.15 AzNavRail Upgrade & Surveyor Mode
- [x] `AzNavRail` v5.18.
- [x] `SlamManager` replacing `SlamCore`.
- [x] Native Photosphere capture.

### V1.14 UI/UX Enhancements
- [x] Trace Mode improvements (Gestures, Instructions).

### V1.13 Guided Target Creation
- [x] Guided Grid workflow.
- [x] Dynamic grid generation.

### V1.12 Android XR Readiness
- [x] Android XR SDK integration.
- [x] `compileSdk 36`.

### Architecture & Documentation
- [x] Multi-module split.
- [x] "God Object" ViewModel refactoring.
- [x] Comprehensive documentation update.

## Phase 3: Advanced AR (Next)
- [x] Target Creation Flow (Capture -> Rectify -> Track).
- [ ] Occlusion handling (Person/Object masking).
- [ ] Light Estimation for realistic rendering.

## Phase 4: Surveyor & 3D (Experimental)
- [x] Basic Gaussian Splatting Viewer integration (Stubbed).
- [ ] Photogrammetry capture pipeline.
- [ ] 3D Model import (.glb/.gltf) support.

**Status:** Updated to reflect that SLAM and 3D Mockups are now active features.
# Roadmap

## Phase 1: Foundation (Completed)
- [x] Multi-module project structure setup.
- [x] ARCore integration with basic plane detection.
- [x] Jetpack Compose UI with `AzNavRail`.
- [x] Unified State Architecture implementation.
- [x] **Save:** Project saving functionality.

## Phase 2: Core Editing (Completed)
- [x] Layer System (Add, Remove, Reorder).
- [x] Blend Modes (Multiply, Overlay, Screen, etc.).
- [x] Trace Mode (High contrast overlay).
- [x] Target Creation Flow (Capture -> Rectify).

## Phase 3: 3D & SLAM (Current Focus)
- [x] **SphereSLAM:** Basic keyframe and point cloud capture (`MappingScreen`).
- [x] **3D Mockup:** Integration of `GsViewer` into the Editor.
- [x] **Splat Training:** On-device training of Gaussian Splats from SLAM data (Online refinement implemented).
- [x] **Live Occlusion:** Using the sparse point cloud for real-time occlusion in AR mode.

## Phase 4: Polish & Scaling
- [ ] **LiDAR:** Mesh generation for Pro devices.
- [ ] **Optimizations:** Vulkan backend for Splat rasterization (Stubbed).
