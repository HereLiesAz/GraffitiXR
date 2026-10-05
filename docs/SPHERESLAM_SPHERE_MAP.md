# Standalone Spherical-Coverage Map (Design)

> Status: **design — for review.** Addendum to `RELOC_MAP_DESIGN.md`. This document
> **reverses that doc's "no explicit scan / passive-only" decision for the standalone
> (non-ARCore) path** and records why the two premises that decision rested on have changed.
> It does not change the ARCore path, which keeps VIO-driven passive accumulation.

## 1. The problem this solves

On the standalone (SphereSLAM / no-ARCore) path, relocalization is **slow — "always too
late."** The marks fingerprint is the only thing reloc can lock onto, and it only matches
when the painted marks are **in frame**. The moment the artist pans away — to step back, to
look at a reference, to reach a far corner of a mural that is larger than the fingerprint —
there is nothing to match, so the overlay drifts until the marks swing back into view and a
fresh lock lands. The lock is reactive, and it arrives after the artist already needed it.

`RELOC_MAP_DESIGN.md`'s lean `WallFeatureMap` was meant to close this, but it was specified
to build **passively**, from whatever keyframes normal use happens to provide, and only on
the **wall plane**. Passive accumulation is itself the latency: the map only knows what the
camera has already dwelt on, so the far corners and the surrounding space are empty exactly
when they are needed. The wall plane alone also can not hold a lock while the camera faces
*away* from the wall.

## 2. The insight

The mural is on a flat wall. **The space around that wall, from the artist's roughly-fixed
standpoint, is — as far as the camera needs to know — a sphere.** A muralist works from a
small footprint and pivots: the surroundings sweep past the lens as an angular field, not a
volume they walk through.

So reloc does not need a dense 3D reconstruction of the room. It needs **features in every
direction**, indexed by where the camera was pointing, so that *whatever* the camera sees
when it comes back — wall or not — gives an immediate pose. The wall fingerprint stays the
**precision anchor**; the surrounding sphere is **coverage** that makes the lock fast and
anticipatory.

## 3. Why this is not a new spatial primitive

The surrounding sphere **is the existing `WallFeatureMap`, populated omnidirectionally.**
That structure already stores feature descriptors at arbitrary 3D points, co-registered to
the fingerprint anchor, matched under a frustum gate (`RELOC_MAP_DESIGN.md` §5, §4b.1).
Nothing in it requires the points to lie on the wall plane — the "wall" in the name is a
usage assumption, not a constraint of the data model. Filling it with features from every
pivot direction turns it into a spherical coverage shell around the standpoint without a new
type, a new persistence slot, or a new reloc path.

What is genuinely missing on standalone is the **geometry to place those features** — the
job ARCore's VIO does on the hybrid path (triangulate map points from a metric baseline).
Standalone has no VIO. Two capabilities that did not exist when `RELOC_MAP_DESIGN.md` was
written now supply that geometry:

- **Recorded gyro orientation — the angular glue.** `GyroOrientationBridge.cameraRotationDelta`
  already yields the camera-space rotation between frames (`GyroOrientationBridge.kt`). Today
  it is a transient ≤ 400 ms bridge across vision dropouts and is **discarded**
  (`SphereSlamStandaloneTrackingAnalyzer.kt` bridge expiry; orientation cleared on stop). Here
  it is instead **recorded per keyframe**, giving each keyframe's pointing direction on the
  sphere. This is the "sensors recorded for reloc" the standalone path has never kept.
- **MiDaS monocular depth — the radial distance.** `DepthEstimator` (MiDaS Small, ONNX) is
  loaded and self-tested but not yet wired into any geometry path (`ArViewModel.selfTestDepth`;
  "Step 2" comment). It supplies per-keyframe **relative depth**, so surrounding features get a
  radius, not just a bearing — a coarse 3D shell rather than a pure-rotation panorama. This is
  exactly the **"triangulate via VIO baseline (or depth when available)"** branch
  `RELOC_MAP_DESIGN.md` §5 was written for and standalone could never take, because — per
  `SPHERESLAM_TODO.md` — "no portable standalone depth provider is currently available." MiDaS
  is now that provider.

Gyro (bearing) + MiDaS (radius) are, together, the standalone substitute for the VIO baseline
the feature map normally triangulates from. MiDaS is affine-ambiguous, so absolute metric
scale is still governed by the fingerprint's asserted reference width (`StandaloneFingerprintFrame`);
the sphere is self-consistent and metric only to the degree the fingerprint is. That is
acceptable: the sphere's job is fast re-lock bearing-and-coverage, not real-world sizing.

## 4. The reversal, stated plainly

`RELOC_MAP_DESIGN.md` §4b.6 and §5 make **"no explicit scan"** a hard requirement: the map
builds passively, "no scan step, prompt, or forced sweep." **This document overturns that for
standalone**, because:

1. The decision was made when standalone had **no depth provider and no recorded orientation**,
   so a guided sweep could not have produced a better map than passive luck. Both premises
   have changed.
2. Passive accumulation is the direct cause of the "always too late" latency. A guided sweep
   captures the sphere **up front**, so reloc is fast on the first re-lock of a session rather
   than improving over it.

The ARCore path is unchanged: it has VIO and keeps passive accumulation. The "no explicit
scan" rule remains correct there and is retained.

## 5. The flow

1. **Fingerprint the wall (precision anchor).** The standalone capture-and-curate flow:
   point at the wall, tap the marks, freeze the frame, prune candidate features with the same
   editable selection mask the ARCore path uses, build the `sphereSlamFingerprint`. (This
   capture/curate parity is a prerequisite, specified separately; the sphere map builds on the
   anchor it produces.)
2. **Sweep the surroundings (coverage).** A guided pivot: the artist turns roughly in place
   while the app captures keyframes across the angular field. Per keyframe, record the gyro
   orientation (bearing) and run MiDaS (radius); detect ORB/SuperPoint features; place them as
   3D points in the fingerprint-anchored frame; associate to existing map points or add new,
   bumping confidence/obsCount exactly as the passive path would. Coverage is shown as a
   simple angular progress hint (which directions are still thin), not a forced full 360°.
3. **Reloc fuses sphere + fingerprint.** The whole map is frustum-gated to the current
   bearing and matched for wide-area coverage; the fingerprint provides the precision snap when
   the marks are visible; the map lets the app **anticipate the fingerprint** — project the
   anchor into the current frustum and prime the matcher as it is about to enter view
   (`RELOC_MAP_DESIGN.md` §4a.3). The lock is held while facing away, so it is instant on
   return instead of late.

## 6. What is reused vs net-new

Reused (no change): `WallFeatureMap` data model + `.gxr`/`sphereSlamWallFeatureMap`
persistence, native map storage + frustum-gated reloc matching (`setMapBuildEnabled` /
`setMapRelocEnabled`), ORB-default / SuperPoint-opt-in descriptor policy, the lean budget
(§4b: 5k default / 20k cap, ≤ ~1 MB, no GPU, per-keyframe not per-frame), KPM capture/rectify
and target-quality gates, the fingerprint precision anchor and coarse-to-fine fusion.

Net-new:

- **Persisted per-keyframe orientation** from `GyroOrientationBridge` (today transient/discarded).
- **MiDaS depth wired into geometry** — per-keyframe radial depth for map-point placement and
  for the fingerprint's own 3D points (the dormant "Step 2").
- **A guided-sweep capture loop + coverage UX** (the reversed "no scan" decision), standalone only.
- **Omnidirectional map population** — placing features off the wall plane from bearing+radius
  rather than from a VIO baseline.

## 7. Lean budget and constraints (carried over, non-negotiable)

Everything in `RELOC_MAP_DESIGN.md` §4b still holds: frustum-gated matching only (never
brute-force the sphere), ORB default (SuperPoint opt-in behind the existing setting),
configurable cap (5k default / 20k hard max) pruned by confidence, per-keyframe build (no
per-frame cost), no GPU footprint, ≤ ~1 MB RAM and `.gxr` at the ORB default. The guided
sweep must not reintroduce per-frame work: keyframes are sampled, not every camera frame
integrated.

## 8. Phased sequencing (each phase device-gated, behind the existing Feature-map flag)

1. **Record orientation.** Persist per-keyframe gyro bearing alongside the existing map build;
   no behavior change to reloc yet. Round-trip test.
2. **MiDaS → geometry.** Wire `DepthEstimator` output into map-point placement (radius) and
   the standalone fingerprint's 3D points; validate the self-test path feeds real frames.
   Compare reloc vs fingerprint-only behind the flag.
3. **Guided sweep + coverage UX.** The pivot capture loop and angular coverage hint; populate
   the map omnidirectionally from bearing+radius. This is the step that reverses "no scan."
4. **Fusion + tuning on device.** Coarse-to-fine map/fingerprint fusion, anticipate-the-
   fingerprint priming, confidence/pruning thresholds, keyframe cadence — all tuned on device.

## 9. Open questions

1. **Sweep extent.** How much of the sphere is "enough" before reloc is meaningfully faster —
   a loose arc around the wall, or a fuller turn? Tune in Phase 4; the coverage hint should
   guide, not gate.
2. **Gyro drift over a sweep.** `TYPE_GAME_ROTATION_VECTOR` has no magnetometer, so bearing
   can drift over a long sweep. Acceptable for short pivots; Phase 4 decides whether loop
   closure against already-mapped features is worth it or over-budget.
3. **MiDaS coarseness.** Relative, low-resolution depth places features approximately. Is the
   radius accurate enough for frustum gating and PnP to benefit, or does the sphere effectively
   degrade to bearing-only (still useful)? Measured in Phase 2.
4. **Scale coupling.** The sphere inherits the fingerprint's asserted-width scale. If the
   fingerprint is non-metric (default), the sphere is non-metric too — fine for reloc, but
   worth confirming nothing downstream assumes the map is metric.
