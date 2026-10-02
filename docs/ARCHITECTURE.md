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

- **ARCore-capable devices:** run beside `ArCorePoseSource` as a lower-rate metric wall
  relocalization source. Accepted KPM observations can correct the artwork anchor only through
  `HybridKpmCorrection → PoseFusion`; ARCore remains the continuous camera pose.
- **ARCore-unavailable devices:** provide the standalone wall-tracking backend without an ARCore
  `Session`. CameraX + synchronous KPM supply wall-relative 6-DoF while a page is visible, a short
  rotation-only IMU bridge covers brief misses, MobileGS uses the same centered page frame, and a
  bounded multi-page atlas extends coverage without changing the canonical wall frame.

Both roles are implemented. Standalone still has explicit capability differences from ARCore
(depth/planes/cloud-anchor/perception APIs), and long visual loss still requires reacquisition rather
than pretending to have free-space inertial translation. See
[`SPHERESLAM_ARCORE_SIDECAR.md`](SPHERESLAM_ARCORE_SIDECAR.md).

## Data Flow (AR Pipeline)

Each ARCore tracking frame, roughly:

~~~
camera.trackingState ─────────────────────────► setTrackingPoseValid(isTracking)
ArCorePoseSource.sample(view, proj, timestamp) ─► PRIMARY renderer camera pose
camera.pose.inverse() + consensus backbone ───► HybridPoseHistory[timestamp]
                                                │
frame.acquireCameraImage() [raw YUV] ──────────┼► MobileGS.feedYuvFrame(...)
                                                │      └─ ORB/SuperPoint + solvePnPRansac
                                                │
                                                └► SphereSlamTracker (~10 Hz, worker)
                                                       └─ metric KPM page observation
                                                                  │
                                                                  ▼
                                                     HybridKpmCorrection
                                             page frame + timestamp + quality gates
                                                                  │
                                  accepted ────────────────────────┘
                                     ▼
                      PoseFusion.currentAnchorFromHybridObservation()
                                     │
                         else MobileGS PoseFusion fallback
                                     │
                         else hold standing local correction
                                     ▼
                      artwork anchorMatrix in ARCore world
                                     │
                     ArRenderer draws ARCore camera + overlay
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

There are now two explicit correction sources behind the same off-by-default drift-correction
switch:

1. **Metric hybrid KPM** — a target capture with a real ARCore wall plane is rectified into a
   physically-scaled KPM page. Each worker observation is paired with the nearest raw sensor-camera
   ARCore view + unfused consensus backbone from the same timestamp (40 ms maximum mismatch), then
   gated for metric frame, age (≤500 ms), inliers (≥12), and reprojection error (≤4 px).
   `HybridKpmCorrection` converts it into a corrected artwork anchor at the observation timestamp;
   `PoseFusion` stores only the resulting anchor-local correction.
2. **MobileGS** — the existing fingerprint background thread (`relocThreadFunc`) continuously
   matches ORB/SuperPoint descriptors and solves PnP. Its existing `captureAnchorCam` correction
   path remains the fallback when no fresh accepted KPM observation exists.

If neither source supplies a fresh correction, the standing anchor-local correction is carried by
the live ARCore consensus backbone; with no standing correction the raw consensus is used. KPM is
never allowed to replace ARCore's camera view/projection or continue as primary tracking while
ARCore is paused.

See [`SPHERESLAM_ARCORE_SIDECAR.md`](SPHERESLAM_ARCORE_SIDECAR.md) for KPM frame/scale contracts
and [`TELEOLOGICAL_SLAM.md`](TELEOLOGICAL_SLAM.md) for MobileGS corroboration/self-grow behavior.

**Return-visit anchoring.** When a project with a saved capture pose (`captureAnchorCam`) is
reopened and no anchor exists yet, the renderer turns two consecutive agreeing high-confidence
reloc solves (`PoseFusion.COLD_SNAP_*` gates, `PoseFusion.diverged` agreement) into the primary
ARCore anchor (`ArRenderer.pendingRelocAnchor`). This runs regardless of the drift-correction
toggle and without re-capturing, so the saved target is preserved. Pre-Phase-2 fingerprints
(no capture pose) cannot use it. Not yet validated on device.

---
*Documentation updated on 2026-10-01: documented standalone atlas/MobileGS/co-op frame ownership and
the metric hybrid KPM→PoseFusion correction seam, including timestamp pairing and ARCore-primary
camera ownership. Earlier 2026-09-04 update removed the fictional Persistent Voxel Memory /
`slamManager.draw()` architecture and corrected the module graph against source.*


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
