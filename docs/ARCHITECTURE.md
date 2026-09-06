// FILE: docs/ARCHITECTURE.md
# GraffitiXR Architecture

## High-Level Overview

GraffitiXR follows a multi-module Clean Architecture pattern, optimized for high-performance native
relocalization and local-first data persistence. There is no persistent voxel or splat map, and no
in-app 3D-map renderer — see [`NATIVE_ENGINE.md`](NATIVE_ENGINE.md) for what the native engine
actually does.

~~~mermaid
graph TD
    App[":app"] --> FeatureAR[":feature:ar"]
    App --> FeatureEditor[":feature:editor"]
    App --> FeatureDash[":feature:dashboard"]
    App --> CoreNative[":core:nativebridge"]
    App --> CoreCollab[":android_collaboration_module"]

    FeatureAR --> CoreNative
    FeatureAR --> CoreCollab
    FeatureAR --> CoreDomain[":core:domain"]
    FeatureAR --> CoreData[":core:data"]
    FeatureAR --> CoreDesign[":core:design"]
    FeatureEditor --> CoreDomain
    FeatureEditor --> CoreData
    FeatureEditor --> CoreDesign
    FeatureDash --> CoreDomain
    FeatureDash --> CoreData
    FeatureDash --> CoreDesign

    CoreDomain --> CoreCommon[":core:common"]
    CoreData --> CoreDomain
    CoreData --> CoreCommon
    CoreDesign --> CoreCommon
    CoreNative --> OpenCV[OpenCV, Maven Central]
    FeatureAR --> CoreCommon
    FeatureEditor --> CoreCommon
    FeatureDash --> CoreCommon
~~~

**Feature modules must not depend on other feature modules.** `:core:common` is the shared model/
domain-object module every other module depends on directly or transitively — `:core:domain` is a
thin two-file repository-interface layer on top of it (`ProjectRepository`, `SettingsRepository`),
not where the domain models themselves live.

`:feature:editor` does **not** depend on `:core:nativebridge` — it owns image placement and
legibility only, and has no reason to touch the native SLAM engine. `:feature:ar` is the sole owner
of the native `SlamManager` singleton, including restoring a saved project's wall fingerprint on
load (`ArViewModel.loadFingerprintIfExists`).

## Module Definitions

### `:feature:ar`
ARCore session lifecycle (`ArViewModel`), camera frame acquisition and rendering (`ArRenderer`), and
feeding frames to the native relocalization engine. `ArRenderer` renders the camera background and
composites the AR overlay; it does **not** render any persistent 3D map — there isn't one.

### `:feature:editor`
Placement and legibility tools for the one design image being traced: transform (pan/scale/rotate),
lock, opacity/brightness/contrast/saturation/colour-balance/invert, plus Outline and subject
isolation. Authoring (multi-layer compositing, painting, stencil generation, warp/Liquify) does not
live here — see [`FEATURE_REFERENCE.md`](FEATURE_REFERENCE.md) for what was removed and why.

### `:core:nativebridge`
C++17 `MobileGS` engine and JNI boundary (`GraffitiJNI.cpp`). Handles fingerprint-based
relocalization (ORB/SuperPoint descriptors, Lowe-ratio matching, `solvePnPRansac`), the
distortion-head model for painting-progress/confidence (`docs/DISTORTION_HEAD.md`), and — opt-in,
off by default — drift correction and self-growing fingerprint (`docs/TELEOLOGICAL_SLAM.md`). OpenCV
is a Maven Central dependency (`org.opencv:opencv`), not a vendored/embedded copy.

## Data Flow (AR Pipeline)

Each ARCore tracking frame, roughly:

~~~
camera.trackingState ────────────────────────► setArCoreTrackingState(isTracking)
camera.getViewMatrix/ProjectionMatrix ───────► slamManager.updateCamera(view, proj, timestampNs)
frame.acquireCameraImage() [YUV] ────────────► slamManager.feedYuvFrame(...) (relocalization thread)
                                              │
                                    MobileGS::runRelocPass() (background thread)
                                              │  ├─ ORB/SuperPoint match against the wall fingerprint
                                              │  ├─ solvePnPRansac → camera_from_fpWorld
                                              │  └─ distortion-head crop → painting progress / confidence
                                              │
                                     PoseFusion.currentAnchor() (Kotlin, feature:ar)
                                              │  blends the ARCore-consensus pose with the reloc pose
                                              ▼
                                   ArRenderer draws camera background + AR overlay
~~~

**Camera ownership:**
- `EditorMode.AR` → ARCore `Session` owns the camera.
- `EditorMode.OVERLAY` → CameraX owns the camera (ARCore-available devices still use the ARCore
  session; devices without ARCore fall back to a planar homography tracker over the same OpenCV
  pipeline — see `docs/UI_UX.md`).

## Relocalization and Drift Correction

The engine uses a dedicated background thread (`relocThreadFunc`) to continuously match the current
camera frame against the stored wall fingerprint. On a high-confidence PnP match it can correct
global drift and "snap" the overlay back into place — see
[`TELEOLOGICAL_SLAM.md`](TELEOLOGICAL_SLAM.md) for the actual gating and defaults; the mechanism
runs unconditionally, but its downstream drift-correction consumer is a diagnostic-only, off-by-
default toggle today.

---
*Documentation updated on 2026-09-04: removed the fictional Persistent Voxel Memory /
`slamManager.draw()` architecture (deleted from the codebase; never actually built per
`docs/NATIVE_ENGINE.md`), removed GPU-accelerated Liquify (no implementing code), corrected the
module dependency graph and the AR data-flow diagram against current source. Prior update:
2026-06-22, SLAM right-size and documentation-accuracy pass.*
