# Native Engine (MobileGS) Documentation

## Overview
`MobileGS` (`core/nativebridge/src/main/cpp/MobileGS.cpp` / `include/MobileGS.h`) is a C++17 relocalizer: it fingerprints a wall the artist has marked, then matches that fingerprint against the live camera feed to snap the mural's anchor back into place after tracking loss or a pocketed phone. It is **not** a mapping engine — there is no persistent-voxel or splat layer, no scene reconstruction, and no `draw()`. Earlier drafts of this document described one; that layer was deleted from the codebase, and `MobileGS.cpp`'s own code says so at the two call sites that used to feed it (`setMappingPaused`, `getSplatCount` — both log "no gaussian-splat mapper in this engine" and return inert values).

## Key Components

### 1. The wall fingerprint
A fingerprint is ORB (`cv::ORB::create(1500)`) or SuperPoint descriptors paired with the triangulated 3D positions of the marks the artist drew, captured once when the target is confirmed (`restoreWallFingerprintMetric`). It optionally carries a per-point region tag (`Footprint::Region` — INSIDE/BAND/OUTSIDE) so relocalization can exclude points that sit under the artwork itself and will be painted over.

Both app builders (`MetricFingerprintBuilder`, `StandaloneFingerprintBuilder`) add the capture
photo's **close-ups** (`CloseUpFeatures`): crops at zooms 6×, 3× and 2× (the anticipation
half-spheres, square-on), each tiled by its grid of aim points, enlarged to about a live frame's size
and run through the base fingerprint's own detector. Their features map back to the photo's pixels
and onto the wall like the capture's own, so a close-up of any corner meets dense features at the
size it sees them. At most as many as the capture's own (≤ 2000), shared across the 49 crops.
Settings → Fingerprint chooses what a new target's fingerprint is made of (`FingerprintFeatures`):
**SuperPoint** (SuperPoint features of the capture only, ~1.5 MB), **Half sphere** (ORB features of
the capture and its close-ups, < 0.1 MB) or **Both** (SuperPoint and the close-ups, ~2–3 MB; the
default). It applies to the next capture; relocalization matches whichever descriptors a fingerprint
holds. While they build, a progress bar counts the crops down (`FingerprintBuildProgress`, shown by
`MainScreen` in any mode).

### 2. Relocalization ("snap-back")
A background thread (`relocThreadFunc`) continuously matches the live camera frame's descriptors against the stored fingerprint (Lowe ratio test, `kRelocLoweRatio = 0.75`) and solves the resulting 2D↔3D correspondences with `cv::solvePnPRansac` (100 iterations, 8px reprojection threshold, 0.99 confidence). Every solve that clears PnP's own floors (at least 6 inliers, `FEW_INLIERS` otherwise) is published (pose, inlier/match counts, and a monotonically increasing `seq` — all under `mMutex` in one critical section, so a reader never sees a torn result); native applies no further accept/reject policy to the *anchor*. `kBigLockInliers = 20` only gates `growTrusted` — whether a relock is trusted enough to promote new marks into the fingerprint (a big lock still has to pass the inlier-spread gate); it does not accept a lock "outright". The anchor correction itself is **Kotlin-side**: `PoseFusion` reads the published result (`getRelocResult`), applies its inlier-ratio gate (`MIN_INLIER_RATIO = 0.5`) and cold-snap / blend policy, and keeps the standing anchor-local correction. `clearWallFingerprint` resets the published result's pose and counts to "no relock" but never rewinds the seq, so a consumer holding an old seq can't mistake a later result for one it has seen. The worker waits between attempts (`mRelocLockedBackoffMs`, default 200 ms once locked; `mRelocHuntBackoffMs`, default 60 ms while hunting; both clamped to 10–2000 ms and raised under thermal / power-save / low-battery pressure via `setRelocBackoffMs`). This — not a mapping/rendering layer — is the actual mechanism behind the "Pocket-Ready" pitch: it needs no persistent map, just the one fingerprint.

### 2a. Relationship to hybrid SphereSLAM/KPM

On ARCore-capable devices, MobileGS is no longer the only optional drift-correction observation.
A target capture with a real ARCore wall plane can also seed a **physically metric, rectified KPM
page** in the separate `:sphereslam` module.

The ownership boundary is strict:

- ARCore remains the continuous camera pose;
- KPM matching runs asynchronously outside this native engine;
- `HybridKpmCorrection` pairs a KPM observation with the ARCore sensor-view/backbone from the same
  timestamp and applies metric/quality gates;
- an accepted KPM correction enters `PoseFusion` first;
- while a KPM correction is *fresh* (accepted within `CorrectionSourceArbiter.HYBRID_FRESH_WINDOW_NS`,
  3 s), frames with no new observation HOLD the standing correction — the MobileGS PnP result is not
  consulted, so the two estimators never alternate frame by frame;
- the MobileGS PnP correction drives only before KPM has first accepted, or once KPM has been silent
  for the whole window (and a capture pose exists);
- if neither source is available, PoseFusion only re-applies its standing anchor-local correction.
See `TELEOLOGICAL_SLAM.md` ("Correction sources").

MobileGS does not consume the hybrid KPM page frame and KPM does not mutate MobileGS fingerprint
coordinates. The only shared downstream object is PoseFusion's **anchor-local correction**, which is
frame-invariant under ARCore global world rebases. The correction switch remains off by default.

### 2b. The sensor turn (lost frames)
When no tracking pose is current, the pose-based rectifying pass cannot run. The phone's sensors still
say which way the camera faces (`setCameraAttitude`: earth ← camera from the north-referenced rotation
vector, fed with every frame in both AR modes), and the wall's normal says which way the wall faces.
The live frame is then turned about its own centre into a camera square-on to the wall and upright
(`sensorTurnHomography`: focal × cos(obliquity), principal point where the frame's centre lands),
matched there, and the matches mapped back for the one PnP. A pure turn needs no position, so this
works from the first lost frame. The normal is learned at every well-supported lock
(`wallNormalFromLock`: the fingerprint's plane through the PnP pose and that frame's attitude),
averaged in, saved with the project (`fingerprintWallNormalEnu`) and restored on load, so it is
there for the first relocalization of the next session; a new target forgets it. Diagnostics:
`lastRelocTurnDeg`, `lastRelocTurnCorr`.

### 3. Teleological corroboration and self-grow (both off by default)
Two optional, separately-gated mechanisms sit on top of relocalization:
- **Corroboration**: compares descriptors from the design composite against the live wall to produce a confidence signal (Lowe ratio `kCorrobLoweRatio = 0.85`) — see `docs/TELEOLOGICAL_SLAM.md` for what it does and does not measure.
- **Self-grow** (`mSelfGrowEnabled`, default **off**, `setSelfGrowEnabled`): lets validated new marks extend the fingerprint as the artist paints, so relocalization can survive the original reference being covered. Capped at `kMaxWallMarks = 5000` points. Two further, independent 5000 caps exist: `kMaxPaintMarks = 5000` (painted-design reference marks) and `kMapCap = 5000` (the persistent feature map, which confidence-prunes when full).

Both are reachable only from the diagnostic overlay (Settings > diagnostic overlay), not the artist-facing UI — see that doc for why, and for the open questions around self-grow's promotion gate.

### 4. Optional persistent wall feature map
`WallFeatureMap` (`setMapRelocEnabled`/`setMapBuildEnabled`, `IMPLEMENTATION.md` phases 2b/3) is a second, longer-lived relocalization source that can be built and matched against across sessions. Off by default like the two mechanisms above. The **map path** of the reloc pass is additive and prior-gated: it needs a previous published pose (`mapPriorSeq > 0`, i.e. the fingerprint has locked at least once), frustum-gates the map to the points that pose says are in view (never a brute-force match of the whole map), and appends those correspondences to the fingerprint's before the single PnP solve.

For standalone SphereSLAM, both self-grown fingerprint points and feature-map growth are written
directly in the centered KPM-page fingerprint frame (+X right, +Y up, +Z wall normal). The
standalone map is persisted separately from the ARCore-frame map and must carry an identity
fingerprint-anchor transform on save and restore; incompatible anchors are refused rather than
implicitly converted.

## Memory Management
- The fingerprint itself is small (a few thousand descriptor+point pairs at most); there is no fixed-size point-cloud or voxel budget to speak of beyond `kMaxWallMarks`.

## JNI Interface (`GraffitiJNI.cpp` → `SlamManager.kt`)

A representative subset of the real, currently-exported surface (see `SlamManager.kt` for the complete list):

| Kotlin method | Description |
|---|---|
| `restoreWallFingerprintMetric(...)` | Ingests a captured fingerprint (descriptors + 3D points + capture pose/intrinsics). |
| `updateAnchorTransform(mat)` | Hands the engine the current ARCore anchor pose. (The relocalization correction itself is computed Kotlin-side by `PoseFusion` from `getRelocResult`.) |
| `getRelocDiagnostics()` | Structured relocalization state (inliers, reprojection error, reject reason). |
| `getCorroborationConfidence()` | Teleological corroboration's confidence signal (see `TELEOLOGICAL_SLAM.md`). |
| `setSelfGrowEnabled(enabled)` | Toggles self-grow (default off). |
| `feedYuvFrame(...)` | Per-frame camera feed into the reloc thread. |
| `setCameraAttitude(earthFromCamera)` | The frame's north-referenced camera attitude, for the sensor turn. |
| `getWallNormalEnu()` / `setWallNormalEnu(n)` | The wall's normal learned at locks; saved with and restored from the project. |
| `clearWallFingerprint()` | Drops the current fingerprint (new project / re-target). |


