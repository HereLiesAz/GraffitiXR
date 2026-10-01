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
    FeatureAR --> SphereSLAM[":sphereslam"]
    SphereSLAM --> CoreNative
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

### `:sphereslam`
Native tracking/relocalization module built around the pinned artoolkitX integration. It has two
architectural roles:

- **ARCore-capable devices:** run beside `ArCorePoseSource` for wall recognition/relocalization and
  later explicit pose correction.
- **ARCore-unavailable devices:** provide the standalone wall-tracking backend without an ARCore
  `Session`. The current implementation is page-relative KPM + a short rotation-only IMU bridge;
  physical scale, MobileGS frame integration, and wider wall-map continuity remain tracked work.

The code now implements both the calibrated hybrid KPM sidecar and an initial standalone
wall-target path. On non-ARCore devices CameraX supplies display-oriented luminance + intrinsics,
KPM supplies the wall-relative 6-DoF pose while the target is visible, and the fused game-rotation
sensor bridges very short visual dropouts without inventing translation. Standalone parity with the
full ARCore feature set is still in progress. See
[`SPHERESLAM_ARCORE_SIDECAR.md`](SPHERESLAM_ARCORE_SIDECAR.md).

## Data Flow (AR Pipeline)

Each ARCore tracking frame, roughly:

~~~
camera.trackingState ────────────────────────► setTrackingPoseValid(isTracking)
camera.getViewMatrix/ProjectionMatrix ───────► slamManager.updateCamera(view, proj, timestampNs)
                                              │
                                              ├────────────────────────► primary ARCore renderer pose
                                              │
frame.acquireCameraImage() [YUV] ────────────┼► slamManager.feedYuvFrame(...) (relocalization thread)
                                              │         │
                                              │   MobileGS::runRelocPass() (background thread)
                                              │         ├─ ORB/SuperPoint match
                                              │         ├─ solvePnPRansac
                                              │         └─ distortion-head crop
                                              │
                                              └► SphereSLAM/KPM sidecar (~10 Hz when seeded)
                                                        ├─ calibrated planar keypoint match
                                                        └─ page-relative observation only
                                                             (not fused into primary pose yet)
                                              │
                                     PoseFusion.currentAnchor() (Kotlin, feature:ar)
                                              │  existing ARCore + MobileGS fusion
                                              ▼
                                   ArRenderer draws camera background + AR overlay
~~~

**MobileGS pose-validity seam:** `setTrackingPoseValid` means only that the most recent
`updateCamera` pose belongs to the current camera frame. ARCore drives it from
`camera.trackingState`; standalone drives it from same-frame accepted KPM observations. It is not a
backend identity flag. The plane-guided capture-view rectifier additionally requires a stored
fingerprint capture view, so it remains intentionally unavailable to centered-page standalone
fingerprints.

**Tracking backend selection:** on ARCore-capable devices, `ArCorePoseSource` supplies the live
renderer pose while SphereSLAM runs asynchronously beside it. On ARCore-unavailable devices,
`MainScreen` keeps AR mode reachable and switches camera ownership to CameraX; the standalone
SphereSLAM analyzer converts each successful KPM wall match directly into the OpenGL view matrix
used by the transparent overlay renderer. A short fused-gyro bridge covers brief visual misses.
The detailed contract, calibration assumptions, threading model, and remaining parity work are
documented in
[`SPHERESLAM_ARCORE_SIDECAR.md`](SPHERESLAM_ARCORE_SIDECAR.md).

**Camera ownership:**
- `EditorMode.AR`, hybrid mode → ARCore `Session` owns the camera and SphereSLAM consumes frames
  acquired from that session.
- `EditorMode.AR`, standalone mode → CameraX owns the camera; calibrated KPM supplies the
  wall-relative pose while the page is visible and a short fused-gyro bridge covers brief visual
  misses. The remaining parity work is tracked in `docs/SPHERESLAM_TODO.md`.
- `EditorMode.OVERLAY` → CameraX owns the camera (devices without ARCore can continue using the
  planar homography/OpenCV overlay path described in `docs/UI_UX.md`).

**Standalone AR capability boundary:** the centered KPM page/atlas is the wall anchor. Core placement
does not construct an ARCore `Anchor` or depend on `Frame.hitTest`; when a screen-space wall query
is needed, `StandaloneWallHitTest` intersects the calibrated CameraX ray with canonical z=0 and
accepts only registered page coverage while visually locked. ARCore Depth/stereo probes and
planes/point-cloud debug renderers stay behind the ARCore branch. Standalone exposes KPM
page/inlier/error/age diagnostics instead. ARCore and standalone persist separate AR adjustments:
`modeAdjustments[AR]` remains ARCore's coordinate state, while `sphereSlamModeAdjustment` is
generation-bound to the canonical KPM wall. Recapture therefore cannot reuse standalone transforms
from the superseded wall or erase a still-valid ARCore placement.

## Relocalization and Drift Correction

The engine uses a dedicated background thread (`relocThreadFunc`) to continuously match the current
camera frame against the stored wall fingerprint. On a high-confidence PnP match it can correct
global drift and "snap" the overlay back into place — see
[`TELEOLOGICAL_SLAM.md`](TELEOLOGICAL_SLAM.md) for the actual gating and defaults; the mechanism
runs unconditionally, but its downstream drift-correction consumer is a diagnostic-only, off-by-
default toggle today.

**Return-visit anchoring.** When a project with a saved capture pose (`captureAnchorCam`) is
reopened and no anchor exists yet, the renderer turns two consecutive agreeing high-confidence
reloc solves (`PoseFusion.COLD_SNAP_*` gates, `PoseFusion.diverged` agreement) into the primary
ARCore anchor (`ArRenderer.pendingRelocAnchor`). This runs regardless of the drift-correction
toggle and without re-capturing, so the saved target is preserved. Pre-Phase-2 fingerprints
(no capture pose) cannot use it. Not yet validated on device.

---
*Documentation updated on 2026-09-04: removed the fictional Persistent Voxel Memory /
`slamManager.draw()` architecture (deleted from the codebase; never actually built per
`docs/NATIVE_ENGINE.md`), removed GPU-accelerated Liquify (no implementing code), corrected the
module dependency graph and the AR data-flow diagram against current source. Prior update:
2026-06-22, SLAM right-size and documentation-accuracy pass.*


## Cross-backend co-op wall frame

Co-op protocol v3 shares one **host wall-local coordinate object**, not either device's transient
ARCore/SphereSLAM world origin. The wire descriptor is
`core/common/.../CoopSpatialFrame.kt`:

- `hostBackend` says whether the host wall came from ARCore or standalone SphereSLAM.
- `scale` says whether translations are metric metres or normalized page units.
- `fingerprintFromWall` is the durable transform from host wall-local coordinates into the
  MobileGS fingerprint object frame.
- `anchorRevision` identifies the exact host target. A recapture is a new coordinate object and
  ends the running co-op session; reconnect never silently rebases it.

For an ARCore host, `fingerprintFromWall` is the persisted
`Fingerprint.captureAnchorCam = V_cv(capture) * anchorModel`. For a standalone host it is identity,
because standalone fingerprint points already live directly in the centered canonical KPM wall
frame.

A peer MobileGS solve is therefore backend-neutral:

```
cameraGL_from_hostWall =
    CV_TO_GL * pnpCV_camera_from_fingerprint * fingerprintFromWall
```

That same equation drives ARCore guest anchoring and the CameraX-only
`CoopPeerFingerprintAnalyzer` used by an ARCore-host -> standalone-guest session. A standalone
host -> standalone guest normally uses the shared KPM page/atlas directly; its project ZIP already
contains page 0, atlas images, scale metadata, and canonical page transforms.

Scale compatibility fails closed. A normalized standalone page may be shared with another
standalone peer because both consume the same page-relative coordinate object. It may **not** be
injected into ARCore's metre world. Protocol v3 rejects that pairing as `SpatialIncompatible`
before bulk transfer. Protocol-v2 peers are rejected as `VersionMismatch`; retaining compatibility
would recreate the frame ambiguity v3 exists to remove.
