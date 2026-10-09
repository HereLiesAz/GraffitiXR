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
*   `MainScreen.kt`: `ArViewport` composable. Selects the AR backend after ARCore capability
    resolves: supported devices keep the existing ARCore `ArRenderer`; unsupported devices keep AR
    mode and layer `SphereSlamStandaloneOverlay` over CameraX. Overlay mode remains CameraX and can
    use its separate legacy homography tracker on non-ARCore devices; Mockup/Trace are static.
*   `MainViewModel.kt`: Cross-cutting state — touch lock, Overlay ▸ Gyro toggle (`isOverlayGyroActive`), `CaptureStep` wizard for target creation, and the persisted first-run flag for the AR-unavailable explainer.
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

### SphereSLAM (published dependency)

SphereSLAM 0.23.5 public API baseline: GraffitiXR consumes the supported planar/coverage surface and explicitly opts into the experimental `:reloc` surface while preserving its existing `Standalone*` aliases.

The KPM planar tracker is no longer built in this repo. It is consumed as the published
`com.github.HereLiesAz.SphereSLAM:sphereslam` artifact, which carries the Kotlin API
(`SphereSlamStandaloneSession`, `SphereSlamTracker`, `SphereSlamPoseMath`, …) and the native
`libsphereslam.so` (artoolkitX KPM + JNI bridge). GraffitiXR's own `:core:nativebridge` no longer
compiles artoolkitX; `feature/ar` depends on the artifact and the release guards in `tools/` verify
the real (non-stub) tracker is packaged.

*   The native KPM atlas/session is rebuildable state and is never persisted; projects store the
    rectified reference PNG plus scale metadata.

## Feature Modules

### `:feature:ar`
*   `ArViewModel.kt`: ARCore session lifecycle for the ARCore backend plus shared flashlight/GPS
    state and crash-safe standalone SphereSLAM reference persistence.
*   `rendering/ArRenderer.kt`: ARCore `GLSurfaceView.Renderer`. Initialises `BackgroundRenderer`;
    keeps `ArCorePoseSource` as the primary camera pose, calls backend-neutral
    `setTrackingPoseValid`/`updateCamera`/`feedYuvFrame`, owns the asynchronous hybrid KPM sidecar,
    and composes only accepted timestamp-aligned corrections through `PoseFusion`.
*   `HybridMetricKpmReference.kt`: derives a physical rectangle from the ARCore wall plane,
    perspective-rectifies the captured sensor image to that physical aspect, and computes explicit
    KPM DPI/centered page geometry; a raw perspective photo is never treated as metric.
*   `HybridKpmPage.kt`: the persisted form of a hybrid KPM page (raw luma, pixel size, metric width,
    `page_from_artwork`) with validation; DPI/geometry are recomputed on restore, never stored.
*   `anchor/HybridPageFrame.kt`: pure ARCore/KPM frame conversion
    (`world_from_page`, `page_from_artwork`) with world-rebase-invariant math.
*   `anchor/HybridPoseHistory.kt`: bounded timestamp history pairing asynchronous KPM observations
    with the raw sensor-camera ARCore view and unfused artwork backbone from the same clock.
*   `anchor/HybridKpmCorrection.kt`: metric/age/inlier/reprojection/timestamp gates plus conversion
    of an accepted KPM observation into a corrected artwork anchor for `PoseFusion`.
*   `anchor/WallMeasure.kt`: pure two-tap wall measurement — screen ray, intersection with the
    drawn wall plane in wall-local metres, range/obliquity/rigidity gates (AR Measure).
*   `anchor/WallWidthPersistence.kt`: writes a Measure result to the project it was taken in;
    reports unsaved on a write failure or a project switch.
*   `anchor/PoseFusion.kt`: stores drift fixes as anchor-local corrections. It accepts both the
    legacy MobileGS PnP path and timestamp-aligned hybrid KPM corrections; neither path writes
    renderer camera matrices.
*   `SphereSlamStandaloneOverlay.kt`: non-ARCore AR surface. Owns rectified wall-target capture,
    CameraX analyzer lifecycle, KPM/reacquisition HUD, GL overlay, target restore, and copyable
    diagnostics.
*   `SphereSlamStandaloneTrackingAnalyzer.kt`: synchronous display-oriented CameraX luma →
    calibrated KPM pose pipeline with quality gates, observation-age checks, pose-jump rejection,
    tracking-state hysteresis, and the short rotation-only IMU bridge.
*   `StandaloneWallHitTest.kt`: ARCore-independent screen-pixel → canonical wall intersection.
    Back-projects calibrated CameraX rays onto SphereSLAM z=0 and accepts only visually locked,
    registered page/atlas coverage; no synthetic depth or ARCore `Frame.hitTest`.
*   `StandaloneAtlasGrowth.kt`: chooses/rectifies additional KPM wall pages and registers each into
    page 0's immutable canonical frame.
*   `StandaloneFailure.kt` / `StandaloneDiagnostics.kt`: typed standalone failure taxonomy and the
    compact diagnostic dump contract.
*   `rendering/HomographyOverlayRenderer.kt`: transparent CameraX GL overlay renderer shared by
    standalone SphereSLAM AR and the legacy homography Overlay tracker.
*   `rendering/BackgroundRenderer.kt`: OpenGL ES shader that renders ARCore's `EXTERNAL_OES`
    camera texture full-screen.
*   `CameraPreview.kt`: shared CameraX preview for Overlay and standalone SphereSLAM AR.
*   `OverlayGyroStabilizer.kt`: Overlay ▸ Gyro (tripod stabilisation). `rememberOverlayGyroCompensation`
    captures a rotation-vector reference (`GyroOrientationBridge`), publishes the per-frame
    pure-rotation screen homography MainScreen draws the Overlay design through, auto-releases past
    3°, and runs a slow (15 s) background MiDaS sample of the surface under the design.
*   `util/OverlayGyroCompensationMath.kt`: pure math for Gyro — body→display axis remap, rotation
    angle, FIT_CENTER screen intrinsics, `K·(R + t·nᵀ)·K⁻¹` homography, MiDaS patch sampling;
    pinned by `OverlayGyroCompensationMathTest`.
*   `HomographyFallbackOverlay.kt` / `HomographyArTracker.kt`: legacy planar live tracking, currently
    unmounted (Overlay draws the edited design over the camera untracked); not the standalone AR backend.
*   `computervision/DualAnalyzer.kt`: ARCore-side `ImageAnalysis.Analyzer` for relocalization
    callbacks and light estimation.
*   `src/test/.../ArViewModelTest.kt` plus standalone/hybrid tests: lifecycle/persistence,
    calibration, tracking-state/failure, metric KPM page geometry, page↔ARCore frame invariance,
    timestamp pairing, correction gates, and PoseFusion world-rebase behavior.

### `:feature:editor`
*   `EditorViewModel.kt`: Placement and legibility for the single design image (there is no
    multi-layer stack — see `FEATURE_REFERENCE.md`), undo/redo, project save/load. Does not own
    native SLAM state — `:feature:ar`'s `ArViewModel` restores a project's wall fingerprint.
*   `src/test/.../EditorViewModelTest.kt`: Unit tests for layer ops and bitmap dimensions.

### `:feature:dashboard`
*   `DashboardViewModel.kt`: Project library, settings navigation, new/open/delete project.
*   `ProjectLibraryScreen.kt`: Full-screen project list UI.

---
*Documentation updated on 2026-10-01: added the dual ARCore/SphereSLAM backend ownership,
standalone CameraX/KPM files, co-op frame calibration, and metric hybrid KPM→PoseFusion files. Earlier 2026-09-04 update removed the Persistent Voxel Memory /
`slamManager.draw()` /
`VoxelHash.*` / `StereoProcessor.cpp` claims (none of those files or methods exist), corrected the
`:core:nativebridge` and `:feature:ar` sections against the current native/Kotlin source. Prior
update: 2026-03-17, website redesign and Stencil generation integration phase.*