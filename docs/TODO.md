# GraffitiXR Roadmap & Backlog

## 🔴 High Priority (The Core)

- [x] **Voxel Map Culling:** `MobileGS` garbage collection for old points.
- [x] **Serialization Speed:** Async map saving.
- [x] **Modularization:** Complete refactoring into `:core` and `:feature` modules.
- [ ] **Unit Testing:** Increase coverage for new ViewModels and Repositories.

## 🟡 Medium Priority (Features)

- [x] **"Ghost" Toggle:** Toggle point cloud visibility.
- [x] **Fingerprint Aging:** Handle outdated ORB descriptors.
- [x] **Left-Handed Mode:** Invert AzNavRail alignment.
- [ ] **Mockup Mode - Mesh Warp:** Non-linear warping for curved surfaces.

## 🟢 Low Priority (Polish)

- [ ] **Cloud Anchors:** Multi-user AR sessions.
- [ ] **Occlusion:** Person/Object masking.

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
