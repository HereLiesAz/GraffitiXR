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
| `:app` | `com.hereliesaz.graffitixr` | `MainActivity`, Hilt DI wiring, Navigation Graph |
| `:feature:ar` | `...feature.ar` | ARCore session, camera frames, sensor fusion, AR rendering |
| `:feature:editor` | `...feature.editor` | Image manipulation, layer management, Mockup/Trace/Overlay screens |
| `:feature:dashboard` | `...feature.dashboard` | Project library, Settings, Onboarding |
| `:core:nativebridge` | `...nativebridge` | JNI bridge to C++ MobileGS engine, `SlamManager.kt`, `OpenCVHelper.kt` |
| `:core:domain` | `...domain` | Pure data models, repository interfaces |
| `:core:data` | `...data` | Repository implementations, file I/O, `.gxr` project serialization |
| `:core:design` | `...design` | Theme, typography, shared Compose UI components |
| `:core:common` | `...common` | Utilities, Kotlin extensions, math helpers |

### Native Engine (MobileGS)

The C++ engine lives in `core/nativebridge/src/main/cpp/`. Key files:
- `MobileGS.cpp` / `MobileGS.h` — Sparse voxel hash map (5mm³ voxels), Gaussian splat rendering via `GL_POINTS`
- `GraffitiJNI.cpp` — JNI boundary; exposes native pointer as `long` handle to Kotlin
- `StereoProcessor.cpp` — Dual-camera stereo depth fusion

**JNI Protocol:** Always pass memory as `ByteBuffer` (never raw pointers). C++ extracts the address via `GetDirectBufferAddress`.

Key tuning constants in `MobileGS.h`: `VOXEL_SIZE` (20mm), `CONFIDENCE_THRESHOLD` (0.6), `MAX_SPLATS` (500k), `CULL_DISTANCE` (5m).

### Data Flow (Teleological Loop)

1. ARCore → 6DOF pose + 16-bit depth buffer
2. `SlamManager` feeds depth to `MobileGS::processDepthFrame()` for voxel unprojection
3. OpenCV fingerprinting matches current frame to stored fingerprint → teleological alignment correction
4. `MobileGS::draw()` renders the voxel map via OpenGL ES 3.0

### State Management

- ViewModels use `StateFlow` / `MutableStateFlow` (not `LiveData`)
- `MainViewModel` (scoped to `MainActivity`) manages cross-cutting state: touch lock, `CaptureStep` wizard for target creation
- Hilt is used for all DI; `@HiltViewModel` + `@Inject constructor`

### Project File Format

- `.gxr` — JSON manifest serialized via `kotlinx.serialization`
- `.bin` — Binary SLAM map: `"GXRM"` magic header + splat count + keyframe count + splat payload (32 bytes/splat: `x,y,z,r,g,b,a,confidence`) + alignment matrix

## Conventions

- **Packages:** Flat packages matching module names — e.g., `:core:nativebridge` → `com.hereliesaz.graffitixr.nativebridge`
- **UI:** Jetpack Compose only (no XML layouts); AzNavRail (`com.github.HereLiesAZ:AzNavRail`) for navigation — a vertical thumb-driven rail, right-side by default
- **Dependencies:** All versions managed in `gradle/libs.versions.toml` (version catalog); never hardcode versions in module `build.gradle.kts`
- **Protobuf dual-version:** Root `build.gradle.kts` intentionally forces Protobuf `3.25.5` in the buildscript classpath (AGP 9.0.1 requirement) and `4.28.2` in the app runtime. Do not align these — the split is required.
- **Versioning:** Update `version.properties` for Major/Minor; build number auto-increments from git commit count

## Testing Notes

- Unit tests: `src/test/` in each module; run per-module with `:module:testDebugUnitTest`
- C++ has no automated test runner — use `DEBUG_COLORS` flag in `MobileGS.h` for visual verification
- ARCore cannot be mocked in UI tests — mock `ArRenderer` instead
- Compose UI tests: `src/androidTest/`; focus on AzNavRail interactions
