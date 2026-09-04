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
*   OpenCV (Java + native C++) is imported from Maven Central (`org.opencv:opencv`, native via its Prefab part); GLM headers are committed under `core/nativebridge/libs/glm`.

## Application (`:app`)
*   `MainActivity.kt`: Entry point. Holds `ArViewModel by viewModels()` for `onResume/onPause` ARCore lifecycle. Configures the AzNavRail (10.18) via `azTheme()`, `azConfig()`, `azAdvanced(helpEnabled = true, helpList = …)`, registers the rail items (`ConfigureRailItems`), and the reactive guidance graph (`ConfigureGuidance`). Captures the host-provided `AzGuidanceController` from `LocalAzGuidanceController.current` so the Help item can replay the tour. Passes `arViewModel` and `onRendererCreated` into `MainScreen`.
*   `MainScreen.kt`: `ArViewport` composable. Manages mode-based rendering (AR = `GLSurfaceView` —
    `ArRenderer` handles the camera feed and the AR overlay composite; Overlay = CameraX or, on the
    small number of ARCore-unavailable devices, a planar homography tracker; Mockup/Trace = static).
    Shows live tracking state chip in AR mode.
*   `MainViewModel.kt`: Cross-cutting state — touch lock, `CaptureStep` wizard for target creation, and the persisted first-run flag for the AR-unavailable explainer.
*   `GuidanceDefinitions.kt`: The reactive status-driven guidance graph (AzNavRail 10.18) that replaced the old scripted-tutorial API and the hand-built onboarding coach. Declares `azStatus`/`azEdge`/`azGoal`/`azSuppressGuide` reusing the existing `onboarding_*` strings; per-mode goals self-activate on mode entry and persist completion.
*   `HelpItemsBuilder.kt`: Builds the `helpList` map for the rail's help overlay (rail-item id → help text).
*   `RailIntegrityCheck.kt`: Debug-only invariants — validates helpList keys and guidance highlight ids against the registered rail items.

## Core Modules

### `:core:common`
*   `common/model/UiState.kt`: Shared state data classes (`ArUiState`, `EditorUiState`, `GpsData`, `SensorData`).
*   `common/util/ImageUtils.kt`: Bitmap loading/decoding helpers used across the editor and AR
    pickers. (There is no `ImageProcessingUtils.kt` — it was replaced by
    `core/nativebridge/.../YuvConverter.kt`, a direct native YUV→RGBA JNI binding, and deleted;
    `solvePnP`/fingerprinting live in the native `MobileGS` engine, not a Kotlin wrapper.)

### `:core:domain`
*   `domain/repository/ProjectRepository.kt`: Interface for project data access.

### `:core:data`
*   `data/ProjectManager.kt`: File system I/O — project list, delete, GXRM map path, zip import.
*   `data/repository/ProjectRepositoryImpl.kt`: `ProjectRepository` implementation with GPS/layer persistence.
*   `src/test/.../ProjectManagerTest.kt`: Unit tests for `ProjectManager` (file I/O, zip import failure paths).

### `:core:nativebridge`
*   `nativebridge/SlamManager.kt`: Kotlin JNI bridge. All native calls go through here. Key methods:
    `updateCamera`, `feedYuvFrame`/`feedColorFrame` (relocalization thread input), `setWallFingerprint`
    /`restoreWallFingerprintMetric` (target creation / project load), `getAnchorTransform`,
    `setRelocEnabled`/`setSelfGrowEnabled`/`setMapRelocEnabled`/`setMapBuildEnabled` (diagnostic-only
    toggles), `loadDistortionHead`. There is no `draw()` and no `importModel3D` — no persistent 3D map
    is rendered by this engine.
*   `src/main/cpp/GraffitiJNI.cpp`: JNI implementation. All entry points serialize through a single
    `gEngineMutex` guarding the native `MobileGS` singleton's lifetime.
*   `src/main/cpp/MobileGS.cpp` / `MobileGS.h`: The relocalization engine. Runs the background
    relocalization thread (`relocThreadFunc` / `runRelocPass`): ORB/SuperPoint detection, Lowe-ratio
    matching against the stored wall fingerprint, `solvePnPRansac`, plane-guided rectification, and
    (when `distortion_head.onnx` is bundled — the shipped default) the distortion-head crop that
    produces painting-progress and corroboration confidence. No voxel/spatial-hash map — see
    `NATIVE_ENGINE.md`.
*   `src/main/cpp/DistortionHead.cpp`: ONNX inference wrapper for the distortion-head model —
    `docs/DISTORTION_HEAD.md`.
*   `src/main/cpp/SuperPointDetector.cpp`: ONNX SuperPoint keypoint/descriptor detector, used as an
    alternative to ORB in the relocalization and fingerprint-building paths.
*   `src/main/cpp/HomographyTracker.cpp`: Planar homography tracker used by Overlay mode on the small
    number of devices without ARCore (`docs/UI_UX.md`).
*   `src/main/cpp/LowLightEnhancer.cpp`: Low-light frame enhancement for feature detection.
*   `src/main/cpp/MlasStub.cpp`: Build-time stub patching a missing ONNX Runtime symbol.

## Feature Modules

### `:feature:ar`
*   `ArViewModel.kt`: ARCore session lifecycle (`initArSession`, `attachSessionToRenderer`, `resumeArSession`, `pauseArSession`), GPS, flashlight, tracking state, keyframe capture.
*   `rendering/ArRenderer.kt`: `GLSurfaceView.Renderer`. Initialises `BackgroundRenderer`; calls
    `setArCoreTrackingState`, `updateCamera`, `feedYuvFrame`/`feedColorFrame` each frame, and composes
    the AR overlay via `PoseFusion` — no `draw()` call, no map to render. `onTrackingUpdated: (Boolean)`
    callback reports state to `ArViewModel`.
*   `rendering/BackgroundRenderer.kt`: OpenGL ES shader that renders ARCore's `EXTERNAL_OES` camera texture full-screen.
*   `CameraPreview.kt`: CameraX preview composable — used in Overlay mode on ARCore-available devices.
*   `computervision/DualAnalyzer.kt`: `ImageAnalysis.Analyzer` for relocalization callbacks and light estimation.
*   `src/test/.../DualAnalyzerTest.kt`: Unit tests for SLAM callback, light throttle, luminosity path.
*   `src/test/.../ArViewModelTest.kt`: Unit tests for session management, flashlight, GPS, keyframe.

### `:feature:editor`
*   `EditorViewModel.kt`: Placement and legibility for the single design image (there is no
    multi-layer stack — see `FEATURE_REFERENCE.md`), undo/redo, project save/load. Does not own
    native SLAM state — `:feature:ar`'s `ArViewModel` restores a project's wall fingerprint.
*   `src/test/.../EditorViewModelTest.kt`: Unit tests for layer ops and bitmap dimensions.

### `:feature:dashboard`
*   `DashboardViewModel.kt`: Project library, settings navigation, new/open/delete project.
*   `ProjectLibraryScreen.kt`: Full-screen project list UI.

---
*Documentation updated on 2026-09-04: removed the Persistent Voxel Memory / `slamManager.draw()` /
`VoxelHash.*` / `StereoProcessor.cpp` claims (none of those files or methods exist), corrected the
`:core:nativebridge` and `:feature:ar` sections against the current native/Kotlin source. Prior
update: 2026-03-17, website redesign and Stencil generation integration phase.*