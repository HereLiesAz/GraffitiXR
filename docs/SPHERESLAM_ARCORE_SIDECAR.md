# SphereSLAM Hybrid + Standalone Integration

Status: dual-backend foundation is on `main`; co-op protocol-v3 calibration is merged through
PR #1968; metric hybrid KPM→PoseFusion correction is implemented on
`feat/sphereslam-hybrid-posefusion` / PR #1970.

## Non-negotiable architecture

SphereSLAM has **two required roles**, selected by device capability.

### Mode A — ARCore + SphereSLAM hybrid

On an ARCore-capable device, ARCore remains the primary continuous metric 6-DoF tracker. SphereSLAM
runs beside it for wall recognition and bounded drift/relocalization correction. When the optional
drift-correction switch is enabled, only a physically metric, timestamp-aligned KPM observation may
enter the explicit `HybridKpmCorrection → PoseFusion` seam. SphereSLAM never overwrites ARCore
view/projection matrices or becomes the continuous camera tracker in this mode.

```
ARCore Session
  ├─ ArCorePoseSource ───────────────► primary renderer pose
  ├─ depth / planes / anchors ───────► existing AR placement pipeline
  └─ camera Image Y plane
         ├─ MobileGS relocalization ─► existing ORB/SuperPoint + PnP path
         └─ SphereSLAM/KPM ──────────► metric rectified wall-page observation
                                          │
                                  HybridKpmCorrection
                                   timestamp + quality gates
                                          │
                                          ▼
                                      PoseFusion
                              anchor-local correction only
```

### Mode B — SphereSLAM standalone

On a phone where ARCore is unsupported or unavailable, **AR mode must remain usable**. SphereSLAM is
the required standalone tracking backend and must provide the capabilities the app needs without
constructing an ARCore `Session`.

The standalone path currently owns:

- CameraX frames without constructing an ARCore `Session`;
- calibrated intrinsics, crop, stride, and display-rotation handling;
- KPM wall-relative 6-DoF while a registered page/atlas page is visible;
- a short rotation-only IMU bridge for brief visual misses, with no fake translation;
- OpenGL-compatible wall-relative view/projection for the transparent overlay;
- measured physical scale when supplied, otherwise explicitly normalized page units;
- wall-target capture, bounded multi-page atlas growth, persistence, restore, and return-visit
  relocalization;
- backend-neutral wall placement/hit-testing without `com.google.ar.core.Anchor`;
- explicit LOCKED/IMU_BRIDGE/REACQUIRING/LOST/FATAL state.

It does **not** claim free-space continuous VIO during long visual loss; after the bounded bridge it
stops trusting the pose and requires visual reacquisition.

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

This branch now has a first functional Mode B path as well as Mode A:

- AR mode stays visible on devices where ARCore resolves unsupported;
- CameraX owns the standalone camera;
- the Y plane is cropped by `ImageProxy.cropRect`, packed with its real row/pixel stride, then
  rotated into display orientation;
- Camera2 intrinsics are shifted by the same crop origin and rotated through the same display
  transform as the pixels;
- CameraController pinch-to-zoom is disabled and camera zoom is reset to 1x while standalone
  tracking is active, so KPM calibration cannot be invalidated by an unmodeled digital zoom;
- a rectified wall target becomes a calibrated KPM reference page;
- KPM's camera-from-page 3x4 is converted with artoolkitX's own right-handed OpenGL convention;
- the KPM lower-left page frame is shifted to a centered renderer frame;
- KPM millimetres are converted into the renderer's shared units;
- the design is rendered directly from that wall-relative view/projection pair;
- the existing fused game-rotation sensor bridges visual losses for at most 400 ms without
  inventing translational motion;
- the rectified wall page is persisted as a versioned `sphereslam_reference_<uuid>.png`; its URI
  and scale metadata are committed together and restored on reopen/import, rebuilding the KPM atlas
  locally.

After rectification, standalone target capture now asks for the real width represented by the page.
A validated measured width is converted into KPM DPI and persisted with
`sphereSlamReferencePhysicallyMetric=true`, so KPM translation/design scale can be physically
metric. The artist can explicitly skip measurement; that path uses normalized 1.0-unit width with
the metric flag false and must never be displayed as a real-world distance.

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

`SphereSlamTracker` is the renderer-facing asynchronous KPM adapter for hybrid ARCore mode.
Standalone CameraX uses the separate synchronous `SphereSlamStandaloneSession` path so it can make
same-frame acceptance/loss decisions.

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

The adapter publishes observations only. `ArRenderer` converts an accepted metric observation into
an artwork-anchor correction through `HybridKpmCorrection` and `PoseFusion`; the observation is
never assigned to the primary ARCore camera matrices. Standalone remains a separate camera/runtime
path rather than reusing this asynchronous latest-observation adapter as continuous tracking.

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

Hybrid capture no longer registers the raw camera photograph at the adapter's default DPI. That was
not physically valid under an oblique view: assigning a DPI to a perspective-warped photo makes KPM
translation look metric without actually matching metres on the wall.

When target capture has a tracking ARCore wall plane:

1. use raw sensor-image intrinsics and `camera.pose.inverse()` for the same pixel geometry as the YUV
   camera frame;
2. intersect center/edge camera rays with the metric ARCore wall plane;
3. choose a conservative centered physical rectangle on that plane;
4. perspective-rectify the captured bitmap to that rectangle's **physical aspect ratio**;
5. compute `referenceDpi` from rectified pixel width / measured wall width;
6. register that rectified image as page 0;
7. create a dedicated ARCore page anchor from the exact same camera-from-page metric geometry.

A new target calls `SphereSlamTracker.clearReference()` synchronously before replacement work, so
an asynchronous matcher cannot publish a late observation from the superseded wall. If there is no
metric ARCore plane or rectification fails, hybrid KPM correction is simply unavailable; ARCore and
MobileGS continue normally.

The rectified hybrid page + page↔artwork relation are currently **runtime-only**. They are not yet
restored after process death/project reopen; the durable MobileGS fingerprint remains the return-
visit path until a new hybrid page is captured.


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
matching. Each tracking frame also records a bounded timestamped pair of the raw sensor-camera ARCore
view and the **unfused** consensus artwork backbone. When a worker result arrives, only a nearby
same-clock sample may be used for correction.

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
camera-from-reference-plane transform. `SphereSlamPoseMath.pageToOpenGlViewMeters` recenters the
page, performs the artoolkitX right-handed GL conversion, and converts millimetres to metres before
`HybridKpmCorrection` composes it with `page_from_artwork`.

## Scale

KPM planar coordinates come from `referenceDpi`, so scale is only trustworthy when the reference
image geometry itself corresponds to a measured physical wall rectangle.

Hybrid mode now satisfies that condition explicitly:

- ARCore supplies a metric wall plane;
- `HybridMetricKpmReference` rectifies a physical rectangle on that plane;
- `SphereSlamPoseMath.dpiForReferenceWidth` chooses DPI so KPM millimetres equal wall metres;
- the hybrid path passes that DPI explicitly and never relies on `SphereSlamTracker`'s generic
  `72f` default.

The generic adapter still retains a default DPI for low-level/tests/legacy callers, but **hybrid
fusion treats that default as non-authoritative**. If a physically metric page cannot be built, KPM
does not enter PoseFusion.

Standalone is separate: a measured page is physically metric; an explicitly skipped measurement is
stored as normalized page units with `sphereSlamReferencePhysicallyMetric=false` and is never
presented or cross-calibrated as metres.


## Relationship to MobileGS relocalization

SphereSLAM does not remove or bypass MobileGS. Both can observe the same wall, but correction is
serialized to one source per render frame.

1. **Metric KPM correction (preferred when a fresh accepted observation exists):**
   - rectified physical wall page;
   - calibrated KPM camera-from-page observation;
   - nearest ARCore sensor-view + unfused artwork-backbone sample within 40 ms;
   - age ≤ 500 ms, ≥ 12 inliers, reprojection error ≤ 4 px;
   - explicit page→artwork transform;
   - `HybridKpmCorrection` produces the corrected artwork anchor at the observation timestamp;
   - `PoseFusion.currentAnchorFromHybridObservation` stores only the anchor-local correction.

2. **MobileGS fallback:**
   - existing wall fingerprint;
   - ORB/SuperPoint matching + `solvePnPRansac`;
   - existing `captureAnchorCam` composition;
   - `PoseFusion.currentAnchor`.

3. **No fresh correction:** PoseFusion reapplies the standing anchor-local correction to the current
   ARCore consensus backbone. If no standing correction exists, the raw ARCore consensus is drawn.

The optional drift-correction switch still gates both downstream correction consumers and defaults
off. KPM never becomes a substitute camera tracker while ARCore is PAUSED.


## Native release-build validation

The release-build CI now checks out the pinned artoolkitX submodule so CI exercises the real KPM
native path instead of the no-submodule stub. The first run after PR #1961 compiled KPM and the
explicit AR source set successfully, then exposed missing transitive ARUtil dependencies at the
final shared-library link. The continuation branch mirrors upstream ARUtil's bundled minizip/SHA-1
support sources and links NDK zlib. Keep this CI path enabled; otherwise native KPM regressions can
silently pass normal builds that never initialize the submodule.

## Crash-safe standalone reference persistence

Recapture never overwrites the currently referenced wall image in place. It writes a new versioned
PNG, commits `sphereSlamReferenceUri`, width, and physical-scale flag together through the
repository's atomic project update, and only then deletes the previous reference best-effort. If the
project changes or the metadata save fails, the uncommitted candidate is deleted and the previous
reference remains authoritative.

Regression tests cover both process-death boundaries: an uncommitted new image cannot replace the
old project reference, while a committed new URI remains authoritative even if the old file is
still present because cleanup had not yet run.

## Import and co-op reference transport

The portable artifact is the rectified SphereSLAM reference PNG plus its width/metric metadata in
`project.json`. Imports and co-op spectator loads copy that PNG into the destination project and
rebase `sphereSlamReferenceUri` before use. KPM atlases and native session handles are rebuilt
locally and are never persisted or transferred.

## Missing or corrupt persisted reference

If the saved reference URI cannot be decoded, the standalone UI asks for a new target and calls a
compare-guarded repository cleanup. Only the matching SphereSLAM URI/width/metric fields are reset;
all other project data is preserved. If a newer recapture has already replaced the URI, the stale
failure callback becomes a no-op.


## Standalone target quality

A newly rectified target is preflighted before native registration for minimum size, luminance
contrast, blur (Laplacian variance), and severe clipped exposure. Exposure is a warning; size,
contrast, and blur are blockers. Native KPM registration is still authoritative: the generated
reference feature count must meet an initial floor of 16. A replacement is persisted only after
that native check passes, so a weak recapture cannot overwrite the previous valid saved target.

## Standalone failure diagnostics

Standalone failures are classified rather than collapsed into one generic KPM error. The runtime
distinguishes native-library unavailability, camera/camera-calibration unavailability, weak
references, no page match, stale observations, excessive reprojection error, insufficient inliers,
pose jumps, corrupt persisted targets, and unexpected internal failures. Transient visual-quality
failures feed the reacquisition HUD; recoverable setup/data failures get concise artist-facing
guidance; true native/internal failures remain modal.

Failure and calibration transitions also retain numeric diagnostics. The failure/reacquisition UI
offers **Copy Diagnostics**, producing one text payload with the standalone backend, camera ID and
intrinsics, raw/crop/display frame geometry, timestamp source, tracking state, last KPM page/inliers/
error/age/match duration, physical-scale status, and current failure detail. Camera-ID acquisition
reports CAMERA_UNAVAILABLE after five seconds rather than spinning indefinitely, while continuing to
accept a late CameraX bind if it recovers.

## Standalone observation age

For Camera2 devices that declare `SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME`, the analyzer compares
the CameraX frame timestamp with `SystemClock.elapsedRealtimeNanos()` after KPM matching and rejects
observations older than 250 ms. Devices reporting UNKNOWN timestamp source are not forced into a
made-up clock conversion: absolute age is logged as unavailable, while KPM processing duration is
still measured. Accepted-match metrics are rate-limited to one diagnostic line every five seconds.

## AR rail capability contract

AR mode remains reachable regardless of ARCore availability, but individual actions are gated by
what the active backend can actually do. Standalone keeps the shared Light, Lock, Magic, design,
project, and settings actions. The legacy ARCore Target rail action is disabled and points the artist
to the standalone on-screen Wall Target capture. Co-op Host/Join use protocol-v3 explicit host
wall-frame metadata: measured standalone pages can pair cross-backend, normalized pages can pair
standalone↔standalone, and incompatible scale/backend combinations fail before bulk transfer. Leave
remains reachable for an already-active session. AR preview Export is still disabled on standalone
until CameraX and the
transparent GL overlay can be composited into the same mode screenshot; exporting only artwork
layers would be misleading.

## Standalone tracking-state contract

The standalone runtime exposes explicit `INITIALIZING`, `LOCKED`, `IMU_BRIDGE`,
`REACQUIRING`, `LOST`, and `FATAL` states. Initial acquisition and post-loss reacquisition
require two consecutive accepted visual poses. A brief miss from `LOCKED` uses the short
rotation-only IMU bridge; after the bridge expires the state becomes `REACQUIRING`, then `LOST`
after 2 seconds without recovery. State changes are surfaced in the standalone HUD and existing AR
diagnostic log.

## Standalone pose acceptance

The CameraX standalone path applies an explicit app-level KPM acceptance policy before publishing a
pose. Defaults mirror the pinned artoolkitX binary path: at least 4 inliers and ICP/reprojection
error no greater than 10.0. Non-finite matrices/errors are also rejected. A second continuity gate rejects catastrophic
frame-to-frame jumps using reference-page widths (scale-independent for measured and normalized
targets) and rotation angle. Reacquisition has deliberately looser limits than locked tracking.
Rejections enter the same short IMU-bridge/loss path as a missing visual match and are logged only
when the rejection reason changes. On Camera2 devices with REALTIME sensor timestamps, observations
older than 250 ms are rejected against `SystemClock.elapsedRealtimeNanos()`; UNKNOWN timestamp
sources are reported as age-unavailable rather than forced through a guessed clock conversion.

## Standalone calibration diagnostics

Whenever the effective CameraX calibration changes, the standalone analyzer emits one line through
the existing AR diagnostic log with camera ID, raw/crop/display dimensions, display rotation, and
`fx/fy/cx/cy`. It deliberately does not log every frame.

## Lifecycle

The `SphereSlamTracker` lifetime follows `ArRenderer`.

On renderer destruction:

- the current hybrid page is synchronously disabled;
- pending SphereSLAM frames and latest observations are discarded;
- the dedicated ARCore page anchor is detached;
- timestamp history/page↔artwork runtime state is cleared;
- native KPM session destruction is scheduled on the SphereSLAM worker;
- the worker executor is shut down.

ARCore teardown remains controlled by the renderer/session locking already present in
`:feature:ar`.

## Current limitations

### Hybrid

The correction software path is implemented, but it is not yet production-validated:

- the rectified hybrid KPM page and frozen `page_from_artwork` relation are runtime-only; project
  reopen/process death currently falls back to the durable MobileGS fingerprint until a new hybrid
  page is captured;
- current KPM gates cover metric frame, age, timestamp pairing, inliers, reprojection error, and
  finite matrices, but there is not yet a separate hard maximum correction-distance/angle rejection
  policy or repeated-observation agreement requirement before a large cold snap;
- accepted/rejected KPM decisions are logged, but the full age/error/delta payload is not yet wired
  into the artist-facing fusion diagnostics/report;
- real-device forced-drift, wrong-wall, no-jump, recapture, and lifecycle tests remain required.

### Standalone

The standalone software path now includes CameraX calibration, measured/normalized page scale,
MobileGS centered-page integration, persisted feature maps, bounded 12-page atlas growth/restore,
explicit ARCore-only capability degradations, and protocol-v3 cross-backend co-op calibration.

Remaining work is predominantly device/performance validation and product hardening:

- physical walk-off/return testing across grown pages;
- permission revocation, camera interruption, and background/foreground lifecycle tests;
- sustained KPM FPS/latency/thermal/heap measurements and page-limit validation from measurements;
- repeated AR enter/exit leak checks;
- standalone composited preview export;
- real-device validation on hardware that genuinely lacks ARCore.

