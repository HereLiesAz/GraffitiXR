# Metric Depth Strategy: dual-lens priority + motion triangulation

*Design spec — 2026-06-02*

## Context

The app's central failure is metric distance — "how far is the wall/mark I'm looking at." Today every
distance comes from ARCore's depth map, and on devices without hardware stereo (e.g. Pixel 5) that's an
ML-estimated depth that is jittery, hole-pocked, and (per logcat) errors continuously while also
appearing to starve VIO. The fingerprint's 3D marks (`MobileGS.generateFingerprint`) are back-projected
straight from that depth (`Z = depthMm/1000`), and keypoints with missing depth are dropped — so bad
depth ⇒ a sparse/wrong 3D fingerprint ⇒ relocalization can't recover pose ⇒ the overlay drifts. The
existing "stereo" path (`StereoDepthProvider` temporal stereo → `StereoProcessor`) pairs *consecutive*
frames (near-zero baseline while painting) and converts raw disparity with `*1/16` and **no baseline /
no focal length**, so it cannot produce metric depth.

The maintainer's hard requirements: (1) get metric distance *right*, and (2) **use and prioritize dual
lens when the device has it.**

## Goal

A single **metric-depth source selector** with a priority ladder:

1. **Hardware stereo (dual lens) — preferred.** When `isHardwareStereoActive` (ARCore selected a
   hardware-stereo `CameraConfig`, PR #1522), ARCore's depth is hardware-derived and reliable; use it
   for the marks' 3D positions and distance.
2. **Motion (VIO-baseline) triangulation — when stereo is unavailable.** Triangulate the marks from two
   camera views with a sufficient *metric* baseline taken from ARCore VIO poses (the artist's natural
   step-in/step-back). Single lens, metric, improves with movement.
3. **ARCore ML depth — last resort.** Only if neither above yields a value; never the primary on mono
   devices. (It also stays disabled-by-default while we confirm the VIO-starvation fix.)

Reliable metric marks feed the fingerprint (`mWallKeypoints3D`), which is what makes relocalization —
the app's real main loop — actually work.

## Non-goals

- Not replacing ARCore VIO/tracking (that's the separate in-flight fix). Triangulation *depends* on VIO
  poses being valid.
- Not Depth Anything / a per-frame depth model: it yields relative (non-metric) depth and is compute-
  heavy; triangulation gives metric depth from motion the app already has.
- Not dense full-frame depth. We need metric depth **at the marks** (sparse) for the fingerprint; dense
  surface depth (mesh) remains a separate, lower-priority concern.

## The triangulation core (layer 2)

Classic two-view linear triangulation (DLT), metric because the camera poses come from VIO:

- For each of two observations of the same mark: a 3×4 projection `P = K · [R | t]`, where `K` is the
  camera intrinsics and `[R|t]` is the **world→camera** transform from the ARCore view matrix at that
  frame, and the mark's 2D pixel `(u,v)`.
- Solve `A x = 0` (the 4×4 DLT system from the two `(u,v)` ↔ `P` pairs) via SVD for the homogeneous 3D
  point `X`; de-homogenize. Result is in ARCore **world** (metric) coordinates.
- Quality gates: triangulation **baseline** (‖t₂ − t₁‖) ≥ ~8 cm; **parallax angle** ≥ ~1–2°; positive
  depth (cheirality) in both views; low reprojection residual. Reject marks that fail.

This is a pure function: `triangulate(p0: 3x4, p1: 3x4, uv0, uv1) → Point3 + residual`. Unit-testable
with synthetic cameras (place a known 3D point, project into two poses, triangulate, assert recovery).

## Observation capture + pairing (layer 2 wiring)

- As the artist moves, periodically (throttled) record an **observation**: detect the marks/features in
  the current frame (reuse the existing ORB/SuperPoint detector + the artwork mask) and store their 2D
  positions **with the current ARCore view matrix + intrinsics**. Keep a small ring of recent
  observations.
- Match marks across observations by descriptor (the matcher already exists for relocalization).
- When a matched pair has adequate baseline/parallax, triangulate each shared mark → metric 3D.
- Aggregate over several pairs (median / running estimate) for stability; this is the "gets better the
  more you move" behavior.

## Source selection + fingerprint integration

- A selector decides per capture: `isHardwareStereoActive` → ARCore depth back-projection (layer 1);
  else → triangulated 3D (layer 2); else → ML depth (layer 3, only if enabled).
- `generateFingerprint` consumes the selected metric 3D for `mWallKeypoints3D` instead of unconditionally
  reading the depth map. Same downstream PnP relocalization; just fed correct metric geometry.

## Where the math lives

- **Pure triangulation + gates: Kotlin** (`feature/ar` or `core/nativebridge` Kotlin), unit-tested — the
  poses (VIO view matrices) and intrinsics are already in Kotlin, and OpenCV isn't needed for 2-view DLT.
- Observation capture: `ArRenderer` (GL thread has the live frame + view matrix) → a small collector.
- Integration into `mWallKeypoints3D`: pass triangulated points into the native fingerprint instead of
  (or alongside) the depth-derived ones.

## Testing

- Unit: `triangulate` recovers a known synthetic point within tolerance; rejects degenerate (zero-
  baseline, behind-camera) configs; baseline/parallax gates behave. Aggregation median is correct.
- On-device (after VIO confirmed): step in/out ~0.5 m in front of marks; confirm triangulated mark
  distances match a tape measure and that relocalization re-locks across distances. Dual-lens device:
  confirm layer 1 (hardware stereo) is selected and used.

## Dependencies / sequencing

- **Depends on VIO tracking** (the in-flight depth-disable experiment). Triangulation is worthless
  without valid metric poses. Build the pure core now (no VIO needed); wire capture/integration once VIO
  is confirmed.
- Layer 1 (dual-lens priority) is already wired (PR #1522) — this spec adds the selector that *prefers*
  it and the layer-2 triangulation for everyone else.
