# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
# One-time setup: download OpenCV and GLM native dependencies
./setup_libs.sh

# Build debug APK
./gradlew assembleDebug

# Run all Kotlin unit tests
./gradlew testDebugUnitTest

# Run tests for a single module
./gradlew :feature:editor:testDebugUnitTest

# Lint
./gradlew lintDebug
```

**Prerequisites:** NDK 25.x+, `local.properties` pointing to Android SDK, `app/google-services.json` (use `app/google-services.json.template` with dummy values for local builds).

## Architecture

GraffitiXR is a **multi-module Android app** (Kotlin + C++17) for AR-assisted mural projection. Modules follow strict dependency rules.

### Module Dependency Graph

```
:app  →  :feature:ar, :feature:editor, :feature:dashboard
:feature:ar       →  :core:nativebridge, :core:design, :core:domain
:feature:editor   →  :core:design, :core:domain, :opencv
:feature:dashboard→  :core:design, :core:data
:core:nativebridge→  :core:domain, :opencv
:core:data        →  :core:domain, :core:common
:core:design      →  :core:common
:core:domain      →  :core:common
```

**Feature modules must not depend on other feature modules.** Cross-feature communication goes through `:app` or `:core` interfaces.

### Module Responsibilities

| Module | Namespace | Responsibility |
|---|---|---|
| `:app` | `com.hereliesaz.graffitixr` | `MainActivity`, Hilt DI wiring, Navigation Graph, `ArViewport` composable |
| `:feature:ar` | `...feature.ar` | ARCore session lifecycle, `ArViewModel`, `ArRenderer`, `BackgroundRenderer`, sensor fusion |
| `:feature:editor` | `...feature.editor` | Image manipulation, layer management, `GsViewer` (Vulkan), Mockup/Trace/Overlay screens |
| `:feature:dashboard` | `...feature.dashboard` | Project library, Settings, Onboarding |
| `:core:nativebridge` | `...nativebridge` | JNI bridge to C++ MobileGS engine, `SlamManager.kt` |
| `:core:domain` | `...domain` | Pure data models, repository interfaces |
| `:core:data` | `...data` | Repository implementations, file I/O, `.gxr` project serialization |
| `:core:design` | `...design` | Theme, typography, shared Compose UI components |
| `:core:common` | `...common` | Utilities, Kotlin extensions, math helpers |

### Native Engine (MobileGS)

The C++ engine lives in `core/nativebridge/src/main/cpp/`. Key files:
- `MobileGS.cpp` / `MobileGS.h` — Sparse voxel hash map (20mm voxels), Gaussian splat rendering via `GL_POINTS`
- `GraffitiJNI.cpp` — JNI boundary; all native functions go here
- `StereoProcessor.cpp` — Dual-camera stereo depth fusion

**JNI Protocol:** Always pass memory as `ByteBuffer` (never raw pointers). C++ extracts the address via `GetDirectBufferAddress`.

Key tuning constants in `MobileGS.h`: `VOXEL_SIZE` (20mm), `CONFIDENCE_THRESHOLD` (0.6), `MAX_SPLATS` (500k), `CULL_DISTANCE` (5m).

### Data Flow (AR Pipeline)

Each ARCore tracking frame in `ArRenderer.onDrawFrame`:

1. **Pose** — `camera.getViewMatrix/getProjectionMatrix` → `slamManager.updateCamera()`
2. **Motion calibration** — `camera.imageIntrinsics.focalLength[0]` + inter-frame translation from `camera.pose.translation` → `slamManager.setCameraMotion(focalLengthPx, translationM)` — sets native `kScale = focalLengthPx × translationM` for optical-flow depth
3. **Monocular feed** — `frame.acquireCameraImage()` Y-plane → `slamManager.feedMonocularData()` → Lucas-Kanade optical flow → sparse depth map → `MobileGS::processDepthFrame()`
4. **Metric depth** (Depth API devices) — `frame.acquireDepthImage16Bits()` → `slamManager.feedArCoreDepth()` → DEPTH16 decode → `MobileGS::processDepthFrame()` (overrides optical-flow estimates)
5. **SLAM rendering** — `GsViewer` (Vulkan, `SurfaceView`) renders the voxel map; `ArRenderer` (`GLSurfaceView`) renders the live camera background via `BackgroundRenderer`
6. **Teleological correction** — OpenCV fingerprinting matches current frame to stored fingerprint → `slamManager.updateAnchorTransform()`

**Camera ownership by mode:**
- `EditorMode.AR` → ARCore `Session` owns the camera; CameraX `Preview` is **not** active
- `EditorMode.OVERLAY` → CameraX `Preview` owns the camera; ARCore `Session` is paused
- Session lifecycle: `DisposableEffect` in `ArViewport` (mode-level) + `MainActivity.onResume/onPause` (activity-level)

### State Management

- ViewModels use `StateFlow` / `MutableStateFlow` (not `LiveData`)
- `MainViewModel` (scoped to `MainActivity`) manages cross-cutting state: touch lock, `CaptureStep` wizard for target creation
- `ArViewModel` manages ARCore session (`initArSession`, `resumeArSession`, `pauseArSession`), GPS, flashlight, and tracking state
- Hilt is used for all DI; `@HiltViewModel` + `@Inject constructor`
- `ArViewModel` is obtained via `by viewModels()` at `MainActivity` level (not `hiltViewModel()`) so `onResume`/`onPause` can call session lifecycle methods

### Project File Format

- `.gxr` — JSON manifest serialized via `kotlinx.serialization`
- `.bin` — Binary SLAM map: `"GXRM"` magic header + splat count + keyframe count + splat payload (32 bytes/splat: `x,y,z,r,g,b,a,confidence`) + alignment matrix

## Conventions

- **Packages:** Flat packages matching module names — e.g., `:core:nativebridge` → `com.hereliesaz.graffitixr.nativebridge`
- **UI:** Jetpack Compose only (no XML layouts); AzNavRail (`com.github.HereLiesAZ:AzNavRail`) for navigation — a vertical thumb-driven rail, right-side by default
- **Dependencies:** All versions managed in `gradle/libs.versions.toml` (version catalog); never hardcode versions in module `build.gradle.kts`
- **Protobuf dual-version:** Root `build.gradle.kts` intentionally forces Protobuf `3.25.5` in the buildscript classpath (AGP 9.0.1 requirement) and `4.28.2` in the app runtime. Do not align these — the split is required.
- **Versioning:** Update `version.properties` for Major/Minor; build number auto-increments from git commit count
- **ARCore types in `:app`:** The `Session` class must not appear in `:app` module code — use `ArViewModel.attachSessionToRenderer(renderer)` to assign sessions without leaking the ARCore dependency

## Testing Notes

- Unit tests: `src/test/` in each module; run per-module with `:module:testDebugUnitTest`
- Existing unit test files: `TeleologicalTrackerTest` (`:feature:ar`), `DualAnalyzerTest` (`:feature:ar`), `ProjectManagerTest` (`:core:data`), `EditorViewModelTest` (`:feature:editor`), `ArViewModelTest` (`:feature:ar`)
- Mock patterns:
  - `android.util.Log` → `mockkStatic(Log::class)` + `every { Log.e(any(), any()) } returns 0`
  - `ImageProcessingUtils` → `mockkObject(ImageProcessingUtils)`
  - `BitmapUtils` → `mockkObject(BitmapUtils)` + `coEvery { getBitmapDimensions(any()) } returns Pair(100,100)`
  - ARCore `Session` cannot be instantiated on JVM — ARCore session tests belong in instrumented (`androidTest`) tests
  - `Mat()` constructor calls native code — avoid constructing `Mat` in JVM unit tests; use `mockk<Mat>(relaxed = true)`
- C++ has no automated test runner — use `DEBUG_COLORS` flag in `MobileGS.h` for visual verification
- ARCore cannot be mocked in UI tests — mock `ArRenderer` instead
- Compose UI tests: `src/androidTest/`; focus on AzNavRail interactions
