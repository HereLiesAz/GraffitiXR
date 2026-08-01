# Teleological SLAM (Painting-Progress Correction)

Conventional relocalization treats the wall as a fixed target: capture a
fingerprint once, then match against it forever. That assumption breaks for a
muralist, because **the wall changes as you paint it** — the very marks the
fingerprint relies on get covered by the artwork, so matching gets *worse* the
further along you are.

GraffitiXR turns that around. Because the app already knows what the finished
piece is supposed to look like (the overlay you loaded), it treats the goal
state as additional information — hence *teleological* (goal-directed). The
further along the painting is, the more real-world corroboration the engine
has, and the more tightly the overlay locks to the wall.

## Mechanism

The work happens entirely in the native engine (`core/nativebridge`), layered
on top of the OpenCV relocalizer:

1. **Baseline fingerprint.** When the artist registers the wall, the engine
   stores ORB/feature descriptors of the clean surface (the relocalization
   fingerprint used by `relocThreadFunc` for snap-back).
2. **Progress measurement (`MobileGS::tryUpdateFingerprint`).** On a clean
   camera frame, the engine measures how much of the registered artwork base is
   now corroborated by real wall content, writing the result to
   `mPaintingProgress`. This stage is read-only with respect to the reloc
   fingerprint.
3. **Confidence weighting.** As `mPaintingProgress` rises, the corroborated
   marks contribute more to the pose solution, so global drift correction
   becomes more aggressive — the overlay "snaps" more tightly the more of the
   mural exists on the wall.

   Concretely: `ArRenderer` passes the progress as `PoseFusion.currentAnchor`'s
   `confGlobal`, which scales the smoothing rate as
   `alpha = BASE_ALPHA * inlierRatio * (CONF_FLOOR + (1 - CONF_FLOOR) * progress)`.
   The floor means a bare wall still corrects at half strength on the PnP inlier
   ratio alone; a fully corroborated one earns twice that.

   (Until 2026-07 this stage was **not wired**: `confGlobal` was pinned at `1f`
   with a comment about the retired voxel map, so progress reached the HUD and
   nothing else and correction strength was identical at 0% and 100% painted.)

4. **Self-grow.** Live features that pass the same corroboration test are
   promoted into the reloc fingerprint (`mSelfGrowEnabled`, default on), so
   relocalization survives the original marks being painted over. The promotion
   gate is `MobileGS::growTrusted` — a strong inlier ratio qualifies at a lower
   absolute count, because a half-covered wall rarely reaches a large raw inlier
   count and the old flat `inliers >= 20` meant the fingerprint could only grow
   when it was already strong.

This is the inverse of the failure mode other tracing apps hit, where accuracy
degrades as the original reference marks disappear under paint.

## What "matching the image" does and does not mean

The corroboration test is **descriptor similarity, not geometric accuracy**. A
live feature corroborates the artwork when its nearest neighbour among the
design composite's descriptors passes a Lowe ratio of 0.75
(`MobileGS::tryUpdateFingerprint`). There is no positional tolerance, no scale
or colour check, and nothing anywhere compares your brushwork to the design
geometrically. Painting "more accurately" only helps insofar as it makes the
wall's local appearance descriptor-match the design image.

Tracking itself never consults the artwork at all: relocalization matches the
live camera against the **photograph of the wall taken at target creation**.

## Diagnosing it

Every failure in this chain used to be silent. `RelocDiagnostics` (surfaced by
the Diagnostic Overlay, in release as well as debug) reports which gate the last
attempt missed:

| State | Meaning |
|---|---|
| `NO_FINGERPRINT` | no target created, or one with no 3D points — nothing to match |
| `NO_FEATURES` | live frame had no usable texture (light, focus, blur) |
| `FEW_MATCHES` | fewer than 8 correspondences survived the ratio test |
| `PNP_FAILED` | matches found, none geometrically consistent |
| `FEW_INLIERS` | PnP solved but fewer than 6 inliers agreed |
| `OK` | pose published; PoseFusion applies it if the inlier ratio ≥ 0.5 |

The overlay also shows how many features the live frame yielded *before* matching.
That disambiguates `FEW_MATCHES`, which means opposite things depending on it: a
handful of features in frame is a capture problem (light, focus, a blank wall),
while a thousand features that don't match is an aiming problem.

The reloc thread runs at 5 Hz once locked and ~16 Hz while hunting, since the
cost of an extra attempt is far smaller than the cost of the overlay staying
adrift.

## Open question: the display-rotation convention

**Unresolved, and the leading suspect if relocalization matches but places the
overlay wrong.** Recorded here so the analysis isn't redone from scratch.

At capture, `ArRenderer` rotates the intrinsics to display orientation (the
`when (rotationNeeded)` block: swap `fx`/`fy`, remap `cx`/`cy`) and `ArViewModel`
rotates the bitmap by the same angle. The **view matrix is not rotated** — it is
`camera.pose.inverse()` in ARCore's own camera frame.

`PlaneMarks.backProject` builds each ray from the rotated pixel and rotated `K`,
then intersects it with the plane transformed into the camera frame by that
unrotated view. Working the algebra for a 90° turn: a pixel's ray in the rotated
frame is `d' = (-d_y, d_x, 1)`, i.e. `d' = R·d` with `R = [[0,-1,0],[1,0,0],[0,0,1]]`.
So the rays live in a frame rotated about the optical axis relative to the plane
they are being intersected with, and the resulting depths are skewed — the
fingerprint's 3D structure is not the real wall, and no consistent PnP pose
exists over it.

Two details make this fit the observed "never locks" behaviour:

- The error **vanishes for a head-on wall** — a plane normal of `(0,0,±1)` is
  invariant under rotation about Z — and grows with obliquity. That matches a
  failure that feels intermittent rather than absolute.
- The onboarding doodle path (`buildDoodleFingerprint`) uses the identical
  convention, so it would fail the same way.

**RESOLVED — Phase 0 (PR #1797).** The description above is of the pre-fix code;
the algebra in it is correct and matches the fix's independently-derived direction
(`d' = R_z(+90)·d`). `MetricMarks.glViewToCvDisplay` now rotates the capture view
to match the pixels and intrinsics, and `MetricFingerprintBuilder.buildSingle`
routes through it.

The reason given here for not simply fixing it was wrong on its central point.
It claimed `V_current` is in ARCore's *unrotated* frame; it is not —
`Camera.getViewMatrix` is documented as incorporating display orientation. The
live side was already display-oriented, so the composition needed no rotation
change, and the fix was one matrix at one site rather than the system-wide
rotation change feared here.

`composeCorrected` does still have frame defects, but they are GL-vs-CV
convention and a missing capture-view factor, not rotation — see
`docs/research/IMPLEMENTATION.md` 0.6/0.9.

**How to tell:** with the Diagnostic Overlay on, a healthy match count and a
**low inlier ratio** points here. `NO TARGET` / `NO FEATURES` / a low in-frame
feature count point at capture problems instead, which are covered above.

## Relationship to the rest of the engine

- **Relocalization** (snap-back after tracking loss / screen-off) is the
  `relocThreadFunc` background thread — see [NATIVE_ENGINE.md](NATIVE_ENGINE.md)
  and [SLAM_SETUP.md](SLAM_SETUP.md).
- **Pose tracking** itself is provided by ARCore; the native engine layers
  relocalization, the persistent voxel map, and this teleological correction on
  top of ARCore's poses.
- Pose smoothing/fusion lives in the Kotlin `PoseFusion` layer, not in C++.

---
*Documentation updated on 2026-06-22 during the SLAM right-size and documentation-accuracy pass.*
