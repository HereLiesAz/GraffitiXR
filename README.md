# GraffitiXR

**GraffitiXR** is a local-first, offline-capable Android application for street artists. It leverages Augmented Reality (AR) and a custom C++ engine to project sketches onto walls using a confidence-based voxel mapping system.

## Key Features
*   **Offline-First:** No cloud dependencies; zero data collected.
*   **Custom Engine (MobileGS):** C++17 native engine for 3D Gaussian Splatting and spatial mapping.
*   **Full ARCore Pipeline:** Live camera feed via `BackgroundRenderer`, monocular optical-flow depth, and ARCore Depth API — all feeding real data to the SLAM engine.
*   **Dynamic Depth Calibration:** Inter-frame camera pose and intrinsics used to compute accurate `kScale` for optical-flow depth estimation each frame.
*   **AzNavRail UI:** Thumb-driven navigation for one-handed use in the field.
*   **Dual Render Paths:** OpenGL ES (`ArRenderer`) for camera background; Vulkan (`GsViewer`) for SLAM voxel splats.
*   **Multi-Lens Support:** Automatically uses dual-camera stereo depth on supported devices; falls back to optical flow.
*   **Teleological Correction:** Automatic map-to-world alignment using OpenCV fingerprinting.

## Architecture
Strictly decoupled multi-module architecture:
*   `:app` — Navigation, `ArViewport` composable, camera ownership orchestration.
*   `:feature:ar` — ARCore session, `ArRenderer`, `BackgroundRenderer`, sensor fusion, SLAM data feeding.
*   `:feature:editor` — Image manipulation, layer management, `GsViewer` (Vulkan).
*   `:feature:dashboard` — Project library, settings.
*   `:core:nativebridge` — `SlamManager` JNI bridge, `MobileGS` voxel engine, optical flow depth, Vulkan backend.
*   `:core:data` / `:core:domain` / `:core:common` — Clean Architecture data layer.

## Setup & Building
1.  **Libraries:** Run `./setup_libs.sh` to fetch OpenCV and GLM.
2.  **NDK:** Ensure NDK 25.x or higher is installed.
3.  **Firebase:** Copy `app/google-services.json.template` → `app/google-services.json` for local builds.
4.  **Build:** `./gradlew assembleDebug`
5.  **Tests:** `./gradlew testDebugUnitTest`

## Documentation
- [Architecture Overview](docs/ARCHITECTURE.md)
- [Native Engine Details](docs/NATIVE_ENGINE.md)
- [SLAM Configuration & Tuning](docs/SLAM_SETUP.md)
- [3D Pipeline Specification](docs/PIPELINE_3D.md)
- [Testing Strategy](docs/testing.md)
- [Screen & Mode Reference](docs/screens.md)
- [Roadmap](docs/TODO.md)
