# Relocalization Configuration

This document covers tuning and troubleshooting for `MobileGS`'s relocalizer — see `docs/NATIVE_ENGINE.md` for what the engine is. There is no voxel or splat mapping layer to configure; earlier drafts of this document described tuning one, but no such layer exists in `core/nativebridge`.

## Key parameters

The relocalizer's real constants live in `core/nativebridge/src/main/cpp/include/MobileGS.h`; the authoritative record of which ones have been measured on real hardware versus set by informed guess is `docs/research/PARAMETERS.md` — read that before treating any of these as settled:

| Parameter | Value | Description |
| :--- | :--- | :--- |
| `kRelocLoweRatio` | `0.75` | Lowe's-ratio threshold for a relocalization descriptor match. |
| `kCorrobLoweRatio` | `0.85` | Same test, for the teleological corroboration path (see `TELEOLOGICAL_SLAM.md`). |
| `kBigLockInliers` | `20` | Inlier count above which a PnP solve is accepted outright. |
| `kMaxWallMarks` | `5000` | Cap on fingerprint points, including any self-grow additions. |
| RANSAC | `100` iterations, `8px` reprojection threshold, `0.99` confidence | `cv::solvePnPRansac`'s own parameters for the reloc solve. |

## Sensor input pipeline

### ARCore + MobileGS path

#### Color frame (`feedYuvFrame` / `feedColorFrame`)
The ARCore camera feed is offloaded to `relocThreadFunc` for background ORB/SuperPoint matching
against the stored fingerprint and a `solvePnPRansac` pose solve.

#### Depth (hardware stereo where available)
Depth is used for triangulating the fingerprint's 3D points at capture time on devices with real
hardware stereo; there is no separate mapping/fusion pipeline that consumes it afterward.

### Standalone CameraX + SphereSLAM/KPM path

On devices where ARCore is unavailable, AR mode does **not** construct an ARCore `Session`.
`MainScreen` keeps CameraX bound and mounts `SphereSlamStandaloneOverlay`:

1. the artist captures a textured wall patch and marks four corners;
2. `PerspectiveProcessor` rectifies that patch into the canonical page image;
3. image-quality preflight rejects undersized, flat, or blurry targets and warns on severe clipped
   exposure;
4. the artist can enter the real measured page width, or explicitly continue with normalized
   1.0-unit scale;
5. `SphereSlamStandaloneTrackingAnalyzer` packs the real `ImageProxy.cropRect` Y plane, rotates it
   into display orientation, estimates Camera2 intrinsics for the raw CameraX frame, applies the crop,
   and rotates `fx/fy/cx/cy` into the same display frame;
6. `SphereSlamStandaloneSession` builds a calibrated artoolkitX KPM page and synchronously matches
   each analyzed frame;
7. KPM camera-from-page is converted to a centered, right-handed OpenGL wall-relative view matrix;
8. the transparent `HomographyOverlayRenderer` draws the design over `CameraPreview`;
9. a visual miss can use the rotation-only gyro bridge for at most 400 ms, then the overlay clears
   and enters explicit reacquisition.

KPM acceptance defaults mirror the pinned artoolkitX binary pose path: at least 4 inliers and
reprojection/ICP error no greater than 10.0. The app additionally rejects stale REALTIME-timestamp
observations and catastrophic pose jumps.

#### Persistence

The portable standalone target is a versioned
`sphereslam_reference_<uuid>.png` plus width/physical-scale/generation metadata in
`project.json`. Additional atlas pages are persisted as versioned
`sphereslam_page_<pageNo>_<uuid>.png` files with their rigid `canonicalFromPage` transforms.
Native KPM handles are never serialized; the in-memory KPM session/atlas is rebuilt from those
persisted page images on reopen/import/co-op project transfer.

#### MobileGS boundary — connected through the centered page frame

Standalone MobileGS no longer reuses the ARCore `fingerprint` slot. It has its own
`sphereSlamFingerprint` and `sphereSlamWallFeatureMap`, both versioned against the canonical
centered KPM page frame. For standalone:

- KPM page 0, renderer wall coordinates, MobileGS fingerprint object coordinates, and persisted
  standalone wall-map coordinates are the same centered page frame;
- accepted same-frame KPM view/projection feed `SlamManager.updateCamera`;
- CameraX display-oriented luma/intrinsics feed MobileGS only under that explicit frame contract;
- self-grow and wall-feature-map intersections are stored directly in fingerprint/page coordinates;
- an ARCore-frame `fingerprint` is never silently restored into standalone MobileGS.

The two persisted fingerprint/map slots remain separate precisely so no implicit ARCore-world
conversion can creep back in.

### ARCore + metric hybrid KPM correction

On ARCore-capable devices, KPM is a lower-rate **correction source**, never the primary camera pose.
At target capture, a tracking ARCore wall plane is intersected with raw sensor-camera rays to define
a physical wall rectangle. The captured bitmap is perspective-rectified to that physical aspect and
registered with explicit DPI derived from measured wall width. A dedicated ARCore page anchor is
created from the same metric geometry.

During tracking, `SphereSlamTracker` matches at roughly 10 Hz. Its observation timestamp is paired
with a bounded history of raw sensor-camera ARCore view + unfused artwork consensus (40 ms maximum
pairing error). `HybridKpmCorrection` then requires a metric page, ≤500 ms observation age,
≥12 inliers, ≤4 px reprojection error, and finite transforms before the correction may enter
`PoseFusion`.

Correction priority while the off-by-default drift-correction switch is enabled is:

1. fresh accepted metric KPM correction;
2. MobileGS PnP correction fallback;
3. hold the standing anchor-local correction;
4. raw ARCore consensus if no correction exists.

KPM never writes renderer view/projection matrices and is not used as surrogate continuous tracking
while ARCore is paused. The hybrid rectified page/page↔artwork relation is currently runtime-only;
project reopen falls back to the durable MobileGS fingerprint until a new metric hybrid page is
captured.

## Tuning guide

**"The tracking doesn't snap back after pocketing"**
Cause: no wall fingerprint was captured, or relocalization is failing its RANSAC/inlier gates against the live frame (a near-featureless or highly repetitive surface — smooth stucco, running-bond brick — starves the correspondence set the solver needs).
Fix: confirm a target was actually captured and locked; for a low-texture wall, capture the fingerprint over a patch with more visible variation (an edge, a stain, a fixture) rather than the flattest part of the surface.

**"The overlay is placed correctly at capture but drifts off over a session"**
Cause: drift correction (`driftCorrectionEnabled`) is off by default — see `docs/TELEOLOGICAL_SLAM.md` for how to enable it from the diagnostic overlay, and its own caveats before doing so.

**"The geometry looks skewed after rotating the phone"**
Cause: the fingerprint's stored intrinsics were captured at one display rotation and the live frame is being matched at another — see `MobileGS.cpp`'s `restoreWallFingerprintMetric`/reloc PnP path and `ArRenderer`'s `cvRotateCode` handling.
Fix: re-capture the target at the orientation painting will actually happen in, or file this as the open bug it currently is if it reproduces.

---
*Updated 2026-10-01 with standalone CameraX/KPM + centered-page MobileGS integration, persisted
multi-page atlas behavior, and the metric ARCore/KPM→PoseFusion correction contract. Rewritten 2026-09-04 to describe the relocalizer actually in the tree — the
previous "Persistent Voxel Memory" tuning guide (voxel size, stochastic sampling, `MAX_SPLATS`, `feedArCoreDepth`) had no corresponding code anywhere in `core/nativebridge`.*
