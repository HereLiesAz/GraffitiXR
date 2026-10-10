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

SphereSLAM 0.24.0 public API baseline: GraffitiXR consumes the supported planar/coverage surface
and toolkit packages, and explicitly opts into the experimental `:reloc` and `:models` surfaces while
preserving its existing `Standalone*` aliases.

The KPM planar tracker is no longer built in this repo. It is consumed as the published
`com.github.HereLiesAz.SphereSLAM:sphereslam` artifact, which carries the Kotlin API
(`SphereSlamStandaloneSession`, `SphereSlamTracker`, `SphereSlamPoseMath`, …) and the native
`libsphereslam.so` (artoolkitX KPM + JNI bridge). GraffitiXR's own `:core:nativebridge` no longer
compiles artoolkitX; `feature/ar` depends on the artifact and the release guards in `tools/` verify
the real (non-stub) tracker is packaged.

*   The native KPM atlas/session is rebuildable state and is never persisted; projects store the
    rectified reference PNG plus scale metadata.
*   Generic SLAM code that used to live here now comes from the library (0.24.0):
    `math.RigidMath` / `math.RotationMath` (was `anchor/PoseMath`, `util/RotationDeltaMath`,
    `MetricMarks.glViewToCv`); `camera.*` (was `core/common` `CameraIntrinsicsEstimator`,
    `StandaloneCameraCalibration`, `anchor/CaptureRotation`, `LumaFrameTransform`,
    `rendering/ProjectionMatrix`, the Gyro screen intrinsics); `attitude.*` (was
    `GyroOrientationBridge` and the analyzer's bridge math); `SphereSlam.isOperational()` (was
    `pose/SphereSlamRuntimeProbe`); `sidecar.*` (was `anchor/HybridPoseHistory`,
    `HybridKpmCorrection`, `HybridPageFrame`, `HybridMetricKpmReference` and PoseFusion's
    hybrid-agreement path); `PhotosphereKeyframeStore` / `PhotosphereFingerprintFrame` /
    `PhotosphereMap.directionOfPixel` (was ArViewModel's keyframe map and placement math and
    `anchor/StandaloneFingerprintFrame`); `gyro.GyroCompensationMath` (was
    `util/OverlayGyroCompensationMath`); `:overlay` `CoverageGlowRenderer` (was the app's scissor
    wash); `:models` `DepthEstimator` (was `depth/DepthEstimator`); `:reloc` `PoseBlend` for the
    standalone reloc corroboration blend.
*   `core/common` `sensor/CameraIntrinsics` and `model/HybridKpmDiagnostics`/`HybridKpmOutcome` are
    typealiases of the library types (`core/common` depends on `:sphereslam` with `api`).

## Feature Modules

### `:feature:ar`
*   `ArViewModel.kt`: ARCore session lifecycle for the ARCore backend plus shared flashlight/GPS
    state and crash-safe standalone SphereSLAM reference persistence.
*   `rendering/ArRenderer.kt`: ARCore `GLSurfaceView.Renderer`. Initialises `BackgroundRenderer`;
    keeps `ArCorePoseSource` as the primary camera pose, calls backend-neutral
    `setTrackingPoseValid`/`updateCamera`/`feedYuvFrame`, owns the asynchronous hybrid KPM sidecar,
    and composes only accepted timestamp-aligned corrections through `PoseFusion`.
*   Hybrid KPM sidecar math is SphereSLAM's `sidecar` package, wired here by `ArRenderer`:
    `MetricPageRectification` (physical rectangle on the ARCore wall plane, rectified to its
    physical aspect, explicit KPM DPI/page geometry), `HybridPoseHistory` (timestamp pairing with
    the raw sensor-camera ARCore view and unfused backbone), `HybridKpmCorrection` (metric/age/
    inlier/reprojection/plausibility gates + solve) and `HybridPageFrame` (`world_from_page`,
    `page_from_artwork`).
*   `HybridKpmPage.kt`: the persisted form of a hybrid KPM page (raw luma, pixel size, metric width,
    `page_from_artwork`) with validation; DPI/geometry are recomputed on restore, never stored.
*   `anchor/WallMeasure.kt`: pure two-tap wall measurement — screen ray, intersection with the
    drawn wall plane in wall-local metres, range/obliquity/rigidity gates (AR Measure).
*   `anchor/WallWidthPersistence.kt`: writes a Measure result to the project it was taken in;
    reports unsaved on a write failure or a project switch.
*   `anchor/PoseFusion.kt`: stores drift fixes as one anchor-local correction over the ARCore
    backbone. The MobileGS PnP path is its own; the timestamp-aligned hybrid KPM path delegates to
    SphereSLAM `sidecar.HybridAnchorFusion` (repeated-observation agreement before a large move)
    while keeping the shared correction and `FusionDiagnostics`. Neither path writes renderer
    camera matrices.
*   `SphereSlamStandaloneOverlay.kt`: non-ARCore AR surface. Owns rectified wall-target capture,
    CameraX analyzer lifecycle, KPM/reacquisition HUD, GL overlay, target restore, and copyable
    diagnostics.
*   `SphereSlamStandaloneTrackingAnalyzer.kt`: synchronous display-oriented CameraX luma →
    calibrated KPM pose pipeline with quality gates, observation-age checks, pose-jump rejection,
    tracking-state hysteresis, and the short rotation-only IMU bridge (SphereSLAM
    `GameRotationAttitudeSource` + `AttitudeRotationBridge`, body→camera
    `DeviceCameraRotation.cameraFromDevice(SENSOR_ORIENTATION, rotationDegrees)`). Emits SphereSLAM
    `PhotosphereKeyframe`s; corroborates MobileGS reloc with `:reloc` `PoseBlend`.
*   `StandaloneWallHitTest.kt`: ARCore-independent screen-pixel → canonical wall intersection.
*   `StandalonePhotosphereCapture.kt`: names why a standalone fingerprint cannot be bound to the
    base photosphere (no rotation sensor / attitude not ready / no keyframe yet) and the
    artist-facing message. The capture view and placement are SphereSLAM's
    (`PhotosphereKeyframeStore.view`, `PhotosphereFingerprintFrame.placement`, roll-aware).
    Back-projects calibrated CameraX rays onto SphereSLAM z=0 and accepts only visually locked,
    registered page/atlas coverage; no synthetic depth or ARCore `Frame.hitTest`.
*   `StandaloneAtlasGrowth.kt`: chooses/rectifies additional KPM wall pages and registers each into
    page 0's immutable canonical frame.
*   `StandaloneFailure.kt` / `StandaloneDiagnostics.kt`: typed standalone failure taxonomy and the
    compact diagnostic dump contract.
*   `rendering/HomographyOverlayRenderer.kt`: transparent CameraX GL overlay renderer shared by
    standalone SphereSLAM AR and the legacy homography Overlay tracker. In standalone it embeds
    SphereSLAM `:overlay` `CoverageGlowRenderer` (complement wash, holes =
    `PhotosphereMap.currentRegions()`) before the design.
*   `rendering/BackgroundRenderer.kt`: OpenGL ES shader that renders ARCore's `EXTERNAL_OES`
    camera texture full-screen.
*   `CameraPreview.kt`: shared CameraX preview for Overlay and standalone SphereSLAM AR.
*   `OverlayGyroStabilizer.kt`: Overlay ▸ Gyro (tripod stabilisation) Compose wiring.
    `rememberOverlayGyroCompensation` captures a game-rotation reference (SphereSLAM
    `GameRotationAttitudeSource`), publishes the per-frame pure-rotation screen homography
    (SphereSLAM `gyro.GyroCompensationMath`, `camera.ScreenIntrinsics`) MainScreen draws the Overlay
    design through, auto-releases past 3°, and runs a slow (15 s) background MiDaS sample (`:models`
    `DepthEstimator`) of the surface under the design.
*   `HomographyFallbackOverlay.kt` / `HomographyArTracker.kt` / `BridgedHomographyTracker.kt`: legacy
    planar live tracking with a short SphereSLAM attitude bridge, currently unmounted (Overlay draws
    the edited design over the camera untracked); not the standalone AR backend.
*   `computervision/DualAnalyzer.kt`: ARCore-side `ImageAnalysis.Analyzer` for relocalization
    callbacks and light estimation.
*   `src/test/.../ArViewModelTest.kt` plus standalone/hybrid tests: lifecycle/persistence,
    tracking-state/failure, the gyro-bridge wiring, and PoseFusion world-rebase/agreement behavior
    (including an `Observation.forTesting` → `HybridKpmCorrection` → `PoseFusion` wiring test). The
    moved math (metric page geometry, page↔ARCore frame invariance, timestamp pairing, correction
    gates, camera geometry, rotation math) is tested in SphereSLAM.

### `:feature:editor`
*   `EditorViewModel.kt`: Placement and legibility for the single design image (there is no
    multi-layer stack — see `FEATURE_REFERENCE.md`), undo/redo, project save/load. Does not own
    native SLAM state — `:feature:ar`'s `ArViewModel` restores a project's wall fingerprint.
*   `src/test/.../EditorViewModelTest.kt`: Unit tests for layer ops and bitmap dimensions.

### `:feature:dashboard`
*   `DashboardViewModel.kt`: Project library, settings navigation, new/open/delete project.
*   `ProjectLibraryScreen.kt`: Full-screen project list UI.

---
*Documentation updated on 2026-10-10: generic SLAM code moved into SphereSLAM 0.24.0 (see the
SphereSLAM section). Earlier 2026-10-01 update: added the dual ARCore/SphereSLAM backend ownership,
standalone CameraX/KPM files, co-op frame calibration, and metric hybrid KPM→PoseFusion files. Earlier 2026-09-04 update removed the Persistent Voxel Memory /
`slamManager.draw()` /
`VoxelHash.*` / `StereoProcessor.cpp` claims (none of those files or methods exist), corrected the
`:core:nativebridge` and `:feature:ar` sections against the current native/Kotlin source. Prior
update: 2026-03-17, website redesign and Stencil generation integration phase.*