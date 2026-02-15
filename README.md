# GraffitiXR

**GraffitiXR** is a local-first, offline-capable Android application for street artists. It leverages Augmented Reality (AR) and a custom C++ engine to project sketches onto walls using a confidence-based voxel mapping system.

## Key Features
*   **Offline-First:** No cloud dependencies; zero data collected.
*   **Custom Engine (MobileGS):** C++17 native engine for 3D Gaussian Splatting and spatial mapping.
*   **AzNavRail UI:** Thumb-driven navigation for one-handed use in the field.
*   **Advanced Rendering:** Includes LiDAR-based occlusion, realistic light estimation, and Vulkan-ready architecture.
*   **Multi-Lens Support:** Automatically utilizes dual-camera setups for enhanced depth sensing on supported devices.
*   **Teleological Correction:** Automatic map-to-world alignment using OpenCV fingerprinting.

## Architecture
The project is built with a strictly decoupled multi-module architecture:
*   `:app`: Dependency injection (Hilt) and navigation.
*   `:feature:ar`: ARCore integration, camera handling, and sensor fusion.
*   `:feature:editor`: Image manipulation, mesh warp, and layer management.> Task :app:kspDebugKotlin
    e: [ksp] InjectProcessingStep was unable to process 'slamManager' because 'com.hereliesaz.graffitixr.domain.repository.ProjectRepository' could not be resolved.

Dependency trace:
=> element (CLASS): com.hereliesaz.graffitixr.MainActivity
=> element (FIELD): projectRepository
=> type (ERROR field type): com.hereliesaz.graffitixr.domain.repository.ProjectRepository

If type 'com.hereliesaz.graffitixr.domain.repository.ProjectRepository' is a generated type, check above for compilation errors that may have prevented the type from being generated. Otherwise, ensure that type 'com.hereliesaz.graffitixr.domain.repository.ProjectRepository' is on your classpath.
e: [ksp] InjectProcessingStep was unable to process 'projectRepository' because 'com.hereliesaz.graffitixr.domain.repository.ProjectRepository' could not be resolved.

Dependency trace:
=> element (CLASS): com.hereliesaz.graffitixr.MainActivity
=> element (FIELD): projectRepository
=> type (ERROR field type): com.hereliesaz.graffitixr.domain.repository.ProjectRepository

If type 'com.hereliesaz.graffitixr.domain.repository.ProjectRepository' is a generated type, check above for compilation errors that may have prevented the type from being generated. Otherwise, ensure that type 'com.hereliesaz.graffitixr.domain.repository.ProjectRepository' is on your classpath.

> Task :app:kspDebugKotlin FAILED

Execution failed for task ':app:kspDebugKotlin'.
> A failure occurred while executing com.google.devtools.ksp.gradle.KspAAWorkerAction
> KSP failed with exit code: PROCESSING_ERROR

*   `:core:nativebridge`: JNI interface and native engine management.
*   `:core:cpp`: C++17 engine source code.

## Setup & Building
1.  **Libraries:** Run `./setup_libs.sh` to fetch OpenCV and GLM.
2.  **NDK:** Ensure NDK 25.x or higher is installed.
3.  **Build:** Run `./gradlew assembleDebug`.

## Documentation
- [Architecture Overview](docs/ARCHITECTURE.md)
- [Phase 4 Roadmap](docs/PHASE_4_PLAN.md)
- [Native Engine Details](docs/NATIVE_ENGINE.md)
- [UI/UX Guidelines](docs/UI_UX.md)