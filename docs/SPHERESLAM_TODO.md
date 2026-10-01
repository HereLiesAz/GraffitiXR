# SphereSLAM Implementation TODO

Status: active implementation plan. Initial standalone work merged to `main` in PR #1961;
continuation work is on `feat/sphereslam-todo-continuation`.

Checklist refreshed from `main` at `706ccb7ec949afe055156d23d6a0cc7a8c7126bd`.

This is the authoritative remaining-work list for GraffitiXR's SphereSLAM integration. It covers both:

- **Hybrid mode** — ARCore remains the primary continuous pose source; SphereSLAM/KPM is an explicit
  wall-recognition / relocalization / correction input.
- **Standalone mode** — on devices where ARCore cannot run, CameraX + SphereSLAM/KPM provide the
  wall-relative AR path without constructing an ARCore `Session`.

The list is intentionally granular. An unchecked box should correspond to one reviewable code,
test, device-validation, or product-decision unit rather than a broad theme.

## Status legend

- [x] implemented on the current branch;
- [ ] implementation or validation still required;
- **BLOCKED BY:** prerequisite that must land first;
- **ACCEPTANCE:** observable condition required before the item is considered done.

---

## 0. Preserve the architecture

These are invariants, not optional cleanup.

- [x] Keep ARCore as the primary continuous tracker on ARCore-capable devices.
- [x] Keep ARCore optional at the product/install level.
- [x] Keep AR mode reachable when ARCore is unsupported.
- [x] Route unsupported devices to CameraX + standalone SphereSLAM instead of redirecting to Overlay.
- [x] Keep hybrid KPM matching off the ARCore GL/render thread.
- [x] Keep standalone KPM matching off the Compose/Main thread.
- [x] Never silently replace ARCore view/projection matrices with a KPM observation in hybrid mode.
- [x] Treat long standalone visual loss as LOST/REACQUIRING instead of freezing the last pose.
- [x] Do not integrate phone accelerometer translation as fake dead reckoning.
- [x] Add an automated architecture test that fails if non-ARCore AR mode is hidden again.
- [x] Add an automated architecture test that fails if the standalone branch constructs an ARCore
  `Session`.
- [x] Add an automated architecture test that fails if hybrid SphereSLAM writes directly into the
  primary renderer pose without going through the explicit fusion seam.

The three architecture guards above are enforced by
`tools/check_sphereslam_architecture.py` in both Android CI and Merged Build & Release. The check
is deliberately strict: any future hybrid observation consumption must introduce/update an explicit
fusion seam rather than bypassing the guard.

---

## 1. Build and branch verification

This must happen before treating the branch as merge-ready.

- [x] Rebase/merge the newer `main` commits into the standalone implementation before merge.
- [x] Resolve the resulting native/doc conflicts without changing the dual-backend architecture.
- [x] Run `:sphereslam:test` (covered by the repository-wide Gradle `test` task in Android CI).
- [x] Run `:feature:ar:testDebugUnitTest` or the repository's actual equivalent unit-test task
  (covered by the repository-wide Gradle `test` task in Android CI).
- [x] Run the app module's JVM/unit tests (repository-wide Gradle `test` passed).
- [x] Run the repository's native locking/static checks that cover `MobileGS` / JNI.
- [ ] Compile the native `:core:nativebridge` target for every release ABI.
- [ ] Build at least one debug APK containing artoolkitX KPM.
- [x] Build the normal release artifact(s): Android CI `release-build` completed
  `assembleRelease` successfully with the real artoolkitX submodule initialized.
- [ ] Inspect the merged manifest and confirm:
  - [ ] `android.hardware.camera.ar` remains `required="false"`;
  - [ ] `com.google.ar.core` remains optional;
  - [ ] no new required hardware feature excludes non-ARCore devices.
- [ ] Confirm ProGuard/R8 does not strip the KPM JNI entry points or standalone classes.
- [ ] Confirm no duplicate native symbol/source issue was introduced by the explicit artoolkitX AR
  source list.
- [x] Add/enable CI for branch/PR validation; PR #1961 ran Android CI with the artoolkitX
  submodule enabled in the release-build job.
- [x] Re-run release CI after the ARUtil/minizip/SHA-1 support-source fix; the release build
  completed successfully, proving the previous undefined-symbol failure is resolved.

**ACCEPTANCE:** branch compiles, tests run, native libraries package for supported ABIs, and the
merged manifest still permits installation on non-ARCore hardware.

### CI findings — 2026-10-01

PR #1961's first submodule-enabled `assembleRelease` run proved that the embedded artoolkitX build
now compiles all KPM + explicit AR sources through creation of `libarx_kpm.a`. The final
`libgraffitixr.so` link then failed because PR #1960 added `ARUtil/file_utils.c` without the
support objects that upstream ARUtil links with it:

- bundled minizip: `ioapi.c`, `unzip.c`, `zip.c`, `crypt.c`;
- SHA-1: `uuid/uuid_sha1.c`;
- Android/NDK zlib: `libz`.

The continuation branch now matches those transitive upstream dependencies rather than suppressing
individual unresolved symbols. This item remains unchecked until CI completes the final link.

A later superseded CI run also caught an extra closing brace introduced while adding
`ImageProxy.cropRect` support in `LumaFrameTransform.kt`; that Kotlin syntax regression was fixed
at `42ec45ff` before continuing. Keep the full `test` task in the loop even when a change looks
like pure camera math.

The first architecture-guard run then produced a false negative because the checker treated the
first nested Compose `} else {` as the end of the outer non-ARCore branch. The application source
still mounted `SphereSlamStandaloneOverlay`; the guard now anchors the block end at the distinct
outer ARCore branch (`var glView`) instead. This was a checker defect, not a runtime regression.

---

## 2. Standalone camera/calibration correctness

Implemented foundation:

- [x] CameraX owns the camera when ARCore is unavailable.
- [x] CameraX Y-plane row stride is honored.
- [x] CameraX Y-plane pixel stride is honored.
- [x] 0/90/180/270 display rotation is applied to luminance.
- [x] Camera2 intrinsics are rotated through the same transform as the pixels.
- [x] KPM receives tightly packed direct luminance.
- [x] KPM runtime session uses calibrated `ARParamLT`.
- [x] KPM camera-from-page 3x4 is converted using artoolkitX's RH OpenGL convention.
- [x] KPM page origin is shifted from lower-left to the centered renderer frame.
- [x] KPM millimetres are converted to renderer units.
- [x] Unit tests cover luma packing/quarter-turn rotation.
- [x] Unit tests cover CameraX crop-before-rotation and principal-point crop adjustment.
- [x] Unit tests cover KPM→OpenGL sign/layout conversion.
- [x] Unit tests cover page centering and page scale math.

Remaining:

- [ ] Validate Camera2 estimated intrinsics against real CameraX output sizes on at least:
  - [ ] 4:3 sensor → portrait display;
  - [ ] 4:3 sensor → landscape display;
  - [ ] 16:9/other cropped CameraX stream;
  - [ ] front camera if the app ever allows it; otherwise explicitly lock standalone AR to back camera.
- [x] Prevent CameraX crop/zoom from invalidating the intrinsics used by KPM for the supported
  standalone path: apply ImageProxy crop explicitly and lock camera zoom at 1x while tracking.
  - [x] Apply `ImageProxy.cropRect` to the luma pixels before rotation.
  - [x] Shift `cx/cy` by the crop origin before rotating intrinsics.
  - [x] Prevent CameraX digital zoom from changing standalone calibration: standalone AR disables
    CameraController pinch-to-zoom and resets the camera to 1x while active, restoring the shared
    controller's previous zoom behavior on exit.
- [x] If CameraX applies an ImageProxy crop region, incorporate that crop into `fx/fy/cx/cy`.
- [ ] Verify lens distortion is acceptable with the current zero-distortion KPM camera model.
- [ ] If not, populate artoolkitX distortion parameters from Camera2 calibration metadata or undistort
  frames before KPM.
- [x] Add a diagnostic line exposing standalone camera ID, raw/crop/display frame dimensions,
  rotation, and fx/fy/cx/cy for field bug reports. It emits only when effective calibration changes.
- [ ] Test display rotation while tracking; ensure session rebuild/recalibration happens if required.
- [ ] Test device auto-rotate disabled and confirm image/intrinsic orientation remains internally
  consistent.

**ACCEPTANCE:** a known planar target stays registered through all supported device orientations
without a systematic mirrored, rotated, cropped, or scale-shifted pose.

---

## 3. Standalone physical scale

Current behavior is deliberately normalized, not physically metric.

- [x] KPM page DPI can be derived from an explicit reference width.
- [x] The persisted reference records whether its width is physically metric.
- [x] Non-metric standalone mode does not claim a real physical measurement in the implementation
  contract.

Remaining / status:

- [x] Choose the first physical-scale input: measured rectified-target width entered by the artist.
  The alternatives (two-point measurement / hardware depth) can be added later without changing the
  KPM scale contract.
- [x] Add the chosen scale fields to project data with backward-compatible defaults
  (`sphereSlamReferenceWidthMeters` + `sphereSlamReferencePhysicallyMetric`).
- [x] Add capture UI that asks for the real target width after four-corner rectification.
- [x] Validate scale bounds and reject non-finite, zero/negative, and implausible values
  (accepted range: 0.05–100 m).
- [x] Pass the entered real width to `SphereSlamStandaloneSession.addReference`.
- [x] Persist `sphereSlamReferencePhysicallyMetric=true` only when a validated measured width is
  accepted.
- [x] Preserve an explicit "Continue Without Physical Scale" path using normalized 1.0-unit scale
  with the metric flag false.
- [ ] Update standalone distance/measurement UI so it is enabled only for physically metric targets
  when such a readout is added.
- [x] Add unit tests proving the same pixel reference round-trips through KPM DPI for several
  physical widths, plus validation-boundary tests for the capture input.
- [ ] Add an on-device ruler/tape-measure validation at multiple camera distances.

**ACCEPTANCE:** camera translation and rendered design dimensions agree with a physical measurement
within an explicitly documented tolerance.

---

## 4. Standalone target-capture workflow parity

Implemented:

- [x] Capture a wall target from CameraX.
- [x] Four-corner perspective unwarp.
- [x] Build KPM page from the rectified image.
- [x] Recapture replaces the active standalone page.
- [x] Canonical standalone page persists in the project.
- [x] Reopen rebuilds the KPM atlas from the persisted page.
- [x] Import relocates the persisted page URI to the imported project directory.

Remaining:

- [ ] Integrate standalone target capture into the normal AR target workflow/state machine instead of
  maintaining a separate minimal capture UI.
- [ ] Reuse the same capture/review language and error states where the concepts are equivalent.
- [ ] Define what standalone does for ARCore-only target steps:
  - [ ] plane hit test;
  - [ ] depth sample;
  - [ ] ARCore anchor creation;
  - [ ] point cloud/plane coaching.
- [ ] Hide or replace only the truly unavailable steps, not the entire workflow.
- [x] Add target-quality checks before accepting a KPM page:
  - [x] minimum image dimensions/pixel count;
  - [x] minimum native-generated KPM feature count (16 initial conservative floor);
  - [x] texture/contrast threshold using luma standard deviation;
  - [x] blur threshold using Laplacian variance;
  - [x] excessive over/under-exposure warning using clipped-pixel fraction.
- [x] Provide useful user-facing reasons for small/flat/blurry captures and too few native KPM
  features.
- [x] Preserve the previous valid target if a replacement fails native KPM feature validation;
  persistence happens only after the replacement page passes native registration.
- [x] Confirm recapture logically atomically replaces persisted standalone target metadata and
  image: write a new versioned PNG, atomically commit URI + scale fields through the repository,
  then delete the previous file.
- [x] Delete/garbage-collect superseded standalone reference files best-effort after metadata commit;
  aborted/project-switched recaptures delete their uncommitted candidate instead.
- [x] Add project/data tests for legacy projects without standalone fields, versioned reference
  write/delete lifecycle, and import URI relocation with standalone scale metadata.
- [x] Add repository/viewmodel replacement-race tests: URI + width + metric flag commit together,
  a competing recapture wins without being deleted, and an uncommitted candidate is cleaned up.
- [x] Add process-death boundary tests: before metadata commit the old reference remains
  authoritative; after metadata commit the new reference remains authoritative even if old-file
  cleanup has not happened yet.

**ACCEPTANCE:** an artist can create, review, save, reopen, replace, export, import, and reacquire a
standalone wall target without touching an ARCore-specific UI dead end.

---

## 5. Standalone pose continuity and loss handling

Implemented:

- [x] KPM provides wall-relative 6-DoF while the page is visible.
- [x] Fused game-rotation sensor bridges short KPM misses.
- [x] IMU bridge is rotation-only.
- [x] IMU bridge rotates view translation consistently with the held camera center.
- [x] Bridge expires after 400 ms.
- [x] Expired visual loss clears the rendered pose.

Remaining:

- [ ] Measure actual KPM cadence/latency on target devices.
- [x] Record KPM observation age in standalone diagnostics when Camera2 declares a REALTIME
  timestamp source; also record KPM match-processing duration on every device.
- [x] Gate stale KPM matches by timestamp when Camera2 declares
  `SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME` (250 ms default ceiling). UNKNOWN timestamp sources are
  never compared to `elapsedRealtimeNanos()`; absolute age is reported as unavailable rather than
  guessed.
- [x] Add configurable minimum inlier count for standalone acceptance. Default is 4, matching the
  pinned artoolkitX binary KPM minimum correspondence count.
- [x] Add configurable maximum reprojection/ICP error for standalone acceptance. Default is 10.0,
  matching the pinned artoolkitX `kpmUtilGetPose_binary` rejection threshold.
- [x] Add pose-jump rejection:
  - [x] maximum angular jump per frame (90° default catastrophic-jump ceiling);
  - [x] maximum translation jump per frame in reference-page widths (2 page widths default);
  - [x] separate relaxed thresholds during explicit reacquisition (175° / 8 page widths).
- [x] Add hysteresis so a single weak frame does not flap LOCKED↔LOST: acquisition/reacquisition
  requires two consecutive accepted visual poses and a short miss enters IMU_BRIDGE first.
- [x] Define and expose standalone tracking states:
  - [x] INITIALIZING;
  - [x] LOCKED;
  - [x] IMU_BRIDGE;
  - [x] REACQUIRING;
  - [x] LOST;
  - [x] FATAL.
- [x] Feed those states into the standalone AR HUD and existing diagnostic log.
- [ ] Evaluate whether KPM-only continuous wall tracking is sufficiently smooth for the intended
  wall-painting use case.
- [ ] If not, add a true frame-to-frame visual/inertial tracker behind the same standalone pose
  interface rather than extending IMU bridge duration.
- [ ] If a continuous tracker is added:
  - [ ] timestamp-align camera frames and IMU samples;
  - [ ] define initialization/reset behavior;
  - [ ] define KPM relocalization correction into that tracker;
  - [ ] keep KPM page recognition independent of frame-to-frame VIO state.

**ACCEPTANCE:** normal hand motion is smooth, short occlusions recover without a visible snap, long
occlusions never present stale placement as live tracking, and reacquisition is deterministic.

---

## 6. Standalone artwork rendering/control parity

Implemented:

- [x] Transparent GL overlay above CameraX preview.
- [x] Persisted AR `ModeAdjustment` is used in standalone mode.
- [x] Brightness is applied.
- [x] Contrast is applied.
- [x] Saturation is applied.
- [x] Opacity is applied.
- [x] Invert is applied.
- [x] Pan is applied in wall units.
- [x] Scale is applied.
- [x] In-plane Z rotation is applied with screen↔GL sign conversion.
- [x] X perspective rotation is applied.
- [x] Y perspective rotation is applied.
- [x] Gesture drag gets standalone wall-units-per-pixel instead of ARCore-only metres-per-pixel.
- [x] Artwork is aspect-fit inside the standalone reference page before user transforms.

Remaining:

- [ ] Verify every rail/control available in AR mode either:
  - [ ] works on both backends; or
  - [ ] is explicitly disabled with a backend-specific reason.
- [x] Verify transform lock prevents standalone pan/scale/rotate: both AR backends consume the same
  `modeAdjustments[AR]`, and the shared reducer rejects AR transform gestures while locked.
- [x] Verify undo/redo behavior for standalone mode adjustments with an explicit AR-mode gesture
  history test; standalone and ARCore consume the same restored adjustment.
- [x] Verify design visibility/removal clears the standalone GL texture: null design now posts an
  explicit GL-thread clear command instead of leaving the previous texture resident.
- [x] Verify rapid clear/replace ordering with a single-slot atomic texture-command mailbox; unit
  tests prove the latest clear or replacement wins.
- [x] Verify design aspect changes recalculate only the tested aspect-fit base extent; user
  pan/scale/rotation remain in the separate persisted ModeAdjustment.
- [x] Correct and test standalone gesture coordinates for CameraX `FIT_CENTER` letterboxing:
  camera-frame wall-units/pixel are converted to screen wall-units/pixel using the actual fitted
  viewport height; crop handling remains in the calibrated frame transform.
- [ ] Add GL/instrumentation tests for standalone texture clear/replace if test infrastructure permits.
- [x] Sanitize and test standalone renderer transforms: non-finite values fall back safely, scale
  uses the shared 0.1–10 editor bounds, rotations normalize, and pathological finite pan values are
  bounded before GL matrix construction.

**ACCEPTANCE:** the same project adjustment produces the same intended artwork placement and visual
treatment on ARCore and standalone backends, modulo explicitly documented backend capabilities.

---

## 7. Standalone project/data lifecycle

Implemented:

- [x] `sphereSlamReferenceUri` project field.
- [x] persisted reference width.
- [x] persisted physical-metric flag.
- [x] routine saves preserve standalone reference metadata.
- [x] versioned reference PNG is stored inside the project directory and selected by
  `sphereSlamReferenceUri`.
- [x] `.gxr` export naturally includes the reference image.
- [x] import relocates the reference URI.

Remaining:

- [x] Add migration/default test for projects created before the standalone fields existed.
- [x] Add coverage for missing/corrupt URI handling plus legacy/default project behavior; legacy
  fixed `sphereslam_reference.png` files are accepted by guarded cleanup.
- [x] On missing/corrupt reference, clear only standalone target URI/scale fields and preserve the
  rest of the project. Cleanup uses a compare guard so a stale failure callback cannot erase a newer
  recapture.
- [x] Verify duplicate-ID imports rebase the SphereSLAM URI into the newly assigned local project
  directory and leave the existing project untouched.
- [x] Verify project deletion removes the standalone reference with the project directory.
- [x] Verify project copy/duplicate flows: there is no separate project-duplicate operation in the
  repository; the two copy-like paths are duplicate-ID import and co-op spectator load, both covered
  by explicit SphereSLAM URI-rebasing tests.
- [x] Verify co-op bulk project transfer includes and rebases the standalone reference into the
  spectator project directory.
- [x] Decide co-op storage contract: transfer the raw rectified reference PNG + scale metadata and
  rebuild KPM locally; do not serialize a native atlas.
- [x] Do not persist raw native KPM handles.
- [x] No serialized KPM dataset is persisted in the current design. If that policy changes later,
  the dataset must be versioned, retain source image/calibration for rebuild, and invalidate on
  camera/page calibration changes.

**ACCEPTANCE:** no save/import/export/delete/peer-transfer operation can silently orphan or point a
project at the wrong standalone wall page.

---

## 8. MobileGS / fingerprint integration in standalone mode

This is the highest-risk remaining integration because coordinate frames must not be mixed.

Current rule:

- [x] Do **not** feed standalone CameraX frames into MobileGS and pretend an ARCore-world fingerprint
  and a KPM-page pose share a coordinate frame.

Required work:

- [ ] Define the standalone fingerprint coordinate frame.
  - [ ] Recommended: centered SphereSLAM page frame, with physical scale when known.
- [ ] Define an explicit transform between:
  - [ ] KPM page frame;
  - [ ] standalone renderer wall frame;
  - [ ] MobileGS fingerprint frame.
- [ ] Unit-test that transform with known synthetic poses.
- [ ] Decide how standalone creates the first MobileGS fingerprint:
  - [ ] generate 3D points directly on the KPM page plane; or
  - [ ] adapt `MetricFingerprintBuilder` to accept the standalone wall frame.
- [ ] Ensure descriptor pixels and 3D points are generated from the same display-oriented image.
- [ ] Set MobileGS live intrinsics from the standalone display-oriented CameraX calibration.
- [ ] Feed CameraX YUV/color frames to MobileGS only after the frame contract above is satisfied.
- [ ] Feed standalone camera view/projection to `slamManager.updateCamera` only after its world/frame
  semantics match what MobileGS expects.
- [ ] Rename or generalize `setArCoreTrackingState` before using it for standalone tracking health;
  do not lie to native code by setting an "ARCore" flag when ARCore does not exist.
- [ ] Audit every native branch conditioned on `mIsArCoreTracking` and decide the standalone
  equivalent explicitly.
- [ ] Restore existing saved ARCore fingerprints safely on standalone devices:
  - [ ] either provide a validated frame conversion; or
  - [ ] mark them ARCore-frame-only and require a standalone target/fingerprint conversion step.
- [ ] Define behavior for projects that contain both ARCore fingerprint data and a standalone page.
- [ ] Feed paint-progress/distortion-head/corroboration only after coordinate alignment is proven.
- [ ] Verify self-grow adds points in the standalone fingerprint frame.
- [ ] Verify saved wall feature maps preserve that frame across process restarts.
- [ ] Add diagnostics identifying fingerprint frame/version/backend.

**ACCEPTANCE:** MobileGS can consume standalone frames without any implicit ARCore-world assumption,
and return-visit / paint-progress results agree spatially with the KPM wall pose.

---

## 9. Standalone wall-map growth beyond one page

Current implementation rebuilds one canonical KPM page.

- [ ] Define when an additional page should be captured:
  - [ ] minimum baseline from existing pages;
  - [ ] sufficient KPM/feature quality;
  - [ ] sufficient overlap for frame registration;
  - [ ] not while tracking confidence is low.
- [ ] Assign stable page IDs.
- [ ] Store page-to-canonical-wall transforms.
- [ ] Add pages without resetting the canonical wall frame.
- [ ] Match against the atlas and report which page produced the pose.
- [ ] Convert every matched page pose back into the same canonical wall frame.
- [ ] Cap page count / memory usage.
- [ ] Define page eviction/compaction policy.
- [ ] Persist page images or a rebuildable page dataset.
- [ ] Restore page atlas on project reopen.
- [ ] Include atlas assets in export/import.
- [ ] Add corruption/version handling.
- [ ] Test moving far enough that the original page exits view while a grown page remains visible.
- [ ] Test returning from a grown page to the original page without a coordinate jump.
- [ ] Test loop consistency across three or more overlapping pages.

**ACCEPTANCE:** an artist can move across a wall larger than the original target while the artwork
remains in one stable wall coordinate frame.

---

## 10. Hybrid SphereSLAM → ARCore fusion

Hybrid observation production exists; correction does not.

Already available:

- [x] asynchronous hybrid KPM matcher;
- [x] calibrated KPM session;
- [x] KPM observation timestamp;
- [x] page ID;
- [x] reprojection error;
- [x] inlier count;
- [x] ARCore remains primary pose source.

Required work:

- [ ] Seed hybrid KPM page with a physically correct scale rather than the adapter's legacy 72-DPI
  default.
- [ ] Reuse the tested KPM→OpenGL conversion in the hybrid path.
- [ ] Define the transform from KPM page frame to the ARCore anchor/world frame at capture time.
- [ ] Persist that capture-time page↔ARCore relation.
- [ ] Maintain short ARCore pose history keyed by frame timestamp.
- [ ] Pair each KPM observation with the corresponding/interpolated ARCore pose.
- [ ] Reject KPM observations older than the allowed fusion age.
- [ ] Define confidence gates:
  - [ ] minimum inliers;
  - [ ] maximum reprojection error;
  - [ ] maximum correction translation;
  - [ ] maximum correction angle;
  - [ ] repeated-observation agreement requirement.
- [ ] Add a formal SphereSLAM correction observation type.
- [ ] Feed accepted corrections into `PoseFusion`, not directly into renderer matrices.
- [ ] Define correction strength/smoothing.
- [ ] Ensure a rejected/failed KPM observation leaves ARCore behavior unchanged.
- [ ] Add fusion diagnostics: accepted/rejected reason, age, inliers, error, delta angle/translation.
- [ ] Unit-test known page/ARCore transforms and correction deltas.
- [ ] On-device test forced ARCore drift/relocalization with KPM visible.
- [ ] Verify no visible jump when KPM and ARCore already agree.
- [ ] Verify stale/wrong-wall KPM cannot drag the anchor away.

**ACCEPTANCE:** SphereSLAM can improve/recover wall registration in hybrid mode through
`PoseFusion` while ARCore remains the continuous backbone and KPM failure is harmless.

---

## 11. ARCore-only feature audit and standalone equivalents

Every AR feature needs a deliberate answer.

### Plane / hit-test behavior

- [ ] Inventory every `Frame.hitTest` / ARCore plane consumer.
- [ ] For wall placement, replace required hit tests with ray→standalone-wall-plane intersection.
- [ ] Define behavior when the standalone wall is not locked.
- [ ] Test taps near page boundaries and outside the reference image.

### Depth

- [ ] Inventory every ARCore Depth API consumer.
- [ ] Categorize each as:
  - [ ] required for core wall placement;
  - [ ] optional enhancement;
  - [ ] diagnostics only.
- [ ] Provide Camera2/depth-sensor alternative where realistically available.
- [ ] Otherwise disable the feature honestly in standalone mode.
- [ ] Never fabricate a depth value from normalized KPM scale.

### Anchors

- [ ] Replace `com.google.ar.core.Anchor` dependencies required by core placement with a backend-
  neutral wall/anchor transform.
- [ ] Add standalone anchor generation/version to state.
- [ ] Ensure recapture invalidates the old standalone anchor generation.
- [ ] Ensure design placement is persisted relative to the correct backend-neutral wall frame.

### Cloud anchors

- [ ] Decide whether cloud anchors are:
  - [ ] unavailable in standalone mode; or
  - [ ] replaced by project/fingerprint/KPM sharing.
- [ ] Disable cloud-anchor UI when no valid equivalent exists.
- [ ] Ensure disabling it does not disable local/co-op wall sharing that can work without ARCore.

### Point clouds / perception debug

- [ ] Decide what standalone perception/debug view displays.
- [ ] Do not show empty ARCore point/plane layers as if the tracker failed.
- [ ] Expose KPM keypoints/inliers/page boundary if useful for diagnostics.

**ACCEPTANCE:** no AR-mode button or background code path on a non-ARCore device reaches an
ARCore-only API without an explicit capability guard/equivalent.

---

## 12. Co-op / multi-device standalone behavior

- [ ] Define the coordinate object shared by a standalone host.
- [ ] Include standalone reference image/scale/frame metadata in the initial project snapshot.
- [ ] Define standalone-host → ARCore-guest calibration.
- [ ] Define ARCore-host → standalone-guest calibration.
- [ ] Define standalone-host → standalone-guest calibration.
- [ ] Reuse Procrustes/other existing calibration only after input frames are explicitly defined.
- [ ] Reject peer calibration when either side lacks a physically meaningful scale and the operation
  requires metric alignment.
- [ ] Decide whether normalized-scale standalone peers can still share a wall by page-relative
  correspondence.
- [ ] Add protocol versioning if new calibration payload fields are required.
- [ ] Maintain backward compatibility or produce a clear incompatible-peer error.
- [ ] Test reconnect/resync with standalone project assets.

**ACCEPTANCE:** co-op never silently combines coordinates from different backends/scales as though
they were the same frame.

---

## 13. Performance / thermal / memory

- [ ] Measure standalone KPM matching time at representative CameraX resolutions.
- [ ] Measure UI/preview FPS with KPM active.
- [ ] Measure native heap and Java heap while tracking.
- [ ] Confirm direct frame buffers are reused rather than allocated each frame.
- [ ] Confirm pending work cannot queue unboundedly.
- [ ] Add adaptive standalone analysis cadence if KPM exceeds the frame budget.
- [ ] Preserve low latency over maximum throughput: drop stale frames instead of queueing them.
- [ ] Measure battery/thermal behavior over a realistic mural session.
- [ ] Verify background/pause stops CameraX analysis and IMU sampling.
- [ ] Verify resume recreates/calibrates the standalone session cleanly.
- [ ] Verify repeated AR enter/exit does not leak KPM sessions, executors, GL textures, or sensors.
- [ ] Measure atlas memory before enabling multi-page growth.
- [ ] Set explicit atlas/page limits from measurements.

**ACCEPTANCE:** a sustained standalone session remains responsive and bounded in latency, memory,
thread count, and temperature.

---

## 14. Failure handling and user-facing diagnostics

- [x] Fatal KPM/native failure produces a standalone error instead of crashing.
- [x] Tracking loss exposes reacquisition state.
- [x] User can recapture the target from standalone UI.

Remaining:

- [ ] Distinguish:
  - [ ] native library unavailable;
  - [ ] camera unavailable;
  - [ ] intrinsics unavailable;
  - [ ] reference too weak;
  - [ ] no current page match;
  - [ ] stale observation;
  - [ ] excessive reprojection error;
  - [ ] insufficient inliers;
  - [ ] persisted target missing/corrupt.
- [ ] Surface concise artist-facing messages for recoverable failures.
- [ ] Keep detailed numeric reasons in diagnostics/logs.
- [ ] Add one-copy diagnostic dump that includes backend, camera calibration, KPM state, page ID,
  inliers, reprojection error, observation age, and physical-scale status.
- [ ] Verify permission revocation while standalone AR is open.
- [ ] Verify camera interruption by another app.
- [ ] Verify app background/foreground during LOCKED, IMU_BRIDGE, and REACQUIRING states.
- [ ] Verify process death during target persistence cannot leave a truncated/half-installed
  canonical target.
- [ ] Verify a missing native KPM build degrades with a clear unsupported-build message, not an
  infinite spinner.

---

## 15. Automated test backlog

Already added:

- [x] KPM page scale math.
- [x] KPM→OpenGL pose conversion.
- [x] centered page transform.
- [x] standalone session fake-engine test.
- [x] luma stride packing.
- [x] luma 90/180/270 rotation.
- [x] IMU-held view/translation rotation.
- [x] asynchronous hybrid tracker packing/publication tests.
- [x] tracking-mode selector tests already present on the branch.

Still required:

### JVM/pure math

- [x] standalone acceptance-gate tests for REALTIME observation age/staleness and UNKNOWN-source
  no-guess behavior;
- [x] standalone acceptance-gate tests for inliers/error/non-finite poses;
- [x] pose-jump gate tests, including scale-independent page-width translation and relaxed
  reacquisition thresholds;
- [x] tracking-state hysteresis tests for initial lock, bridge, reacquisition, LOST timeout, and
  fatal/reset behavior;
- [ ] standalone page↔MobileGS frame conversion tests;
- [ ] hybrid page↔ARCore frame conversion tests;
- [ ] timestamp pairing/interpolation tests;
- [ ] physical-scale migration tests;
- [ ] multi-page canonical-frame conversion tests.

### Android/Robolectric/instrumented

- [ ] Camera2 crop/intrinsic test;
- [ ] CameraX camera-ID acquisition test;
- [ ] target persistence/readback test;
- [ ] project import URI relocation test;
- [ ] process recreation test;
- [ ] GL texture clear/replace test;
- [ ] lifecycle analyzer/IMU teardown test;
- [ ] backend routing test on ARCore-unavailable environment.

### Native

- [ ] KPM JNI smoke test on packaged APK;
- [ ] KPM add-page/match known-image integration test;
- [ ] native buffer-size guard tests;
- [ ] MobileGS standalone-frame integration tests after frame conversion exists;
- [ ] native thread/teardown race test.

If the repo still lacks the required Android/native test infrastructure, create that infrastructure
as its own reviewable change rather than marking these tests impossible.

---

## 16. Real-device validation matrix

At minimum test:

### ARCore-capable

- [ ] one modern Pixel/Google reference-class device;
- [ ] one Samsung device;
- [ ] portrait;
- [ ] landscape;
- [ ] target capture;
- [ ] KPM sidecar seeded;
- [ ] normal ARCore placement unchanged;
- [ ] forced KPM failure leaves ARCore healthy;
- [ ] forced ARCore relocalization while KPM sees the target.

### ARCore-unsupported

- [ ] at least one real API-26+ device that cannot run ARCore;
- [ ] fresh project capture;
- [ ] saved-project reopen;
- [ ] export/import;
- [ ] target reacquisition;
- [ ] walk toward wall;
- [ ] walk away from wall;
- [ ] lateral motion;
- [ ] moderate oblique view;
- [ ] brief occlusion (<400 ms);
- [ ] long occlusion (>400 ms);
- [ ] target exits and re-enters frame;
- [ ] device rotation;
- [ ] app background/foreground;
- [ ] process kill/relaunch;
- [ ] pan/scale/Z rotation;
- [ ] X/Y perspective rotation;
- [ ] tone/opacity/invert;
- [ ] flashlight;
- [ ] permission revoke/restore;
- [ ] low light;
- [ ] repetitive/weak-texture wall;
- [ ] high-detail wall.

### Cross-version/project

- [ ] project created before SphereSLAM fields;
- [ ] project created on ARCore device opened on standalone device;
- [ ] standalone project opened on ARCore device;
- [ ] project round-tripped through `.gxr`;
- [ ] co-op transfer once standalone payload support lands.

For every device run, record:

- app version/commit;
- device/model/API;
- camera ID/resolution;
- fx/fy/cx/cy;
- backend selected;
- physical-scale status;
- KPM inliers/error;
- lock/recovery behavior;
- any visible registration error.

---

## 17. Documentation cleanup

- [x] architecture document describes dual backend.
- [x] release checklist now expects AR mode on non-ARCore devices.
- [x] project data docs describe versioned `sphereslam_reference_<uuid>.png` references and
  crash-safe swap semantics.
- [x] KPM public API comments describe hybrid + standalone roles.

Remaining:

- [ ] Update every stale comment that calls CameraX/homography the only "ARCore fallback".
- [ ] Update `file_descriptions.md` with standalone classes and ownership.
- [ ] Update `FEATURE_REFERENCE.md` with backend capability differences.
- [ ] Update `SLAM_SETUP.md` with standalone CameraX/KPM data flow.
- [ ] Update `NATIVE_ENGINE.md` once MobileGS standalone frame semantics are implemented.
- [ ] Document physical-scale UX when chosen.
- [ ] Document standalone tracking-state diagnostics and thresholds when finalized.
- [ ] Document co-op cross-backend semantics when implemented.
- [ ] Remove any obsolete statement that AR mode is hidden when ARCore is missing.

---

## 18. Code cleanup after correctness is proven

Do not do these before behavior is tested; they are architecture cleanup, not prerequisites for the
first standalone device validation.

- [ ] Make asynchronous `SphereSlamTracker` delegate through `SphereSlamEngine` instead of talking
  directly to `KpmBridge`.
- [ ] Consolidate duplicated KPM session/reference setup between hybrid and standalone wrappers.
- [ ] Promote a backend-neutral pose/tracking interface only after its actual hybrid/standalone
  consumers are stable.
- [ ] Rename homography-fallback renderer classes if they become shared standalone rendering
  infrastructure.
- [ ] Remove obsolete AR-unavailable onboarding resources only after confirming no other flow uses
  them.
- [ ] Remove dead ARCore-only fallback routing code after device tests prove the new backend
  selection.
- [ ] Keep native ownership/destruction in one abstraction and one thread per KPM session.
- [ ] Avoid a broad rewrite of `ArRenderer`; preserve the known-good ARCore path.

---

## 19. Merge criteria

Do **not** merge the standalone feature merely because it compiles.

Minimum merge criteria:

- [ ] branch rebased/current with `main`;
- [ ] unit/native/build CI green;
- [ ] ARCore regression smoke test passes;
- [ ] at least one real non-ARCore device can:
  - [ ] enter AR mode;
  - [ ] capture a target;
  - [ ] lock the artwork;
  - [ ] move the phone with stable registration;
  - [ ] lose and reacquire the target;
  - [ ] manipulate the artwork;
  - [ ] reopen the project and reacquire without recapture.
- [ ] no UI reports normalized standalone scale as physical metres;
- [ ] no known coordinate-frame mixing between KPM, ARCore, and MobileGS;
- [ ] release checklist reflects the behavior actually shipped;
- [ ] unresolved parity gaps are documented as explicit backend limitations, not silently broken
  controls.

Full parity milestone, after initial standalone merge:

- [ ] physical scale;
- [ ] MobileGS/fingerprint integration;
- [ ] wide-area/multi-page wall tracking;
- [ ] hybrid KPM correction through `PoseFusion`;
- [ ] deliberate equivalents/degradations for all ARCore-only features;
- [ ] co-op cross-backend calibration;
- [ ] sustained-session performance/thermal validation.

---

## Recommended implementation order

1. **Build/CI validation** — find compile/API errors before adding more behavior.
2. **Physical scale** — required before metric integration and trustworthy distance.
3. **Standalone target workflow parity** — remove the temporary separate capture UX.
4. **Standalone confidence/loss gates** — make the pose stream defensible.
5. **MobileGS standalone coordinate bridge + fingerprint generation**.
6. **MobileGS camera feed / paint-progress / self-grow**.
7. **Multi-page wall atlas growth**.
8. **ARCore-only feature/equivalent audit**.
9. **Co-op cross-backend calibration**.
10. **Hybrid KPM→PoseFusion correction** — independent enough to proceed in parallel once the page
    scale and coordinate transform are defined.
11. **Performance/device matrix**.
12. **Cleanup/refactor only after the behavior above is proven**.
