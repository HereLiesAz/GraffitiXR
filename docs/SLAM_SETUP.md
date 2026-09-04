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

### Color frame (`feedYuvFrame` / `feedColorFrame`)
The live camera feed, offloaded to `relocThreadFunc` for background ORB/SuperPoint matching against the stored fingerprint and a `solvePnPRansac` pose solve.

### Depth (hardware stereo where available)
Depth is used for triangulating the fingerprint's 3D points at capture time on devices with real hardware stereo; there is no separate mapping/fusion pipeline that consumes it afterward.

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
*Rewritten 2026-09-04 to describe the relocalizer actually in the tree — the previous "Persistent Voxel Memory" tuning guide (voxel size, stochastic sampling, `MAX_SPLATS`, `feedArCoreDepth`) had no corresponding code anywhere in `core/nativebridge`.*
