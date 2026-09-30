# SphereSLAM + ARCore Sidecar Integration

Status: implemented on `feat/sphereslam-parallel-arcore` as a follow-up to merged PR #1959.

## Non-negotiable architecture

SphereSLAM has **two required roles**, selected by device capability.

### Mode A — ARCore + SphereSLAM hybrid

On an ARCore-capable device, ARCore remains the primary continuous metric 6-DoF tracker. SphereSLAM
runs beside it for wall recognition, relocalization, and later explicit drift correction/fusion.
SphereSLAM must not silently overwrite ARCore view/projection matrices in this mode.

```
ARCore Session
  ├─ ArCorePoseSource ───────────────► primary renderer pose
  ├─ depth / planes / anchors ───────► existing AR placement pipeline
  └─ camera Image Y plane
         ├─ MobileGS relocalization ─► existing ORB/SuperPoint + PnP path
         └─ SphereSLAM/KPM ──────────► planar wall observation
                                          │
                                          └─ explicit fusion/correction stage
```

### Mode B — SphereSLAM standalone

On a phone where ARCore is unsupported or unavailable, **AR mode must remain usable**. SphereSLAM is
the required standalone tracking backend and must provide the capabilities the app needs without
constructing an ARCore `Session`.

The standalone path must eventually own or receive:

- raw camera frames through a non-ARCore camera path such as CameraX;
- calibrated camera intrinsics and display-rotation handling;
- IMU samples needed for robust visual-inertial tracking;
- continuous metric 6-DoF camera pose;
- OpenGL-compatible `viewMatrix` and `projectionMatrix` through `PoseSource`;
- wall-target capture and metric scale establishment;
- anchor/overlay placement without `com.google.ar.core.Anchor`;
- wall-map growth, persistence, restoration, and return-visit relocalization;
- loss/recovery state so the renderer can stop integrating bad poses and reacquire the wall.

Conceptually:

```
CameraX + IMU
      │
      ▼
SphereSLAM continuous tracking ──────► SphereSlamPoseSource ─────► primary renderer pose
      │
      ├─ KPM wall atlas / relocalization
      ├─ MobileGS fingerprint/relocalization where useful
      └─ standalone wall/anchor model ────────────────────────────► overlay placement
```

ARCore is therefore **preferred when present, but never a hard requirement for the app's AR
purpose**.

### Current implementation status

This branch implements the Mode A KPM sidecar foundation only. It does **not** yet implement the
complete Mode B continuous tracker, CameraX/IMU camera pipeline, or ARCore-independent anchor model.
Until those pieces exist, the current UI still disables AR mode when ARCore is unavailable. That is
a temporary implementation limitation, not the intended product behavior.

The app manifest already marks ARCore optional, so installability on non-ARCore devices is preserved.

## What existed before this follow-up

Merged PR #1959 added the first real SphereSLAM library layer:

- `:sphereslam` Android library module.
- `SphereSlam` public entry point.
- `SphereSlamEngine` API.
- `KpmSphereSlamEngine` implementation.
- calibrated KPM session creation using `fx/fy/cx/cy`;
- planar reference-page registration;
- live luma matching;
- `PlanarMatch` output containing:
  - page number;
  - artoolkitX camera-from-page 3x4 transform;
  - reprojection error;
  - inlier count;
- native JNI bridge in `:core:nativebridge`;
- artoolkitX KPM/FREAK native build wiring;
- safe unavailable behavior when the submodule is absent.

That work also corrected an important artoolkitX trap: the pinned 1.1.23 binary/FREAK path performs
a calibrated pose solve during matching. Runtime sessions therefore use `kpmCreateHandle(ARParamLT*)`
with camera calibration. The homography-only handle is kept only for a link smoke test.

## What this follow-up adds

### 1. Asynchronous runtime adapter

File:

`sphereslam/src/main/java/com/hereliesaz/sphereslam/SphereSlamTracker.kt`

`SphereSlamTracker` is a renderer-facing asynchronous adapter around the calibrated KPM bridge.

Responsibilities:

- own one KPM worker thread;
- keep KPM work off the ARCore GL/render thread;
- pack Android Y-plane luminance rows into contiguous buffers;
- create/reset calibrated KPM sessions;
- seed a planar reference image;
- accept live camera frames;
- keep at most one unprocessed frame;
- drop stale queued frames instead of accumulating latency;
- publish the most recent `Observation`;
- cleanly destroy the native session during renderer teardown.

The adapter's `Observation` contains:

- `timestampNs`;
- matched `pageNo`;
- KPM reprojection `error`;
- KPM `inliers`;
- `pageToCamera3x4`.

The adapter does **not** expose a replacement ARCore pose in the current hybrid implementation. A future standalone `SphereSlamPoseSource` will be a separate continuous-pose component rather than treating a single KPM page match as frame-to-frame SLAM.

### 2. AR renderer side-by-side wiring

File:

`feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/rendering/ArRenderer.kt`

A private `SphereSlamTracker` is created beside the existing `ArCorePoseSource`.

The existing ARCore path is unchanged:

```
poseSource.bind(frame)
poseSource.sample(viewMatrix, projMatrix, ...)
```

Those matrices still drive the renderer and `SlamManager.updateCamera(...)`.

#### Reference seeding

When GraffitiXR performs its existing target capture, the same ARCore camera image is also used to
seed a fresh SphereSLAM planar atlas.

The tracker receives:

- the captured Y plane;
- image width and height;
- ARCore image intrinsics:
  - `fx`;
  - `fy`;
  - `cx`;
  - `cy`.

The atlas is reset on a new target capture so pages from a previous wall do not contaminate the new
wall reference.

#### Live matching

The existing YUV feed to MobileGS remains in place.

At the same camera-image acquisition point, SphereSLAM receives the Y plane only when:

- a reference page is ready; and
- `frameCount % SPHERESLAM_FEED_DIVISOR == 0`.

Current divisor: `6`.

At a typical 60 fps render cadence this is approximately 10 Hz.

That rate is intentional for the **hybrid** path implemented here: KPM is acting as a visual
relocalization sidecar while ARCore remains full-rate. Standalone SphereSLAM cannot use this
throttled KPM loop as its only pose source; it requires a continuous tracker fed at camera/IMU rate.

### 3. Native build hardening

File:

`core/nativebridge/src/main/cpp/CMakeLists.txt`

The KPM native target now uses an explicit source list matching upstream artoolkitX's AR target
instead of recursively globbing every `.c` file under `AR/`.

Reason:

artoolkitX contains many platform/configuration-specific translation units. A recursive glob can
silently compile source files that upstream does not compile together. Matching upstream's target
list makes the embedded build deterministic and substantially easier to review.

The build also retains the existing pinned KPM/FREAK source list, generated `config.h`, bundled
Android JPEG headers/library, and the `HAVE_ARX_KPM` feature guard.

## Threading model

### GL / ARCore thread

Still owns:

- `Session.update()`;
- `Frame`;
- ARCore camera pose;
- camera-image acquisition;
- existing renderer state.

The GL thread copies a luma frame when it chooses to submit to SphereSLAM. It does not wait for KPM
matching.

### SphereSLAM worker

Owns:

- native KPM session creation/destruction;
- reference-page registration;
- KPM matching;
- latest sidecar observation.

Only one pending live frame is retained. A newer frame replaces an older unprocessed frame.

This avoids the failure mode where a slower visual matcher falls farther and farther behind the live
camera.

## Calibration and coordinate conventions

SphereSLAM's calibrated KPM session uses the ARCore image intrinsics for the same pixel geometry as
the luma frames supplied to it.

Native camera parameters are built with:

- `mat[0][0] = fx`
- `mat[1][1] = fy`
- `mat[0][2] = cx`
- `mat[1][2] = cy`

The pinned artoolkitX version-5 distortion layout also receives:

- `dist_factor[12] = fx`
- `dist_factor[13] = fy`
- `dist_factor[14] = cx`
- `dist_factor[15] = cy`
- `dist_factor[16] = 1`

Lens distortion coefficients are currently zeroed.

The returned KPM 3x4 transform is **not** an ARCore/OpenGL view matrix. It is artoolkitX's
camera-from-reference-plane transform and needs an explicit coordinate-convention adapter before it
can participate in pose fusion.

## Scale

KPM reference generation derives planar coordinates from `referenceDpi`.

Therefore:

- rotation can be useful with calibrated camera geometry;
- translation direction can be useful;
- translation magnitude is physically metric only when the page's DPI reflects the wall's real
  physical pixel scale.

The runtime adapter currently defaults reference DPI to `72f`.

That means its translation scale must be treated as relative for now. It must not be substituted for
ARCore's metric translation.

The next metric step is to derive reference scale from the existing capture geometry/depth rather
than the default DPI.

## Relationship to MobileGS relocalization

SphereSLAM does not remove or bypass the existing MobileGS relocalization path.

Today both can observe the same wall camera frames:

1. MobileGS:
   - existing wall fingerprint;
   - ORB/SuperPoint matching;
   - `solvePnPRansac`;
   - existing `PoseFusion` path.

2. SphereSLAM/KPM:
   - planar KPM page atlas;
   - calibrated keypoint matching;
   - page-relative pose observation;
   - no downstream fusion yet.

This is intentional redundancy. The two systems can later be compared and fused instead of forcing
one to impersonate the other.

## Lifecycle

The `SphereSlamTracker` lifetime follows `ArRenderer`.

On renderer destruction:

- pending SphereSLAM frames are discarded;
- native KPM session destruction is scheduled on the SphereSLAM worker;
- the worker executor is shut down.

ARCore teardown remains controlled by the renderer/session locking already present in
`:feature:ar`.

## Current limitations

The current follow-up intentionally stops before pose fusion.

Not implemented yet for the hybrid path:

- converting `pageToCamera3x4` into GraffitiXR's ARCore/OpenGL/world conventions;
- metric reference-scale derivation from target capture/depth;
- confidence gates for accepting a SphereSLAM correction;
- time alignment between a KPM observation and the corresponding ARCore pose;
- a formal fusion API between SphereSLAM and `PoseFusion`;
- persistence/restoration of the KPM page atlas;
- adding additional pages during adaptive wall-map growth;
- device validation of KPM reacquisition while walking toward/away from the wall.

Not implemented yet for required standalone operation:

- a continuous SphereSLAM visual-inertial 6-DoF tracker (KPM matches alone are not sufficient);
- CameraX/raw-camera ownership when there is no ARCore `Session`;
- IMU ingestion and camera/IMU timestamp alignment;
- metric initialization and scale recovery without ARCore depth/pose;
- a `SphereSlamPoseSource` that fulfills the existing `PoseSource` matrix contract;
- an ARCore-independent anchor/wall transform model;
- ARCore-independent hit testing / wall placement;
- a camera-background renderer driven by the standalone camera stream;
- runtime backend selection that keeps AR mode enabled when SphereSLAM standalone is ready;
- end-to-end tests on an actually ARCore-unsupported device.

There is also one cleanup item: the asynchronous runtime adapter currently talks to
`KpmBridge` directly while the merged library already exposes `SphereSlamEngine`. A later cleanup
can make the adapter delegate through that public engine so JNI ownership has exactly one Kotlin
abstraction. This does not change the side-by-side architecture.

## Safety invariants for future work

Future changes should preserve all of these:

1. ARCore remains a first-class primary tracker **when it is available**.
2. ARCore remains an optional dependency at the product level; unsupported phones must be able to
   enter a fully functional SphereSLAM-backed AR mode once standalone support is complete.
3. In hybrid mode, SphereSLAM must not silently overwrite ARCore view/projection matrices. Any
   correction enters through an explicit fusion stage.
4. In standalone mode, SphereSLAM is explicitly allowed to be the primary `PoseSource`; that is not
   considered an ARCore replacement bug, it is the required fallback architecture.
5. KPM matching stays off the render thread.
6. A KPM page match is a relocalization observation, not by itself a complete continuous SLAM pose
   source.
7. KPM translation must not be called metric until reference scale is physically calibrated.
8. Coordinate conversion must be explicit and tested before any KPM transform reaches
   `PoseFusion` or a standalone pose source.
9. Loss or failure of SphereSLAM in hybrid mode must degrade to normal ARCore behavior, not break
   the AR session.
10. Loss or failure of the standalone tracker must put tracking into an explicit lost/reacquiring
    state rather than freezing and presenting a stale pose as valid.
11. Existing MobileGS relocalization remains available until an explicit architectural decision says
    otherwise.

## Files changed by this follow-up

- `core/nativebridge/src/main/cpp/CMakeLists.txt`
  - replace broad AR source glob with upstream-matching explicit source list.

- `feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/rendering/ArRenderer.kt`
  - instantiate SphereSLAM beside ARCore;
  - seed the atlas from target capture;
  - feed luma frames at a throttled rate;
  - close SphereSLAM during renderer teardown.

- `sphereslam/src/main/java/com/hereliesaz/sphereslam/SphereSlamTracker.kt`
  - asynchronous runtime sidecar adapter.

- `sphereslam/src/test/java/com/hereliesaz/sphereslam/SphereSlamTrackerTest.kt`
  - luma row-packing coverage;
  - asynchronous observation publication coverage.

## Next implementation slice

The next safe slice is not "replace ARCore pose."

It is:

1. make target capture provide physical wall scale to the KPM page;
2. define and test the artoolkitX-camera → GraffitiXR/ARCore coordinate conversion;
3. timestamp-pair a KPM observation with ARCore pose history;
4. expose a correction observation to the existing fusion layer;
5. gate correction on inliers, reprojection error, age, and consistency;
6. validate on-device by walking toward/away from the same wall and forcing an ARCore
   relocalization event.

Only after that validation should SphereSLAM be allowed to correct the rendered anchor.
