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
*   `MainActivity.kt`: Entry point. Holds `ArViewModel by viewModels()` for `onResume/onPause` ARCore lifecycle. Passes `arViewModel` and `onRendererCreated` into `MainScreen`.
*   `MainScreen.kt`: `ArViewport` composable. Manages mode-based rendering (AR = `GLSurfaceView`+`GsViewer`, Overlay = CameraX, Mockup/Trace = static). Shows live tracking state chip in AR mode.
*   `MainViewModel.kt`: Cross-cutting state — touch lock, `CaptureStep` wizard for target creation.

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
*   `nativebridge/SlamManager.kt`: Kotlin JNI bridge. All native calls go through here. Key methods: `updateCamera`, `setCameraMotion`, `feedMonocularData`, `feedArCoreDepth`, `feedStereoData`, `feedLocationData`, `draw`, `saveModel`, `loadModel`, `saveKeyframe`, `initVulkanEngine`, `resizeVulkanSurface`, `destroyVulkanEngine`.
*   `src/main/cpp/GraffitiJNI.cpp`: JNI implementation. Contains `computeOpticalFlowDepth` (Lucas-Kanade optical flow, dynamic `kScale = gFocalLengthPx × gTranslationM`), `nativeSetCameraMotion`, `nativeFeedMonocularData`, `nativeFeedArCoreDepth`.
*   `src/main/cpp/MobileGS.cpp` / `MobileGS.h`: Sparse voxel hash map, `processDepthFrame`, `draw`, `saveModel/loadModel`.
*   `src/main/cpp/StereoProcessor.cpp`: Stereo disparity → depth pipeline.
*   `src/main/cpp/VulkanBackend.cpp` / `VulkanBackend.h`: Vulkan instance/device/swapchain, descriptor sets, push constants, overlay texture.

## Feature Modules

### `:feature:ar`
*   `ArViewModel.kt`: ARCore session lifecycle (`initArSession`, `attachSessionToRenderer`, `resumeArSession`, `pauseArSession`), GPS, flashlight, tracking state, keyframe capture.
*   `rendering/ArRenderer.kt`: `GLSurfaceView.Renderer`. Initialises `BackgroundRenderer`, tracks prev pose, extracts `camera.imageIntrinsics`, calls `setCameraMotion` + `feedMonocularData` + `feedArCoreDepth` each tracking frame. `onTrackingChanged` callback reports state to `ArViewModel`.
*   `rendering/BackgroundRenderer.kt`: OpenGL ES shader that renders ARCore's `EXTERNAL_OES` camera texture full-screen.
*   `CameraPreview.kt`: CameraX preview composable — used in Overlay mode only.
*   `computervision/TeleologicalTracker.kt`: OpenCV PnP-based anchor correction.
*   `computervision/DualAnalyzer.kt`: `ImageAnalysis.Analyzer` for monocular SLAM and light estimation callbacks (bound in Mapping mode).
*   `MappingActivity.kt` / `MappingScreen.kt`: Standalone mapping flow with camera permission handling and tracking HUD.
*   `src/test/.../TeleologicalTrackerTest.kt`: Unit tests for `trackAndCorrect` (PnP result handling, `Mat.release()`).
*   `src/test/.../DualAnalyzerTest.kt`: Unit tests for SLAM callback, light throttle, luminosity path.
*   `src/test/.../ArViewModelTest.kt`: Unit tests for session management, flashlight, GPS, keyframe.

### `:feature:editor`
*   `EditorViewModel.kt`: Layer operations, image manipulation, undo/redo, project save/load.
*   `GsViewer.kt`: `SurfaceView` with `setZOrderMediaOverlay(true)`. Initialises Vulkan engine on `surfaceCreated`; manages resize/destroy lifecycle.
*   `src/test/.../EditorViewModelTest.kt`: Unit tests for layer ops and bitmap dimensions.

### `:feature:dashboard`
*   `DashboardViewModel.kt`: Project library, settings navigation, new/open/delete project.
*   `ProjectLibraryScreen.kt`: Full-screen project list UI.

## Documentation (`docs/`)
*   `ARCHITECTURE.md`: Module graph, AR pipeline data flow, camera ownership rules.
*   `NATIVE_ENGINE.md`: MobileGS voxel system, rendering paths, full JNI method table, DEPTH16 encoding.
*   `SLAM_SETUP.md`: Engine parameters, full sensor input pipeline (calibration → monocular → depth → pose), tuning guide.
*   `PIPELINE_3D.md`: Per-frame data acquisition, voxel accumulation, Gaussian splatting, occlusion.
*   `testing.md`: Unit test locations, existing test files, mock patterns, field test procedure.
*   `screens.md`: Mode-based rendering layers, camera ownership per mode, secondary screens.
*   `TODO.md`: Phase-by-phase progress tracker including completed AR pipeline wiring.
