// FILE: docs/file_descriptions.md
# File Registry

This document lists key files in the repository and their purposes.

## Root
*   `README.md`: Project overview and setup instructions.
*   `CLAUDE.md`: Guidance for Claude Code — build commands, architecture, conventions, testing patterns.
*   `build.gradle.kts`: Root build configuration.
*   `settings.gradle.kts`: Module inclusion settings.
*   `gradle/libs.versions.toml`: Version catalog — all dependency versions are defined here.
*   `version.properties`: App Major/Minor version; build number auto-increments from git commit count.
*   `setup_libs.sh`: Script to download OpenCV and GLM native libraries.

## Application (`:app`)
*   `MainActivity.kt`: Entry point. Holds `ArViewModel by viewModels()` for `onResume/onPause` ARCore lifecycle. Configures the AzNavRail (10.18) via `azTheme()`, `azConfig()`, `azAdvanced(helpEnabled = true, helpList = …)`, registers the rail items (`ConfigureRailItems`), and the reactive guidance graph (`ConfigureGuidance`). Captures the host-provided `AzGuidanceController` from `LocalAzGuidanceController.current` so the Help item can replay the tour. Passes `arViewModel` and `onRendererCreated` into `MainScreen`.
*   `MainScreen.kt`: `ArViewport` composable. Manages mode-based rendering (AR = `GLSurfaceView` — `ArRenderer` handles both camera feed and SLAM splats, Overlay = CameraX, Mockup/Trace = static). Shows live tracking state chip in AR mode.
*   `MainViewModel.kt`: Cross-cutting state — touch lock, `CaptureStep` wizard for target creation, and the persisted first-run flag for the AR-unavailable explainer.
*   `GuidanceDefinitions.kt`: The reactive status-driven guidance graph (AzNavRail 10.18) that replaced the old scripted-tutorial API and the hand-built onboarding coach. Declares `azStatus`/`azEdge`/`azGoal`/`azSuppressGuide` reusing the existing `onboarding_*` strings; per-mode goals self-activate on mode entry and persist completion.
*   `HelpItemsBuilder.kt`: Builds the `helpList` map for the rail's help overlay (rail-item id → help text).
*   `RailIntegrityCheck.kt`: Debug-only invariants — validates helpList keys and guidance highlight ids against the registered rail items.

## Core Modules

### `:core:common`
*   `common/model/UiState.kt`: Shared state data classes (`ArUiState`, `EditorUiState`, `GpsData`, `SensorData`).
*   `common/util/ImageProcessingUtils.kt`: OpenCV wrappers (`solvePnP`, fingerprinting helpers).

### `:core:domain`
*   `domain/repository/ProjectRepository.kt`: Interface for project data access.

### `:core:data`
*   `data/ProjectManager.kt`: File system I/O — project list, delete, GXRM map path, zip import.
*   `data/repository/ProjectRepositoryImpl.kt`: `ProjectRepository` implementation with GPS/layer persistence.
*   `src/test/.../ProjectManagerTest.kt`: Unit tests for `ProjectManager` (file I/O, zip import failure paths).

### `:core:nativebridge`
*   `nativebridge/SlamManager.kt`: Kotlin JNI bridge. All native calls go through here. Key methods: `updateCamera`, `updateAnchorTransform`, `setArCoreTrackingState`, `feedColorFrame`, `feedArCoreDepth`, `feedStereoData`, `draw`, `importModel3D`.
*   `src/main/cpp/GraffitiJNI.cpp`: JNI implementation. Handles mandatory HW stereo routing and stochastic depth feeding.
*   `src/main/cpp/MobileGS.cpp` / `MobileGS.h`: High-performance native entry point. Manages **Persistent Voxel Memory**, stochastic depth integration, and the background **Snap-Back Thread** for tracking recovery.
*   `src/main/cpp/VoxelHash.cpp` / `VoxelHash.h`: Core spatial memory implementation. Uses a **Zero-Allocation Spatial Hash Table** and O(1) opaque rendering for rock-solid mobile tracking.
*   `src/main/cpp/StereoProcessor.cpp`: Stereo disparity → depth pipeline.

## Feature Modules

### `:feature:ar`
*   `ArViewModel.kt`: ARCore session lifecycle (`initArSession`, `attachSessionToRenderer`, `resumeArSession`, `pauseArSession`), GPS, flashlight, tracking state, keyframe capture.
*   `rendering/ArRenderer.kt`: `GLSurfaceView.Renderer`. Initialises `BackgroundRenderer`; calls `setArCoreTrackingState`, `updateCamera`, `feedArCoreDepth`, `feedColorFrame`, and `slamManager.draw()` each frame. `onTrackingUpdated: (Boolean)` callback reports state to `ArViewModel`.
*   `rendering/BackgroundRenderer.kt`: OpenGL ES shader that renders ARCore's `EXTERNAL_OES` camera texture full-screen.
*   `CameraPreview.kt`: CameraX preview composable — used in Overlay mode only.
*   `computervision/DualAnalyzer.kt`: `ImageAnalysis.Analyzer` for SLAM callbacks and light estimation.
*   `src/test/.../DualAnalyzerTest.kt`: Unit tests for SLAM callback, light throttle, luminosity path.
*   `src/test/.../ArViewModelTest.kt`: Unit tests for session management, flashlight, GPS, keyframe.

### `:feature:editor`
*   `EditorViewModel.kt`: Layer operations, image manipulation, undo/redo, project save/load.
*   `src/test/.../EditorViewModelTest.kt`: Unit tests for layer ops and bitmap dimensions.

### `:feature:dashboard`
*   `DashboardViewModel.kt`: Project library, settings navigation, new/open/delete project.
*   `ProjectLibraryScreen.kt`: Full-screen project list UI.