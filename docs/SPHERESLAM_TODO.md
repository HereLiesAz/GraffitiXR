# SphereSLAM Implementation TODO

## MobileGS sensor turn

- [x] A lost frame is turned by the phone's north-referenced attitude square-on to the wall and
  upright, matched there, and its matches mapped back for PnP (`sensorTurnHomography`), in both AR
  modes; no pose needed.
- [x] The wall's normal is learned at every well-supported lock, saved with the project, restored on
  load, and forgotten with a new target.
- [x] Close-ups: the fingerprint carries the capture photo's features at zooms 6×, 3× and 2× over the
  aim grid (`CloseUpFeatures`), at most as many as its own.

Device checks owed:

- [ ] Lose the lock, then look up at the wall from close below (40°+) with the phone rolled: the
  diagnostics show `turn` and it relocks.
- [ ] Reopen the project and look at the wall steeply: the first relocalization already turns.
- [ ] A magnetically noisy wall (rebar, metal): the turn still helps or stays out of the way.
- [ ] Lose the lock a hand's width from one corner of the target: it relocks (close-ups).
- [ ] Target capture still feels prompt with the close-ups (49 extra detections, background).

## SphereSLAM 0.26.6 — anticipation is the app's (adopted)

The app consumes SphereSLAM **0.26.6** (tag `0.26.6`). The library no longer chooses or renders
previews; it learns and searches the views the app hands it (`AnticipatedView`), and turns the live
frame by the sensors toward the nearest tile. The app's anticipation runs in every AR mode (standalone
through `PhotosphereTrackingSession`, ARCore through `SphereSlamTracker`), on the KPM page of the
fingerprint — the teleological layer's anticipated appearance of the wall.

- [x] Half-sphere views (`feature/ar/.../anticipation/HalfSphere.kt`): target at the core, capture view
  at the pole; meridians (lines of longitude; the `AnticipationSettings.latitude` field, a historical name) cut by tilt circles (lines of latitude; the `longitude` field), a view at the pole and every cut, none
  on the equator (defaults: six half-spheres, circles at 30° and 60° on six meridians); `distance` half-spheres from the capture distance in (zoom in only); every view at
  `roll` turns. Nearer half-spheres repeat over a grid of aim points across the target
  (`⌈distance / (shell + 1)⌉` per side), so a close-up of any part of the wall is anticipated, partial
  views (target overflowing the frame, part of it behind the camera) included.
- [x] Settings → Anticipation: distance, "Longitude" (how many meridians), "Latitude" (how many tilt circles), roll (the maximum) and how far battery,
  heat and the tracker's own load may cut it; applies at once.
- [x] Throttling (`AnticipationGovernor`): far half-spheres go first, outermost in; the nearest one's
  warps go last, and its aims never. Lowering builds nothing; relief waits 10 s.
- [x] Built off the camera thread, farthest first, four at a time (`AnticipationFeeder`).
- [x] Views from steeply above or below the wall (more than `RARE_VERTICAL_DEG` = 45° from its normal in the
  vertical plane) are built after all the others, farthest first among themselves; none are dropped. Sideways
  angles are not demoted. There is no canvas/floor-work signal in the app, so the demotion always applies;
  those views still get built, just last.
- [x] Fingerprint review: a highlighted mark touching the photo's edge is excluded automatically.

Device checks owed for 0.26.6:

- [ ] Lose the lock with the phone a hand's width from the wall, over a corner of the target: it
  relocks (`KPM_PREVIEW`).
- [ ] Defaults (754 views per page) build in the background with no visible frame time; a hot device
  drops the far half-spheres first.
- [ ] A Settings change to Anticipation changes the view count in the diagnostics at once.
- [ ] Fingerprint review never highlights a mark cut by the photo's edge.

## SphereSLAM 0.26.5 — steep relocks, previews (adopted; previews superseded by 0.26.6)

The app consumes SphereSLAM **0.26.5** (tag `0.26.5`). Nothing in the app changes: every new lever is
on by default in `KpmMatchSpeedConfig`, which the app leaves at the library's defaults.

- [x] A lost camera's oblique solve also starts from the matches' own homography, and after three
  direct misses every other full solve rectifies a guessed steep view (`KPM_RECTIFIED`).
- [x] Previews: each reference (and grown atlas page) is rendered tilted, resized and turned, and KPM
  learns each as another image of its page, in a second matcher built off the camera thread. Once lost,
  every other full solve searches the previews nearest the view the phone's rotation predicts, widening
  on each miss (`KPM_PREVIEW`; `matchMethod=KPM_PREVIEW` in the diagnostics dump).
- [x] `KpmBridge` loads the native library itself; a session no longer depends on an earlier
  `isAvailable()` call.

Device checks owed for 0.26.5 (the SphereSLAM test app grades most of these itself):

- [ ] Lose the lock, walk well to the side and look back at the wall at 50–70°: it relocks
  (`KPM_RECTIFIED` or `KPM_PREVIEW`).
- [ ] Lose the lock, step far back or close in, or turn the phone sideways: it relocks (`KPM_PREVIEW`).
- [ ] Preview building (≈ 40 images per page, background) costs no visible frame time or heat in a
  long session with atlas growth.

## SphereSLAM 0.26.4 — floors and ceilings (adopted)

The app consumes SphereSLAM **0.26.4** (tag `0.26.4`).

- [x] The tracking session's base map is gravity-level and north-referenced, the frame fingerprint
  placements use; a capture-time placement is refined on the first confirmed lock (and the app
  persists the refinement, `onMapFromFingerprintSolved`). Projects saved earlier carry a transform in
  their own session's frame: the first lock replaces it.
- [x] Fingerprint placement orients the page by the camera's image-up, live attitude, no ±89° clamp.
- [x] The app reads heading, elevation and roll from one `CameraAttitudeProvider.latestAttitude()`
  sample (`StandaloneCameraAttitude`) for keyframes and placement; separate getters could mix samples,
  which near a floor rotates the result by tens of degrees.
- [x] Standalone MiDaS treats each polar cap as one view tile.

Device checks owed for 0.26.4 (the SphereSLAM test app grades most of these itself):

- [ ] Capture a target looking straight down at a floor: it locks, and recaptures land the same way.
- [ ] Start a session looking at the floor, then turn to the wall: the artwork stays placed.

## SphereSLAM 0.26.3 — lens distortion, tracker refactor (adopted)

- [x] Standalone tracking requests Camera2 distortion correction (FAST, else HIGH_QUALITY, API 28+)
  through `LensDistortion` in `StandaloneCameraTuning`; the HAL then delivers the zero-distortion
  pinhole image KPM assumes. The camera diagnostic line reports `lens=correction=…`, or the
  uncorrected state.
- [x] `SphereSlamTracker` drives `SphereSlamEngine` (library-side; no app change).

Device checks owed for 0.26.3:

- [ ] The camera diagnostic line reads `lens=correction=FAST` (or `HIGH_QUALITY`) on a phone that
  offers it; the artwork stays registered at the frame edges.

## SphereSLAM 0.26.2 — full-frame rectification, reference scale (adopted)

No app code changes.

- [x] The oblique virtual view is rendered from the full 640×480 analysis frame, not its 320×240
  matcher copy: the foreshortened side of the wall keeps the camera's detail.
- [x] The standalone fingerprint stays saved at the camera still's full resolution; KPM registers it
  from an area-downscaled copy (long side ≤ 3 × the matcher's, 960 px), which puts the wall back
  inside KPM's scale range. Page geometry is unchanged.

Device checks owed for 0.26.2:

- [ ] A target captured at full resolution locks as readily from 1 m as from 4 m.

## SphereSLAM 0.26.0 — oblique rectification, anchor tiles (adopted)

Both library-side and on by default.

- [x] Extreme perspectives: when the predicted view is at least 30° off the wall's normal, the full
  KPM solve matches a virtual frontal re-rendering of the frame and solves the real pose from it
  (`KpmMatchSpeedConfig.obliqueRectificationDeg`). Diagnostics report `matchMethod=KPM_RECTIFIED`.
- [x] Anchor tiles: the tiles around a station's first lock (24°) are never evicted from the
  photosphere, so the gyro bridge has its nearest references when the wall swings back into view.

Device checks owed for 0.26.0:

- [ ] Lock square-on, then walk sideways to 60–70° off the wall: the artwork stays on the wall and
  `matchMethod` reads `KPM_RECTIFIED`.
- [ ] Work away from the wall for minutes, swing back: the artwork re-places from the anchor tiles.

The camera analysis stream stays at CameraX's default (640×480, matched at 320×240) by decision: a
higher resolution costs KPM, flow and depth time on the very phones that run this path (no ARCore).
0.26.2 rectifies from the full frame instead.

## SphereSLAM 0.25.3 — full-sphere photosphere, per-tile scan progress (adopted)

The app consumes SphereSLAM **0.25.3** (tag `0.25.3`). It is the 0.25.1 code; 0.25.1/0.25.2 never
published on JitPack (its image dropped Ninja), and 0.25.3 pins Ninja in `jitpack.yml`.

- [x] `PhotosphereMap()` defaults to the whole sphere (24 × 6 tiles of 15° × 30°, every heading,
  zenith to nadir); the app's explicit full-sphere config is gone.
- [x] Keyframes advance each tile's scan progress (`recordView`); a tile becomes current at 90 %
  covered. `ArViewModel.sphereRegionsInProgress` carries `regionsInProgress()` to the glow
  (`CoverageGlowConfig.progress` → `CoverageGlowRenderer.setRegionProgress`): a tile grows more
  opaque as it fills, then opens to the camera. Every glow style; library default peak opacity.
- [x] `firstCaptureForTile` now means "the axis tile's scan completed"; the diagnostic reads
  `photosphere tile scanned`.
- [x] Replacing a standalone target keeps the previous one until native KPM accepts the new one:
  `MainViewModel.onConfirmStandaloneTargetCreation` commits the candidate but holds the previous
  page/atlas files as a pending rollback; the overlay reports the verdict
  (`onPersistedReferenceRegistered` → previous files deleted; `onPersistedReferenceRejected` /
  unreadable → previous project fields restored, candidate deleted, the overlay swaps back).
- [x] The default haze colour is hot pink (`CoverageGlowColor.DEFAULT`); a colour already picked in
  Settings is kept.

Device checks owed for 0.25.3:

- [ ] Sweep slowly: a partly seen tile turns more opaque step by step, then clears once scanned.
- [ ] Ceiling and floor tiles scan and clear like the rest.

## SphereSLAM 0.25.0 — photosphere localization, held pose, stations, tile depth (adopted)

The app consumes SphereSLAM **0.25.0** (tag `0.25.0`).

- [x] The artwork no longer vanishes when the wall leaves view: past the 400 ms bridge SphereSLAM
  returns `PoseSource.PHOTOSPHERE` (gyro rotation corrected by recognised photosphere tiles, at the
  latest camera centre) or `HELD` (gyro-carried last lock); the app maps them to
  `SphereSlamStandalonePoseSource.PHOTOSPHERE` / `HELD` and renders them, also while a relock is
  confirming.
- [x] Stations: stepping more than 0.3 m (metric reference) / 0.3 reference widths from every
  standpoint opens a new photosphere; earlier ones are kept and cross-station tiles are triangulated
  for 6-DoF fixes. All library-side; nothing in the app.
- [x] MiDaS per new tile: the analyzer passes its `DepthEstimator` as the session's
  `TileDepthSource` (synchronized; shared with the existing depth worker).
- [x] Tile localization needs OpenCV, which the analyzer already loads (flow tracking).

Device checks owed for 0.25.0:

- [ ] Turn away from the wall and back: the artwork stays where the wall is (diagnostics:
  `poseSource=PHOTOSPHERE`, `tiles{… rotFixes>0}` in the periodic KPM line) and relocks without a jump.
- [ ] Step sideways 0.5 m and look around: `SphereSLAM station 1 opened`; with the wall out of view the
  artwork stays placed (`poseFixes>0` once tiles from two stations overlap, or with MiDaS depth).
- [ ] Analyzer frame rate unchanged with tile localization running (it is a 4 Hz background job).

## SphereSLAM 0.24.6 — seen tiles persist, KPM gates restored (adopted)

The app consumes SphereSLAM **0.24.6** (tag `0.24.6`; 0.24.4 audit fixes plus HereLiesAz/SphereSLAM#33).
It compiles unchanged; what changed underneath:

- [x] Every tile a keyframe's frame covers (≥ 60 % in frame) is marked current, not only the tile
  under the view axis: scanned areas no longer glow again once they leave the screen.
- [x] KPM reprojection error is back in the matcher's (downscaled) pixels. 0.24.4 had reported it
  ×factor² against unchanged gates — acceptance and atlas growth 4× stricter, so the atlas barely
  grew and relocks were rejected.
- [x] Grown atlas pages are kept by the app when the page set is re-sent
  (`withGrownAtlasPages`); SphereSLAM rebuilds from exactly the set it is given.
- [x] `REFERENCE_TOO_WEAK` no longer kills the session (only the precision layer halts;
  `isRecoverable`). The app still stops feeding the analyzer on any fatal and restarts it on
  restore/recapture (`standaloneFatalRecovery`), which stays correct.
- [x] libc++ linked statically in the AARs; `app/build.gradle.kts` keeps its
  `pickFirsts += "**/libc++_shared.so"` for the other native dependencies.
- [x] `superpoint.onnx` in `:models` is byte-identical to the app's `core/nativebridge` asset.

- [x] ~~The artwork is held by the gyro bridge for only 400 ms (`maxBridgeMs`), then hidden until a
  visual relock.~~ Superseded by 0.25.0: past the bridge SphereSLAM returns `PoseSource.PHOTOSPHERE`
  / `HELD` and the app renders them (see the 0.25.0 section above).

Device checks owed for 0.24.6:

- [ ] Pan across the wall and back: the area seen stays clear of the glow (edges aside).
- [ ] Atlas growth lines (`atlas page persisted`) appear while panning; relock on return is quick.
- [ ] Swapping the wall target re-confirms before `LOCKED`; no overlay swing-back after a bridge.

## SphereSLAM 0.24.3 — display-rate glow and artwork, faster tracking, GPU depth (app half)

The app now consumes SphereSLAM **0.24.3** (tag `0.24.3`). The app-facing API is additive; the
wiring follows the library README's *App integration* walkthrough.

Adopted:

- [x] **One attitude sensor.** `CameraAttitudeProvider` (`TYPE_ROTATION_VECTOR`, East-North-Up — the
  sensor that already stamped keyframe heading/elevation/roll) now samples at `SENSOR_DELAY_GAME`
  instead of the ~16 Hz UI default. Its `attitudeExtrapolator`
  (`ArViewModel.standaloneAttitudeExtrapolator`) drives the glow and the artwork prediction and,
  with a `REALTIME` camera timestamp source, gives the session each frame's attitude AT its capture
  time (`StandaloneFrameAttitude.atCapture` → `quaternionAt(frame timestamp)`; the prediction anchor
  and bridge reference the library asks for). Otherwise `Frame.attitude` is its `latestSample()`
  (`ArViewModel.latestStandaloneAttitudeSample`). MainScreen wires both only when the device has
  the rotation-vector sensor (`ArViewModel.isStandaloneAttitudeSensorAvailable`); without it the
  analyzer's own `GameRotationAttitudeSource` supplies `Frame.attitude` (base pose + gyro bridge)
  and no pose prediction runs (a predictor must read the same sensor as `Frame.attitude`). The
  sensor is chosen once per analyzer, never per frame.
  Atlas-growth keyframe orientations are stamped with the proposing frame's attitude timestamp,
  not the adoption time.
  **Behaviour change:** the ≤ 400 ms gyro bridge and the rotation-only base photosphere pose now
  integrate the magnetometer-fused rotation vector instead of the game rotation vector.
- [x] **Display-rate, full-rotation coverage glow.** `HomographyOverlayRenderer` sets the embedded
  `CoverageGlowRenderer.rotationSource` to `attitudeExtrapolator.worldFromCameraAt(t,
  cameraFromDeviceForDisplayFrame(displayRotation), out)` and draws with
  `drawEmbedded(elapsedRealtimeNanos)`; the lens is `Lens.fromIntrinsics(CameraFillCenter.intrinsics(frame,
  viewW, viewH))`, rebuilt on the GL thread when the analysis-frame intrinsics (calibration callback,
  keyframes, tracked frames) or the surface size change; the display rotation follows a
  `DisplayListener`. `SphereTileGlowMask` no longer projects anything: it hands the regions over
  (`setRegions`) only when the map changes (keyframe / entry), so the per-tracked-frame projection in
  `consumeTrackedFrame` and the deprecated heading/elevation `projectRegions` are gone. Expected to
  fix (by construction; NOT yet verified on a device — see the device checks below): glow rotating
  against the preview by the roll angle, vanishing near the poles, lagging 10–20° on fast pans,
  freezing while untracked. Colour, intensity, style and geometry still travel
  as one `CoverageGlowConfig` applied on the GL thread between draws (settings apply immediately,
  mode and geometry never tear).
- [x] **Two new glow styles** in Settings ▸ coverage glow: *Edge glow* (`OUTLINE`, storage key
  `outline`) and *Wash + edge* (`COMPLEMENT_OUTLINED`, `wash_outlined`), labels in every locale. The
  edge alpha is `(intensity / 0.6)^0.477`, so the whole 0–60 % slider spans the edge's 0–1 (14 % wash
  → 0.50, the library's default edge; 0 still means off; a linear `3.5 ×` saturated at ≈ 29 %), the
  edge width 10 dp (`CoverageGlowStyleMapping.outlineAlphaFor` / `outlineWidthPxFor`).
- [x] **Display-rate artwork pose.** The standalone session publishes every rendered pose to a
  `DisplayPosePredictor` (`session.displayPosePredictor`); each GL draw re-poses the design quad with
  `predictInto(attitudeExtrapolator, now + ARTWORK_DRAW_LEAD_NS, out)` (lead 0 until the preview
  latency is measured; `rendering/ArtworkPosePrediction`, wired through the GL-free
  `rendering/StandaloneAttitudeWiring`), falling back to the last pushed pose when nothing is held.
  Cleared on loss (by the session on a pose-less frame, and by `clearPose` / dispose). Co-op peer
  tracking and phones without a rotation-vector sensor keep the analyzer-rate pose.
- [x] **Pooled luma.** `SphereSlamStandaloneTrackingAnalyzer` and `CoopPeerFingerprintAnalyzer`
  pack the Y plane through `LumaFrameBufferPool(slots = 2)`. Reuse contract checked: the session
  reads `Frame.luma` without retaining it, MobileGS/peer feeds copy it into a direct buffer, depth
  copies a small grid, and
  keyframes/atlas pages are library copies. The pack time is reported as `Frame.convertDurationNs`.
- [x] **Depth on LiteRT.** `:models` `DepthEstimator` runs MiDaS on the LiteRT GPU delegate with an
  XNNPACK CPU fallback. The depth worker now calls `estimateLuma` on its luma grid (no ARGB `Bitmap`
  round trip); the self-test diagnostic reports `backend=`. The unused `litert = "2.2.0"` catalog
  entry is removed (LiteRT 2.x drops the delegate API `litert-gpu` 1.4.2 needs). The app's ONNX
  Runtime keep rules are removed: the `:models` AAR's consumer rules keep `ai.onnxruntime.**` and
  `org.tensorflow.lite.**` (verified in the 0.24.3 AAR's `proguard.txt`).
- [x] **OpenCV before the session.** The analyzer calls the idempotent `NativeLibLoader.loadAll()`
  before constructing the session (the application already loads it); without `opencv_java5` the
  session's flow tracking silently turns itself off.
- [x] **Diagnostics.** The dump adds `matchMethod=` (`KPM_FULL` / `KPM_FOCUSED` / `FLOW`, KPM poses
  only) and `kpmMetrics=` (the session's periodic `KPM ... method= timing{frames matched convertMs
  setupMs matchMs keyframeMs growthMs}` line, which also lands in the diag log as before).
- [x] **Behaviour changes handled.** Until the background matcher build lands, frames carry no pose
  (`FrameResult.pose == null`) and no failure: the analyzer just hides the artwork and keeps every
  UI/MobileGS state; the HUD says "Preparing fingerprint target…" instead of asking for a new target.
  A too-weak reference now fails on the frame after its build — the same `onFatalError` →
  `StandaloneReferenceTooWeakException` path. Atlas growth that throws is a diagnostic now, not fatal.
  A fatal analyzer never shows "Preparing…": the status pill shows the real failure
  (`standaloneInitializingStatusText`).
- [x] **Recovery from a fatal analyzer.** The session's FATAL is terminal, so recovery recreates the
  analyzer (`analyzerRestartGeneration`, a key of the analyzer effect; decisions in
  `StandaloneAnalyzerRecovery.kt`): a too-weak REPLACEMENT target restores the previous validated
  target and restarts at once; "Recapture Target" after any fatal error restarts too. The base
  photosphere tiles/keyframes live in `ArViewModel` (heading/elevation keyed) and survive; the new
  session re-seeds its rotation-only map frame from its first attitude and re-solves
  `map_from_fingerprint` on its first lock, as on any pause/resume.
- [x] **Battery cap vs. Device default.** Standalone keeps *Device default* unpinned under battery
  pressure (`standaloneEffectiveCameraTargetFps`); the cap only lowers explicit 60 / max rates to 30.
  The ARCore camera config keeps `effectiveCameraTargetFps` (30 under pressure).
- [x] **Dead homography Overlay tracker removed.** `HomographyFallbackOverlay`,
  `HomographyTrackingAnalyzer` (+ test) and `BridgedHomographyTracker` had no caller (and the shared
  renderer would never have drawn their pose: it fails closed without `map_from_fingerprint`).

Device checks owed for 0.24.3:

- [ ] Glow stays glued to the preview while rolling the phone, panning fast (no lag, no 10–20°
  trail), looking at ceiling/floor (no pole dropout) and while untracked (keeps moving).
- [ ] Each glow style (Wash, Missing tiles only, Edge glow, Wash + edge) looks right and switches
  live; colour and intensity sliders apply at once; the edge width reads well on a high-density phone.
- [ ] Rotate the device (incl. a 180° flip with auto-rotate on): glow and artwork stay registered.
- [ ] Artwork follows quick rotations at display rate between analyzer frames and does not jitter
  or drift when held still; on loss it disappears (no artwork re-posed from a stale lock).
- [ ] Gyro bridge (hand over the lens for < 0.4 s) near steel/rebar: no visible yaw jump from the
  now magnetometer-fused attitude; re-lock lands where it should.
- [ ] Target capture / restore: "Preparing fingerprint target…" shows briefly, then lock; a weak
  target is still rejected with the KPM-feature message; a weak REPLACEMENT target restores the
  previous one and tracking resumes (analyzer restart) without leaving the AR screen.
- [ ] Phone without `TYPE_ROTATION_VECTOR` (or with it disabled): frame attitude and the gyro bridge
  come from the game rotation vector; no artwork prediction, glow stays an unprojected wash.
- [ ] Copy Diagnostics: `matchMethod` shows `FLOW` between full solves (proves `opencv_java5` is
  loaded) and `kpmMetrics` carries the timing window; tracking frame rate is visibly higher.
- [ ] Depth self-test line reports `backend=GPU` on a GPU-capable phone (CPU fallback logged as an
  error elsewhere); feature-map depth still calibrates radial placement.
- [ ] Release (R8) build: depth self-test runs (LiteRT kept by the `:models` rules) and an ORT
  failure path stays catchable.


## SphereSLAM 0.24.1 — second consolidation (app half)

The app now consumes SphereSLAM **0.24.1** (tag `0.24.1`, library PR #29). The library absorbed the
pieces 0.24.0 deferred; the app copies are deleted and the app keeps thin Android/UI adapters.

Moved (app copy deleted → library API):

- [x] `rendering/CameraFillCenter` → `camera.CameraFillCenter` (identical math; the library adds
  `frameToViewNormalized`). Callers: `HomographyOverlayRenderer`, `OverlayGyroStabilizer`,
  `SphereSlamStandaloneOverlay`, `StandaloneGestureGeometry`, `ArViewModel`.
- [x] The standalone analyzer's runtime → `:reloc` `PhotosphereTrackingSession` (base photosphere
  pose + keyframes, planar KPM layer through `RobustTrackingLoop`, same-frame
  `map_from_fingerprint`, ≤ 400 ms gyro bridge, intrinsics-change rebuild, `unitsPerPixel`, atlas
  growth). `StandaloneAtlasGrowth` → `PhotosphereAtlasGrowth` (pure-Kotlin rectification instead of
  OpenCV/Bitmap); `StandaloneTargetQuality` / `StandaloneFailure` bodies → `TargetQuality` /
  `TrackingFailure*` (typealiased; the artist-facing messages stay in the app).
  `SphereSlamStandaloneTrackingAnalyzer` remains as the adapter: CameraX luma/intrinsics, Camera2
  sensor orientation and timestamp source, attitude, MobileGS seeding/feeding and reloc
  corroboration (`correctAcceptedPose`), MiDaS depth push, UI callbacks.
- [x] Native `HomographyTracker` (C++, its JNI entry points, CMake entries and host test),
  `HomographyTrackerNative`, `HomographyArTracker` → `:reloc` `PlanarTracker` (same ORB / Lowe /
  `solvePnPRansac` + IPPE algorithm and gates, on the OpenCV Java `Relocalizer`).
  (The `BridgedHomographyTracker` / `HomographyTrackingAnalyzer` adapters that used it were removed
  with the dead homography Overlay tracker in the 0.24.3 follow-up.)
- [x] `anchor/PlaneMarks`, `anchor/MetricMarks`, `anchor/Triangulation` → `:reloc` `MetricMarks`
  (`backProjectToPlane`, `triangulateTwoView` through `TileTriangulator`, `glViewToCvDisplay`,
  `glViewDisplayOriented`). The capture-path tests now run against the library.

Left in place, deliberately:

- Native MobileGS `computeRectifyHomography`, `growMapFromReloc` and the depth-calibrated radial
  placement. The library's `RectifyHomography` / `WallFeatureMap` / `RadialDepthPlacement` are
  pure-Kotlin JVM objects; MobileGS runs these inside its native reloc pass on the reloc thread, on
  native map state (`mMapPoints3D`, `mMapDescriptors`, …) under `mMutex`. Switching would mean moving
  the feature map and the reloc pass out of C++ (or a JNI upcall per reloc attempt), not swapping a
  call. The calibration itself now matches the library: `include/DepthScaleFit.h` transliterates
  the robust `DepthScaleFit` (it was plain least squares), pinned by `DepthScaleFitTest`. The
  native rectify sign now matches the library
  (`R + t·nᵀ/d`; it used `R − t·nᵀ/d`, wrong whenever the capture and live cameras translate).
- `core/common` `model/WallFeatureMap` (the persisted `@Serializable` project model) and
  `SlamManager.getWallFeatureMap`'s blob parser: a data model and native-blob reader, not the
  library's runtime map; `:core:nativebridge` does not depend on SphereSLAM.

Device verification still owed for this move:

- [ ] Standalone (non-ARCore) phone: lock, brief hand-over-lens bridge, re-lock, atlas growth and
  `map_from_fingerprint` behave as before; diagnostics show the same state/failure lines.
- [ ] Overlay planar tracker (if remounted): reference capture accepts/rejects as before, lock and
  gyro bridge hold the design.

## SphereSLAM 0.24.0 — generic SLAM code moved into the library (app half)

The app now consumes SphereSLAM **0.24.0** (tag `0.24.0`, library PR #28) across `:sphereslam`,
`:overlay`, `:reloc` and `:models`. The library absorbed GraffitiXR's generic SLAM code; the app
copies are deleted and replaced by the library API. Behavior is otherwise unchanged except for the
listed fixes.

Moved (app copy deleted → library API):

- [x] `anchor/PoseMath`, `util/RotationDeltaMath`, `MetricMarks.glViewToCv` →
  `math.RigidMath` / `math.RotationMath`. App-only display-orientation helpers
  (`MetricMarks.glViewToCvDisplay`, `glViewDisplayOriented`) stay.
- [x] `core/common` `CameraIntrinsicsEstimator`, `StandaloneCameraCalibration`,
  `anchor/CaptureRotation`, `LumaFrameTransform`, `rendering/ProjectionMatrix`, Gyro screen
  intrinsics → `camera.*`. `core/common` `sensor/CameraIntrinsics` is a typealias of
  `camera.CameraIntrinsics` (kept for the `SensorSource` vocabulary).
- [x] `GyroOrientationBridge` + `rotateViewPoseKeepingCameraCenter` → `attitude.GameRotationAttitudeSource`,
  `AttitudeRotationBridge`, `DeviceCameraRotation`, `RotationMath.rotateAboutCameraCentre`
  (standalone analyzer, base photosphere pose, Overlay ▸ Gyro).
- [x] `pose/SphereSlamRuntimeProbe` → `SphereSlam.isOperational()`.
- [x] `anchor/HybridPoseHistory`, `HybridKpmCorrection` (incl. its `solveRaw` test seam),
  `HybridPageFrame`, `HybridMetricKpmReference` → `sidecar.*` (`MetricPageRectification`);
  tests use `SphereSlamTracker.Observation.forTesting`. `PoseFusion` keeps the ARCore backbone and
  MobileGS reloc path and delegates its hybrid path to `sidecar.HybridAnchorFusion`.
  `core/common` `HybridKpmDiagnostics`/`HybridKpmOutcome` are typealiases of the sidecar records.
  `HybridKpmPage` persistence stays in the app.
- [x] ArViewModel keyframe map + `buildStandaloneFingerprintPlacement`/`signedAngleDegrees` +
  `anchor/StandaloneFingerprintFrame` → `PhotosphereKeyframeStore`, `PhotosphereKeyframe`,
  `PhotosphereFingerprintFrame` (`placement`, `cameraFromMap`, `referencePixelToWall`).
  `StandalonePhotosphereCapture` keeps only the blocker reasons and artist-facing messages.
- [x] `util/OverlayGyroCompensationMath` → `gyro.GyroCompensationMath` (`OverlayGyroStabilizer`
  Compose wiring stays).
- [x] `SphereTileGlowMask` scissor wash + `HomographyOverlayRenderer.setCoverageGlow` →
  `:overlay` `CoverageGlowRenderer` (`FillMode.COMPLEMENT`, white, α 0.14) fed
  `CoverageGlowProjection.projectRegions(PhotosphereMap.currentRegions(), …)` and drawn with
  `drawEmbedded()` inside the artwork renderer before the design (glow beneath the artwork, camera
  visible; `setEGLConfigChooser(8, 8, 8, 8, 16, 0)` unchanged).
- [x] `depth/DepthEstimator` → `:models` `DepthEstimator`; the duplicate
  `core/nativebridge/src/main/assets/midas_v21_small_256_int8.onnx` is removed. `superpoint.onnx`
  and `zerodce.onnx` (and `distortion_head.onnx`) stay: `GraffitiJNI.cpp` loads them through
  `AAssetManager`; the `:models` AAR's copies are byte-identical.

Fixes picked up with the move:

- [x] Device→camera rotation for the gyro bridge and the base photosphere pose is
  `DeviceCameraRotation.cameraFromDevice(SENSOR_ORIENTATION, rotationDegrees)` (= display rotation
  for the display-upright tracked image). The app conjugated by `R_z(rotationDegrees)`, wrong for
  any sensor not mounted at 0° (a 90° rear sensor in portrait passed 90° where 0° is right).
- [x] `SphereSlamStandaloneTrackingAnalyzer.fuseWithReloc` corroborates/blends two VIEW matrices
  with `:reloc` `PoseBlend` (camera-centre divergence + camera-centre lerp) instead of PoseFusion's
  model-matrix translation-column helpers.
- [x] Fingerprint placement rotates the tapped pixel's ray through heading, elevation **and roll**
  (`PhotosphereMap.directionOfPixel`) instead of adding `atan` offsets to heading/elevation; the
  bound tile is the tapped direction's tile.
- [x] Coverage-wash holes are the current tiles' projected extents, not screen-aligned rectangles.
- [x] Camera2 intrinsics for an aspect-changing stream are a centred crop + uniform scale (library
  estimator) instead of independent x/y scaling.

Deferred (deliberately NOT moved): the standalone photosphere runtime/atlas growth
(`StandaloneAtlasGrowth`, the analyzer's map-frame bookkeeping), the native MobileGS relocalizer, and
`HomographyTracker`. (The runtime, atlas growth and `HomographyTracker` moved in 0.24.1,
above; the native MobileGS relocalizer stays.)

Device verification still owed for this move:

- [ ] Standalone (non-ARCore) phone, portrait AND both landscapes: a brief hand-over-lens dropout
  bridges the artwork the same way the phone turns (the device→camera fix), and the photosphere
  coverage holes line up with the scanned directions.
- [ ] Fingerprint capture with the phone rolled (and in landscape) lands the target where tapped.
- [ ] Coverage wash: camera preview visible, wash beneath the artwork, holes over scanned tiles.
- [ ] Overlay ▸ Gyro still holds the design on a tripod and auto-releases past 3°.
- [ ] Hybrid (ARCore + KPM sidecar): metric page capture/restore still arms, a large correction
  still waits for three agreeing observations; mixed MobileGS-reloc + KPM sessions behave.
- [ ] MiDaS loads from the `:models` asset (diagnostic "depth: …" line) with the feature-map flag on.

## SphereSLAM 0.23.5 library API migration — no-regression contract (historical)

The app consumed SphereSLAM **0.23.5** across `:sphereslam`, `:overlay`, and `:reloc` at this
point (superseded by 0.24.0 above).
This is an API-surface migration, **not** a feature consolidation. The following behavior must remain
true throughout and after the migration:

- [x] ARCore-capable devices keep ARCore as the primary continuous pose source.
- [x] Hybrid SphereSLAM remains a sidecar wall/relocalization correction input through
  `HybridKpmCorrection -> PoseFusion`; it never replaces ARCore view/projection matrices.
- [x] ARCore-unavailable devices keep the CameraX + synchronous `SphereSlamStandaloneSession`
  backend.
- [x] Standalone KPM still feeds the same-frame accepted raw pose to MobileGS and keeps proprietary
  corroboration/fusion layered on top.
- [x] Rotation-only IMU bridging remains bounded and does not invent translation.
- [x] Multi-page canonical-wall atlas growth, persistence, restore, and anchor-generation semantics
  remain unchanged.
- [x] Physical-vs-normalized scale semantics remain unchanged.
- [x] Coverage/glow and camera-attitude behavior remain available.
  Camera attitude sampling is started by the standalone AR composition (it was previously never
  started, which silently disabled photosphere keyframes, the glow cut-outs, and every standalone
  fingerprint placement).
- [x] Co-op protocol-v3 backend/scale/wall-frame contracts remain unchanged.
- [x] Existing fail-closed ARCore-only feature boundaries remain enforced.
- [x] GraffitiXR's public `Standalone*` vocabulary remains available through public aliases to the
  library reloc types.
- [x] Existing hybrid correction tests remain; hidden SphereSLAM test constructors are replaced by a
  GraffitiXR-owned raw-value test seam rather than deleting coverage.
- [x] `check_sphereslam_architecture.py` continues to enforce the dual-backend behavior.
- [x] `check_sphereslam_build_contract.py` now enforces all three artifacts at 0.23.5, public-only
  API usage, reloc opt-in/transitivity, and supported ABI packaging.

The 0.23.5 naming changes used by GraffitiXR are:

- `SphereSlamStandaloneSession.Pose.cameraFromCanonical` instead of the deprecated `viewMatrix`
  property when reading a library pose.
- `SphereSlamTracker.Observation.cameraFromPage3x4` instead of deprecated
  `pageToCamera3x4`.
- production code consumes library-owned `SphereSlamTracker.Observation` values; GraffitiXR no
  longer constructs that now-internal observation constructor in tests.
- the old internal `SphereSlam.smokeTest` diagnostic is replaced by
  `SphereSlamRuntimeProbe`, which uses only public API while preserving the stronger fail-closed
  check: native availability plus calibrated engine creation, nonzero/ready session, and teardown.
  (0.24.0: the library ships this probe as `SphereSlam.isOperational()`; the app copy is gone.)

No existing tracking, persistence, fusion, atlas, co-op, or UI capability is intentionally removed
by this migration.

---

Status: active implementation + validation plan. Standalone, MobileGS frame integration,
multi-page atlas, ARCore-equivalent audit, and co-op protocol-v3 calibration are on `main`;
hybrid metric KPM→PoseFusion correction is implemented on
`feat/sphereslam-hybrid-posefusion` / PR #1970.

Checklist refreshed against `main` after co-op repair PR #1968 and the current hybrid branch.

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
- [x] Compile the native `:core:nativebridge` target for every release ABI. Android CI release
  packaging verified `libgraffitixr.so` plus the KPM JNI symbols in both `arm64-v8a` and
  `armeabi-v7a`.
- [x] Build at least one debug APK containing artoolkitX KPM. `assembleDebug` (2026-10-08) produced
  a debug APK that `check_sphereslam_apk.py --variant debug` confirms carries the real (non-stub)
  KPM native library, JNI symbols and preserved API classes for arm64-v8a and armeabi-v7a.
- [x] Build the normal release artifact(s): Android CI `release-build` completed
  `assembleRelease` successfully with the real artoolkitX submodule initialized.
- [x] Inspect the merged manifest and confirm (enforced by `check_sphereslam_manifest.py` in the
  successful release-build job):
  - [x] `android.hardware.camera.ar` remains `required="false"`;
  - [x] `com.google.ar.core` remains optional;
  - [x] no new required hardware feature excludes non-ARCore devices.
- [x] Confirm ProGuard/R8 does not strip the KPM JNI entry points or standalone classes:
  `check_sphereslam_apk.py --variant release` passed against the shrunk release APK and found all
  required JNI symbols plus the preserved KPM/SphereSLAM DEX descriptors.
- [x] Confirm no duplicate native symbol/source issue was introduced by the explicit artoolkitX AR
  source list. Moot in its original form: artoolkitX/KPM now arrives prebuilt in the SphereSLAM
  0.23.5 artifacts and `core/nativebridge/src/main/cpp/CMakeLists.txt` compiles no artoolkitX
  sources. Both the debug link (2026-10-08, local) and release link (CI) succeed, which a duplicate
  strong symbol would fail.
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
individual unresolved symbols. Android CI subsequently completed `assembleRelease`, linked the final
`libgraffitixr.so`, verified KPM JNI symbols in both supported ARM ABIs, verified the shrunk DEX
classes, and passed the non-ARCore merged-manifest check. The remaining unchecked build items are the
debug-APK packaging run and the explicit duplicate-source/static-contract execution.

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
  - [x] front camera is not a supported standalone path; standalone AR explicitly forces
    `CameraSelector.DEFAULT_BACK_CAMERA` while active and restores the shared controller selector
    on exit.
- [x] Prevent CameraX crop/zoom from invalidating the intrinsics used by KPM for the supported
  standalone path: apply ImageProxy crop explicitly and lock camera zoom at 1x while tracking.
  - [x] Apply `ImageProxy.cropRect` to the luma pixels before rotation.
  - [x] Shift `cx/cy` by the crop origin before rotating intrinsics.
  - [x] Prevent CameraX digital zoom from changing standalone calibration: standalone AR disables
    CameraController pinch-to-zoom and resets the camera to 1x while active, restoring the shared
    controller's previous zoom behavior on exit.
- [x] If CameraX applies an ImageProxy crop region, incorporate that crop into `fx/fy/cx/cy`.
- [x] Verify lens distortion is acceptable with the current zero-distortion KPM camera model. Where the
  camera can correct (API 28+), standalone requests correction, making the model exact (0.26.3);
  elsewhere the diagnostic line reports the coefficients' state.
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

- [x] Integrate standalone target capture into the normal AR target workflow/state machine instead of
  maintaining a separate minimal capture UI: one `TargetCreationUi` for both backends, branching at
  Confirm on `SphereSlamRuntimeMode.STANDALONE` → `MainViewModel.onConfirmStandaloneTargetCreation`
  (`MainActivity`, `sharedTargetCapture = true` in `MainScreen`).
- [x] Reuse the same capture/review language and error states where the concepts are equivalent
  (same UI and strings; standalone-only failures are toasts and `StandalonePhotosphereCapture.Blocker`).
- [x] Define what standalone does for ARCore-only target steps:
  - [x] plane hit test → the tap ray into the photosphere;
  - [x] depth sample → tile range and MiDaS `TileDepthSource`;
  - [x] ARCore anchor creation → `mapFromFingerprint` (`PhotosphereFingerprintFrame.placement`);
  - [x] point cloud/plane coaching → the ARCore tap hint is suppressed.
- [x] Hide or replace only the truly unavailable steps, not the entire workflow.
- [x] Add target-quality checks before accepting a KPM page:
  - [x] minimum image dimensions/pixel count;
  - [x] minimum native-generated KPM feature count (16 initial conservative floor);
  - [x] texture/contrast threshold using luma standard deviation;
  - [x] blur threshold using Laplacian variance;
  - [x] excessive over/under-exposure warning using clipped-pixel fraction.
- [x] Provide useful user-facing reasons for small/flat/blurry captures and too few native KPM
  features.
- [x] Provide user-facing reasons when a capture cannot bind to the base photosphere (no rotation
  sensor, attitude not ready, nothing mapped yet) instead of failing silently at Confirm.
- [ ] Device-check: fresh standalone AR entry logs `camera attitude sampling started`, then
  `photosphere tile scanned`, status `mapping=true`, and a capture logs
  `fingerprint capture bound to photosphere tile=`.
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
  a competing recapture wins without being deleted, an uncommitted candidate is cleaned up, and
  cancellation delivered immediately after metadata commit reconciles current/on-disk state before
  deciding which PNG is safe to delete.
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
- [x] If not, add a true frame-to-frame visual/inertial tracker behind the same standalone pose
  interface rather than extending IMU bridge duration: SphereSLAM's flow tracking (forward–backward
  Lucas–Kanade + PnP against page coordinates, no chained drift) between full KPM solves, on by
  default (`KpmMatchSpeedConfig.flowTracking`); the IMU side is the display-rate predictor and gyro
  bridge, not a tightly fused VIO.
- [x] With that continuous tracker:
  - [x] timestamp-align camera frames and IMU samples (`StandaloneFrameAttitude`: attitude at the
    frame timestamp on REALTIME sources);
  - [x] define initialization/reset behavior (`FlowTracker.seed` / `reset`);
  - [x] define KPM relocalization correction into that tracker (each full solve re-seeds flow);
  - [x] keep KPM page recognition independent of frame-to-frame VIO state (full solves search the
    atlas on their own schedule; FLOW poses never grow it).

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

- [x] Verify every AR rail/control against backend capability:
  - [x] Light, Lock, Magic, design controls, and project/library/settings actions use shared state and
    remain available on standalone.
  - [x] legacy ARCore Target is disabled on standalone with “Use the on-screen Wall Target capture”.
  - [x] Co-op Host/Join are enabled through the protocol-v3 host wall-frame contract; incompatible
    cross-backend/scale pairs fail closed, and Leave remains reachable for an active session.
  - [x] AR mode preview Export is disabled on standalone with an explicit reason until CameraX +
    transparent-GL compositing is implemented, rather than silently exporting layers-only.
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
- [x] Fill the screen: CameraX preview `FILL_CENTER` (was `FIT_CENTER` letterboxing). The artwork
  surface draws full-surface with a projection from the frame intrinsics rescaled + cropped exactly
  as the preview (`CameraFillCenter`, SphereSLAM `CameraIntrinsicsTransforms`/`ProjectionMatrix`);
  gesture units/pixel use the cover scale; capture taps, the coverage glow's field of view and
  Overlay ▸ Gyro's screen intrinsics map through the same crop; the analyzers track the full frame
  rather than the viewport cropRect. Unit-tested as pure math; on-device registration check
  pending.
- [x] Upstream `CameraFillCenter` to SphereSLAM `camera` beside `ScreenIntrinsics` (fill
  counterpart of `fitCenter`, plus the crop-aware projection and view→frame mapping). Done in
  0.24.1 (`camera.CameraFillCenter`); the app copy is gone.
- [ ] Add GL/instrumentation tests for standalone texture clear/replace if test infrastructure permits.
  Deferred by decision: verification is hands-on use of the app; no `androidTest` set-up for now.
- [x] Sanitize and test standalone renderer transforms: non-finite values fall back safely
  (a non-finite or non-positive scale becomes 1), and rotations normalize. Scale and pan have no
  bounds, by decision: the design goes wherever the artist or Align puts it.

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

- [x] Define the standalone fingerprint coordinate frame.
  - [x] Centered SphereSLAM page frame, with physical scale when known.
- [x] Define an explicit transform between:
  - [x] KPM page frame;
  - [x] standalone renderer wall frame;
  - [x] MobileGS fingerprint frame.
- [x] Unit-test that transform with known synthetic poses.
- [x] Encode the frame contract in `StandaloneFingerprintFrame`: KPM lower-left millimetres are
  centred with the same half-pixel convention as artoolkitX, renderer wall space and MobileGS
  standalone object space are identical, and the standalone wall/anchor relationship is identity.
  JVM tests pin pixel/KPM equivalence, Y-up orientation, and the identity frame bridge.
- [x] Decide how standalone creates the first MobileGS fingerprint:
  - [x] generate 3D points directly on the KPM page plane;
  - [x] do not adapt the ARCore capture-camera builder; use a dedicated standalone page builder.
- [x] Ensure descriptor pixels and 3D points are generated from the same rectified page image used to seed KPM; live matching uses the same display-oriented CameraX frame contract.
- [x] Set MobileGS live intrinsics from the standalone display-oriented CameraX calibration.
- [x] Feed the exact cropped/rotated display luma frame to MobileGS only after the frame contract above is satisfied.
- [x] Feed accepted standalone camera view/projection to `slamManager.updateCamera` only after its world/frame
  semantics match what MobileGS expects.
- [x] Rename/generalize `setArCoreTrackingState` to backend-neutral `setTrackingPoseValid`;
  standalone marks it true only for a KPM pose accepted for the same CameraX frame.
- [x] Audit every native branch formerly conditioned on `mIsArCoreTracking`. There was one:
  plane-guided rectification. It now requires backend-neutral current-pose validity **and**
  `mHasFingerprintView`; standalone deliberately has no capture-camera view and therefore does not
  run the ARCore capture-view rectifier.
- [x] Restore existing saved ARCore fingerprints safely on standalone devices:
  - [ ] either provide a validated frame conversion; or
  - [x] treat `fingerprint` as ARCore/capture-camera-frame-only and require the separate standalone page/`sphereSlamFingerprint` path.
- [x] Define behavior for projects that contain both ARCore fingerprint data and a standalone page: keep separate persisted fingerprints and install only the active backend's frame into native MobileGS.
- [x] Feed baseline paint-progress/corroboration after centered-page alignment is proven; spatially gated self-grow remains disabled pending the checks below.
  - [x] Standalone artwork registration is descriptors-only (no fake depth); native global matching drives progress until design-placement gating is validated.

- [x] Standalone design placement now feeds the existing gated corroboration/Φ path using the exact
  centered-page rigid transform and scale-included extents; unit tests pin the CW→right-handed
  rotation and no-scale-in-matrix contract.
- [x] Remove the legacy self-grow assumption that a valid wall plane must have nonzero distance from
  the fingerprint origin; centered standalone pages intentionally lie on z=0 through that origin.
- [x] Verify self-grow adds points in the standalone fingerprint frame. Native back-projection
  computes camera/rays in fingerprint object space and appends the resulting intersection directly;
  for standalone that object space is the centered KPM page frame. The architecture check pins the
  direct write.
- [x] Verify saved wall feature maps preserve that frame across process restarts. Standalone maps now
  use a separate persisted `sphereSlamWallFeatureMap` slot/version, export the identity
  fingerprint-anchor transform, and are refused on restore/save if the anchor is not centered-page
  identity.
- [x] Add diagnostics identifying fingerprint frame/version/backend; standalone seed logs `backend=standalone-kpm frame=centered-page version=1` and refuses unknown versions.

**ACCEPTANCE:** MobileGS can consume standalone frames without any implicit ARCore-world assumption,
and return-visit / paint-progress results agree spatially with the KPM wall pose.

---

## 9. Standalone wall-map growth beyond one page

The standalone matcher now grows a bounded KPM atlas while page 0 remains the immutable canonical
wall frame.

- [x] Define when an additional page should be captured:
  - [x] minimum baseline from existing pages;
  - [x] sufficient KPM/feature quality;
  - [x] sufficient overlap for frame registration;
  - [x] only from a fully accepted `LOCKED` visual pose, never bridged/low-confidence tracking.
- [x] Assign stable monotonically increasing page IDs.
- [x] Store page-to-canonical-wall transforms.
- [x] Add pages without resetting the canonical wall frame.
- [x] Match against the atlas and report which page produced the pose.
- [x] Convert every matched page pose back into the same canonical wall frame before it leaves
  `SphereSlamStandaloneSession`.
- [x] Cap page count / memory usage at 12 total pages.
- [x] Define page eviction/compaction policy: no runtime eviction; the hard cap preserves stable IDs
  and avoids silently deleting the only reference for a distant wall region.
- [x] Persist rectified page images plus scale/frame metadata.
- [x] Restore page atlas on project reopen.
- [x] Include atlas assets in export/import via the project directory and URI rebasing path.
- [x] Add corruption/version handling; incompatible or unreadable grown pages are removed
  independently without invalidating canonical page 0.
- [x] Unit-test matching when only a grown page supplies the observation.
- [x] Unit-test return from grown pages to page 0 without a coordinate jump.
- [x] Unit-test loop consistency across three overlapping page frames.
- [ ] Real-device test: walk far enough that page 0 leaves the camera view while a grown page remains
  visible, then return to page 0 and verify no visible artwork jump.

**ACCEPTANCE:** the software path can grow and restore a larger-wall atlas without changing coordinate
frames. Physical walk-off/return validation remains in the later real-device validation phase.

---

## 10. Hybrid SphereSLAM → ARCore fusion

The software correction path is implemented. ARCore remains the continuous camera pose source; KPM
never writes renderer view/projection matrices. A metric KPM observation may only adjust the artwork
anchor through the explicit timestamp/frame conversion and `PoseFusion` seam, and only while ARCore
itself is tracking. The existing drift-correction switch still gates downstream correction and
remains off by default.

Implemented:

- [x] Seed the hybrid KPM page with physical scale from the ARCore metric wall plane rather than the
  adapter's legacy/default 72-DPI value.
  - [x] Intersect raw sensor-camera rays with the ARCore plane.
  - [x] Build a conservative centered physical wall rectangle.
  - [x] Perspective-rectify the camera image to the rectangle's physical aspect.
  - [x] Compute KPM DPI from measured wall width.
- [x] Reuse the tested KPM→right-handed OpenGL conversion in the hybrid path.
- [x] Define explicit KPM-page ↔ ARCore frames:
  - [x] `world_from_page = inverse(sensor_view) * camera_from_page`;
  - [x] create a dedicated ARCore page anchor from that metric page frame;
  - [x] freeze `page_from_artwork` only while both the page anchor and artwork consensus anchor
    are tracking;
  - [x] unit-test that `page_from_artwork` is invariant under a global ARCore world rebase.
- [x] Persist the hybrid rectified page plus page↔artwork relation across process restart/project
  reopen. PR #1970 keeps them runtime-only; a reopened project still falls back to the durable
  MobileGS return-visit/fingerprint path until a new hybrid page is captured. Now persisted as
  `GraffitiProject.hybridKpmPage*` (gzip'd raw luma `hybrid_kpm_page_<uuid>.y8.gz`, pixel size,
  metric width, `page_from_artwork`) plus `hybridKpmFingerprintKey` — the
  `fingerprint.captureAnchorCam` of the target the relation was frozen against. The relation freezes
  only against an anchor established AFTER the page's capture (re-captures no longer freeze against
  the outgoing anchor); the page commits only once that capture's own fingerprint lands on the
  project (a failed fingerprint build never commits its page), and restores only while the project's
  fingerprint still matches the key, so an older page goes inert rather than wrong. Rebased on
  import (dropped, not fatal, when the archive lacks the file), re-armed on the first tracking frame
  of a later session with no ARCore page anchor (the solve needs only the relation + live backbone),
  and refused for the rest of a session once a new capture starts. Corrections still require the artwork
  anchor to be re-established first, which the MobileGS return-visit path does. Device validation
  of a reopen-then-correct cycle is still open with the other §10 device items.
- [x] Maintain a bounded ARCore pose/backbone history keyed by camera frame timestamp.
- [x] Pair each asynchronous KPM observation with the nearest sensor-view + unfused consensus sample
  within a strict 40 ms timestamp window; no render-time pose substitution is allowed.
- [x] Reject KPM observations older than 500 ms.
- [x] Define first-pass confidence gates:
  - [x] minimum 12 inliers;
  - [x] maximum 4 px reprojection error;
  - [x] finite metric page/frame requirement;
  - [x] ARCore itself must currently be tracking.
- [x] Add a hard maximum correction translation/angle rejection policy. Existing
  `PoseFusion.COLD_SNAP_DIST_M` / `COLD_SNAP_ANGLE_DEG` classify a correction as a cold relock;
  they do **not** reject a large but high-confidence KPM correction. `HybridKpmCorrection` now
  refuses any observation whose anchor-local correction exceeds `MAX_CORRECTION_M` (1.0 m) or
  `MAX_CORRECTION_DEG` (30°) with `Reject.CORRECTION_TOO_LARGE`. Rebase-invariant (measured on
  `backbone⁻¹ ∘ corrected`). First-pass ceilings; device tuning is the §10 device items below.
- [x] Require repeated-observation agreement before a large/cold KPM snap. A single observation that
  passes the current quality gates may cold-snap through PoseFusion. Now: an observation implying a
  move past the cold-snap thresholds (vs what is currently drawn) is held as
  `FusionState.AWAITING_AGREEMENT` until `PoseFusion.HYBRID_REQUIRED_AGREEMENT` (3) candidates agree
  within 5 cm / 3° of the first inside a 1.5 s window; a disagreeing or expired candidate restarts
  the count. Small corrections still apply on one observation. `PoseFusionHybridObservationTest`.
- [x] Add a formal correction decision type:
  `HybridKpmCorrection.Decision/Accepted/Reject`.
- [x] Feed accepted KPM corrections into `PoseFusion.currentAnchorFromHybridObservation`; never
  assign KPM output to renderer camera matrices.
- [x] Store only the resulting anchor-local correction, so it survives ARCore global world rebases.
- [x] Define source priority per render frame:
  1. accepted fresh metric KPM correction;
  2. existing MobileGS correction path;
  3. hold the standing anchor-local correction;
  4. raw ARCore consensus if no correction exists.
- [x] Define smoothing/cold-snap strength from KPM inlier/reprojection quality using PoseFusion's
  existing blend/cold thresholds.
- [x] Ensure rejected/missing KPM observations cannot replace ARCore pose; during ARCore tracking
  loss KPM does not become a surrogate continuous tracker.
- [x] Expand fusion diagnostics to expose the full KPM decision payload on-screen/reporting:
  accepted/rejected reason, observation age, inliers, reprojection error, and correction
  translation/angle. PR #1970 logs accepted/rejected reason + timestamp/inliers/confidence.
  Now `HybridKpmDiagnostics` (core/common) carries outcome/age/inliers/reprojection/correction
  mm+deg/agreement count; shown as the overlay's `KPM` row in sidecar mode, logged per new
  observation (`ARDIAG hybrid KPM …`), and histogrammed + summarized in the shareable diagnostic
  report when the sidecar ran.
- [x] Unit-test metric page geometry, page↔ARCore transforms, global-rebase invariance, timestamp
  pairing/clock discontinuity, age/inlier/error gates, known correction deltas, and PoseFusion
  anchor-local holding.
- [ ] On-device test forced ARCore drift/relocalization with KPM visible.
- [ ] Verify on device that there is no visible jump when KPM and ARCore already agree.
- [ ] Verify on device that stale/wrong-wall KPM cannot drag the anchor away.
- [ ] Verify target recapture + repeated renderer/session lifecycle does not leak the dedicated KPM
  page anchor/session.

**ACCEPTANCE:** the software path can improve/recover wall registration through `PoseFusion` while
ARCore remains the continuous backbone and KPM failure is harmless. Durable reopen support,
large-correction hardening, richer diagnostics, and real-device validation remain required before
calling hybrid correction production-validated.

---

## 11. ARCore-only feature audit and standalone equivalents

Every AR feature now has an explicit backend answer.

### Plane / hit-test behavior

- [x] Inventory every `Frame.hitTest` / ARCore plane consumer. The live consumers are confined to
  `ArRenderer`: ARCore anchor establishment and ARCore/wearable calibration.
- [x] Core standalone wall placement requires no ARCore hit at all: capturing page 0 establishes the
  canonical wall frame directly. For screen-space wall queries, `StandaloneWallHitTest` performs
  calibrated CameraX ray → canonical z=0 intersection against registered KPM page bounds.
- [x] When the standalone wall is not visually locked, standalone hit-test returns null; it never
  extrapolates from a stale/bridged pose.
- [x] Unit tests cover page boundary acceptance, outside-atlas rejection, grown-page coverage, and
  unlocked-wall refusal.

### Depth

- [x] Inventory ARCore Depth consumers: `ArRenderer.acquireDepthImage16Bits`, the isolated
  `StereoProbeService` capability probe, and recording/evaluation depth metadata.
- [x] Categorize depth as an ARCore enhancement/diagnostic, not a requirement for standalone core
  wall placement; KPM supplies planar registration without claiming camera-to-wall metric depth.
- [x] Evaluate a Camera2/depth-sensor substitute: no portable standalone depth provider is currently
  integrated, so the capability is deliberately unavailable rather than guessed from device scale.
- [x] The ARCore-only stereo/depth probe is skipped on standalone, and each standalone tracking tick
  clears `isDepthApiSupported`, `isHardwareStereoActive`, and `currentCenterDepth`.
- [x] Normalized KPM scale is never surfaced as a depth measurement.

### Anchors

- [x] Standalone core placement has no `com.google.ar.core.Anchor`; the canonical centered KPM wall
  frame is the backend-neutral wall anchor.
- [x] Persist `sphereSlamAnchorGeneration` + frame version with the project.
- [x] Recapture or invalid-reference cleanup increments the generation and invalidates atlas/map
  state tied to the superseded page.
- [x] Persist standalone AR design placement separately from ARCore in
  `sphereSlamModeAdjustment`, bound by `sphereSlamPlacementAnchorGeneration`. A generation
  mismatch preserves its tone but zeros only standalone spatial placement and clears only standalone
  undo/redo/reset history. ARCore's `modeAdjustments[AR]` remains untouched.

### Cloud anchors

- [x] There is no implemented Google Cloud Anchor host/resolve pipeline in this repository; the
  renderer's historical `cloudAnchor` name refers to a local ARCore anchor.
- [x] Standalone therefore advertises no cloud-anchor capability. Project/fingerprint/KPM assets
  remain shareable independently.
- [x] Cloud-anchor absence does not disable co-op: section 12 now gives Host/Join an explicit
  backend-neutral wall-frame calibration contract.

### Point clouds / perception debug

- [x] ARCore `PlaneRenderer`, `PointCloudRenderer`, and `ArDebugRenderer` exist only inside
  `ArRenderer`, which the standalone branch never constructs.
- [x] Standalone therefore never renders empty ARCore planes/points as a tracking failure.
- [x] Standalone diagnostics expose the useful native evidence instead: KPM page id, inlier count,
  reprojection error, observation age, match duration, tracking state, and failure reason.

**ACCEPTANCE:** no AR-mode button or background code path on a non-ARCore device reaches an
ARCore-only hit-test/depth/anchor/perception API without an explicit capability guard or standalone
equivalent.

---

## 12. Co-op / multi-device standalone behavior

- [x] Define the shared coordinate object: the host's durable **wall-local frame**, never either
  device's transient world origin. `CoopSpatialFrame.fingerprintFromWall` is the only peer-PnP
  bridge into that object.
- [x] Include standalone reference image/scale/frame metadata in the initial project snapshot. The
  existing project ZIP already carries page 0 + atlas images and their canonical transforms;
  protocol v3 adds the explicit scale/backend/frame descriptor beside those bytes.
- [x] Define standalone-host → ARCore-guest calibration: peer PnP is composed through identity
  `fingerprintFromWall`; physically metric standalone pages are accepted and normalized pages are
  rejected before bulk transfer.
- [x] Define ARCore-host → standalone-guest calibration: CameraX feeds the host MobileGS fingerprint,
  then `CV_TO_GL * cameraFromFingerprint * fingerprintFromWall` yields camera-from-host-wall.
- [x] Define standalone-host → standalone-guest calibration: the shared KPM page/atlas is already in
  the same canonical centered wall frame; normalized page units are valid because both peers consume
  that exact page coordinate object.
- [x] Do not reuse Procrustes implicitly. Existing wearable Procrustes remains a separate explicitly
  framed calibration; co-op's planar wall bridge is direct and does not need a world-world solve.
- [x] Reject peer calibration when physical scale is missing and metric alignment is required.
- [x] Allow normalized standalone ↔ standalone sharing by page-relative correspondence only.
- [x] Bump the co-op wire protocol to v3 for backend + wall-frame metadata.
- [x] Produce a clear incompatible-peer result: v2 peers fail VersionMismatch and incompatible v3
  backend/scale pairs fail SpatialIncompatible instead of guessing.
- [x] Reconnect/resync reuses the same spatial descriptor and fresh project ZIP; if the host target
  changes, the session ends with SpatialFrameChanged rather than rebasing the guest silently.
  Project ZIP relocation already covers standalone page-0 and atlas URIs.
- [x] Normalize the spectator copy's active AR placement into the guest backend before repository
  publication: standalone-host placement moves into ARCore's AR slot (with the same metric base quad
  reconstructed from page/design aspect), while ARCore-host placement moves into standalone's slot
  and stale standalone target metadata is discarded. Missing metric size fails closed rather than
  silently screen-fitting a different mural size.

**ACCEPTANCE:** co-op never silently combines coordinates from different backends/scales as though
they were the same frame.

---

## 13. Performance / thermal / memory

- [ ] Measure standalone KPM matching time at representative CameraX resolutions.
- [ ] Measure UI/preview FPS with KPM active.
- [ ] Measure native heap and Java heap while tracking.
- [x] Confirm direct frame buffers are reused rather than allocated each frame:
  `SphereSlamStandaloneTrackingAnalyzer.directFrame` grows one direct buffer only when a frame is
  larger and reuses it otherwise. (The heap `ByteArray` from `LumaFrameTransform.packCropAndRotate`
  is still allocated per frame — a GC-pressure measurement item, not a direct-buffer leak.)
- [x] Confirm pending work cannot queue unboundedly. Camera side: the analyzer does its matching
  synchronously and the shared controller pins `STRATEGY_KEEP_ONLY_LATEST`, so at most one camera
  frame waits (enforced by `tools/check_sphereslam_architecture.py` invariant 7). UI side:
  `consumeTrackedFrame` hands its values to a `LatestValueUiPost` — at most one main-thread post
  outstanding, applying the newest frame's values — so a slow UI thread never replays a backlog.
- [x] Add adaptive standalone analysis cadence if KPM exceeds the frame budget. KEEP_ONLY_LATEST
  drops stale frames but does NOT throttle: when matching exceeds the budget the analyzer stays
  continuously saturated. `AnalysisFrameGate` (`DeviceStressPolicy.kt`): with Settings → Adaptive
  rate on, an analyzer whose smoothed work exceeds a camera frame is held to half duty; under
  thermal / power-save / low battery frames are capped at 20 / 15 fps. `DeviceStressPolicyTest`
  drives it through a keep-only-latest simulation.
- [x] Preserve low latency over maximum throughput: drop stale frames instead of queueing them
  (`CameraPreview.rememberCameraController` now pins it rather than inheriting CameraX's default).
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

- [x] Distinguish:
  - [x] native library unavailable;
  - [x] camera unavailable (including a 5-second camera-ID acquisition timeout instead of an
    infinite Preparing state);
  - [x] intrinsics unavailable;
  - [x] reference too weak;
  - [x] no current page match;
  - [x] stale observation;
  - [x] excessive reprojection error;
  - [x] insufficient inliers;
  - [x] persisted target missing/corrupt;
  - [x] catastrophic/non-finite pose continuity rejection.
- [x] Surface concise artist-facing messages for recoverable/transient failures without turning
  ordinary visual misses into fatal modals.
- [x] Keep detailed numeric reasons in diagnostics/logs, including inliers, reprojection error,
  observation age, match duration, camera calibration, and weak-reference feature counts.
- [x] Add one-copy diagnostic dump from standalone failure/reacquisition UI with backend, camera
  calibration, KPM state, page ID, inliers, reprojection error, observation age, match duration,
  physical-scale status, and current failure detail.
- [ ] Verify permission revocation while standalone AR is open.
- [ ] Verify camera interruption by another app.
- [ ] Verify app background/foreground during LOCKED, IMU_BRIDGE, and REACQUIRING states.
- [x] Verify process-death boundaries for target persistence: an uncommitted candidate cannot
  replace the old reference, while committed metadata remains authoritative even if old-file cleanup
  had not yet run.
- [x] Missing native KPM linkage is classified as NATIVE_LIBRARY_UNAVAILABLE and surfaces the
  explicit unsupported-build message instead of a generic tracking failure.

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
- [x] standalone page↔MobileGS frame conversion tests (`PhotosphereFingerprintFrameTest`,
  `StandaloneFingerprintBuilderTest`);
- [x] hybrid page↔ARCore frame conversion tests, including global-rebase invariance;
- [x] timestamp pairing tests with bounded nearest-sample matching and clock-discontinuity reset;
- [x] physical-scale migration tests (`ProjectManagerTest`: a pre-SphereSLAM project defaults to
  width 1, not metric; import keeps width + metric flag);
- [x] multi-page canonical-frame conversion tests (`GrownAtlasPagesTest`; SphereSLAM
  `PhotosphereAtlasGrowthTest`, `AtlasMatcherWorkerTest`).

### Android/Robolectric/instrumented

Unchecked items here need an on-device / instrumented test set-up. Deferred by decision:
verification is hands-on use of the app, with failures reported back.

- [x] Camera2 crop/intrinsic test (JVM: SphereSLAM `CameraIntrinsicsEstimatorTest`, app
  `CameraCapabilitiesTest`; an on-device one waits for an `androidTest` source set);
- [ ] CameraX camera-ID acquisition test;
- [x] target persistence/readback test (`ProjectManagerTest`, `MainViewModelStandaloneReplacementTest`);
- [x] project import URI relocation test (`ProjectManagerTest`);
- [ ] process recreation test;
- [ ] GL texture clear/replace test;
- [ ] lifecycle analyzer/IMU teardown test;
- [x] backend routing test on ARCore-unavailable environment (`ArViewModelLiveSettingsTest`: an unsupported
  device resolves to `STANDALONE`; `ArRailBackendPolicyTest`).

### Native

- [ ] KPM JNI smoke test on packaged APK;
- [ ] KPM add-page/match known-image integration test;
- [x] native buffer-size guard tests (`core/nativebridge/src/test/cpp/FrameBufferGuardTest.cpp`, run in CI);
- [x] MobileGS standalone-frame integration tests (`MobileGSRelocTest`: a centred page-frame fingerprint with
  an identity anchor locks and reports `camera_from_fingerprint` in the page frame; `MobileGSFrames.*`);
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

- [x] Update every stale comment that calls CameraX/homography the only "ARCore fallback" (none left).
- [x] Update `file_descriptions.md` with standalone classes and ownership.
- [x] Update `FEATURE_REFERENCE.md` with backend capability differences (capability table).
- [x] Update `SLAM_SETUP.md` with standalone CameraX/KPM data flow.
- [x] Update `NATIVE_ENGINE.md` once MobileGS standalone frame semantics are implemented.
- [x] Document physical-scale UX when chosen (`FEATURE_REFERENCE.md`, `NATIVE_ENGINE.md`,
  `release-checklist.md`).
- [x] Document standalone tracking-state diagnostics and thresholds when finalized
  (`SPHERESLAM_ARCORE_SIDECAR.md`, `release-checklist.md`).
- [x] Document co-op cross-backend semantics when implemented (`ARCHITECTURE.md`,
  `FEATURE_REFERENCE.md`).
- [x] Remove any obsolete statement that AR mode is hidden when ARCore is missing (none left).

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

## 19. Validation / next-release criteria

The standalone feature is already merged. These are the remaining criteria before treating the
dual-backend SphereSLAM work as production-validated rather than software-complete.

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

Software parity milestone after the initial standalone merge:

- [x] physical-scale model (measured metric or explicitly normalized; real-device measurement
  validation still open);
- [x] MobileGS/fingerprint integration in the centered standalone page frame;
- [x] bounded wide-area/multi-page wall atlas growth and restore;
- [x] hybrid metric KPM correction through `PoseFusion` (durable hybrid reopen + device validation
  still open in §10);
- [x] deliberate equivalents/degradations for ARCore-only features;
- [x] co-op protocol-v3 cross-backend calibration;
- [ ] sustained-session performance/thermal/device validation.

---

## Recommended implementation order

Completed software dependencies:

1. [x] Build/CI foundation.
2. [x] Physical-scale model.
3. [x] Standalone target workflow.
4. [x] Standalone confidence/loss gates.
5. [x] MobileGS standalone coordinate bridge + fingerprint generation.
6. [x] MobileGS camera feed / paint-progress / self-grow integration.
7. [x] Multi-page wall atlas growth.
8. [x] ARCore-only feature/equivalent audit.
9. [x] Co-op cross-backend calibration.
10. [x] Hybrid metric KPM→PoseFusion correction software path.

Next dependency:

11. [ ] **Performance/device matrix and remaining real-device acceptance** — includes standalone
    sustained-session measurements, atlas walk-off/return, hybrid forced-drift/wrong-wall/no-jump,
    permission/interruption/lifecycle, and non-ARCore hardware.
12. [ ] **Cleanup/refactor only after those behavior tests are proven** — keep the current explicit
    frame seams until measurements/device runs show they are safe to consolidate.

