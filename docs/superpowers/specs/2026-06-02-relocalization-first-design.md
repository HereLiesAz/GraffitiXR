# Relocalization-First Architecture (with Teleological SLAM)

*Design spec — 2026-06-02*

## Context & priority

The real product is: **the instant the camera sees the wall again — out of a pocket, screen back on,
from any distance, seeing only part of the marks — the overlay snaps to exactly the right place.** The
maintainer ranks **instantaneous, perfect cold relocalization above everything**, because the phone goes
in/out of a pocket constantly and the screen is off in between.

Today this fails, and the survey shows why:
- **Cold relock can't lean on VIO.** Screen-off pauses ARCore; on resume VIO is cold and needs
  motion/time. So the snap must come from **single-frame PnP against a saved fingerprint**, independent
  of VIO.
- **The marks' 3D positions come only from ARCore ML depth** (`generateFingerprint`, `Z=depthMm/1000`),
  which is unreliable on mono devices; depth-less keypoints are dropped → sparse/wrong fingerprint →
  PnP can't solve, especially on partial views.
- **Teleological SLAM is entirely stubbed** (`setArtworkFingerprint`/`runPnPMatch`/`tryUpdateFingerprint`
  are `{}`; `mPaintingProgress` never written). So relocalization only matches the original marks — which
  get **painted over** — and must degrade as the work progresses.
- Reloc uses plain `solvePnPRansac` with fixed thresholds (≥15 matches, ≥12 inliers, `MobileGS.cpp`),
  which rejects legitimate close-up **partial** views and can flip on coplanar (flat-wall) points.

## Goal

Make relocalization the **primary driver of the overlay pose**, robust to cold start, partial views,
distance changes, and the marks being painted over. VIO becomes a secondary smoother that coasts the
overlay only when relocalization has no view to match.

## Architecture

### 1. Pose source: relocalization-first
- Every frame the wall is visible, solve **PnP** against the fused fingerprint → absolute pose in the
  saved world frame; apply it. This is independent of VIO convergence.
- VIO (ARCore) smooths/coasts **between** successful PnP solves (marks out of view, motion blur).
- **Cold relock = hard snap.** On the first confident PnP after a resume/tracking-loss, set the pose
  immediately (no smoothing); only smooth *subsequent* in-session corrections (extends `PoseFusion`:
  add a `coldSnap` path that bypasses the SLERP on the first lock).

### 2. Teleological SLAM — a self-growing fingerprint validated by the target
Relocalization always matches **real observed features** (marks + paint) against the fingerprint — it
never depends on the painting "looking like" the target. The target overlay is the **gatekeeper that
decides which new real features earn a place in the fingerprint**, so the map stays dense as the original
marks get painted over:

- The registered **target overlay is the "base understanding"** — what each wall location is supposed to
  become, via the current anchor pose + wall plane.
- Each (throttled) frame, run feature detection on the **clean camera image** — the raw wall + paint,
  **not** the composited view with the overlay drawn into it (else we'd fingerprint our own overlay).
- For **new** features not already in the fingerprint: project the feature into target space (current
  pose + anchor plane) and test whether the target has corresponding content there **and** the live
  local patch **roughly matches** the target at that spot (a tolerance check — patch correlation /
  descriptor agreement).
- **Pass → promote** the feature into the fingerprint as a new anchor, with metric 3D from the anchor
  plane (or the depth ladder). **Fail** (hand, shadow, mistake, off-target paint) → reject.
- Result: the fingerprint **continuously grows from the artist's own validated painting**; original marks
  fade under paint but verified paint-marks replace them → more PnP correspondences → "the further along,
  the tighter it sticks."

This is what implements the empty stubs: `tryUpdateFingerprint` (the per-frame detect→validate→promote
loop), `setArtworkFingerprint` (register the target as the validator + its plane geometry), and
`runPnPMatch` (per-frame PnP against the grown fingerprint).

### 3. Painting progress (powers confidence + "tighter as you go")
- `progress` = fraction of the target area now backed by **confirmed (promoted) marks** — a natural
  byproduct of §2. Write `mPaintingProgress` (currently always 0). Drives relocalization confidence and
  the UI; more confirmed anchors also *mechanically* tightens the lock (more correspondences).

### 4. Partial-view & flat-wall robustness
- **Partials are first-class:** PnP needs only the visible subset. Keep the fingerprint **densely
  covered** (fixing metric depth keeps marks valid instead of dropped) so any patch has enough points.
- **Adaptive thresholds:** use distance + camera FOV + the fingerprint's physical extent to estimate how
  much *should* be visible, and lower the inlier/match bar for a legitimate close-up partial instead of
  rejecting it. (This is the concrete payoff of "depth informs partial awareness.")
- **Planar PnP:** wall points are coplanar → use `SOLVEPNP_IPPE` and **resolve the two-fold flip** with
  the prior/VIO-hinted pose, so a small partial patch can't lock backwards.

### 5. Multi-scale / continuous fingerprint (so frame-1 matches)
- The close-up partial out of the pocket looks different from the original capture. Implement
  `tryUpdateFingerprint`: as the artist works at various distances, **augment** the fingerprint with
  current-scale descriptors (both mark and artwork sets), so future relocations have matching-scale
  references. Cap/decay so it doesn't grow unbounded.

### 6. Persistence / cold resume
- Fingerprint (marks + artwork + progress) persists and **reloads on resume** (mostly present:
  `restoreWallFingerprint`, atomic save). Verify the artwork set + progress are saved/restored, and that
  PnP runs immediately on resume (don't gate on VIO).

### 7. Metric depth ladder (dependency)
Per `2026-06-02-metric-depth-strategy-design.md`: **dual-lens hardware stereo first**, else **VIO-baseline
triangulation** (core built), else ML depth. Feeds reference (a)'s metric 3D. Reference (b) is metric
from anchor geometry regardless.

## What's stubbed today (must implement)
- `MobileGS::setArtworkFingerprint` `{}`, `runPnPMatch` `{}`, `tryUpdateFingerprint` `{}`.
- `mArtworkDescriptors` / `mArtworkKeypoints3D` never populated; `mPaintingProgress` never written.
- Reloc: fixed thresholds + non-planar `solvePnPRansac`; no partial/IPPE handling; no cold-snap; no
  continuous PnP driving the pose (PnP currently only snaps `mAnchorMatrix` on the reloc thread).

## Testing
- **Unit (pure):** PnP correspondence fusion & weighting by progress; adaptive-threshold computation from
  distance/FOV/extent; IPPE flip-resolution selecting the pose consistent with a prior; cold-snap vs
  smooth selection. (Triangulation core already tested.)
- **On-device (after VIO confirmed):** pocket → out → confirm sub-second snap from a partial, close-up,
  off-axis view; paint over ~half the marks → confirm relock still holds via the artwork reference;
  verify across distances; dual-lens device confirms hardware-stereo path.

## Sequencing
1. **Continuous PnP + cold-snap** (drive pose by reloc; hard snap on first lock).
2. **Partial/planar robustness** (IPPE + flip resolution + adaptive thresholds).
3. **Teleological self-growing fingerprint** — the detect→validate-against-target→promote loop
   (`tryUpdateFingerprint` + `setArtworkFingerprint` + `mPaintingProgress`), running on the clean camera
   image, so the map survives the marks being painted over. (This *is* the continuous/multi-scale
   fingerprint update — same loop also augments scale over distance.)
4. **Metric marks** wired from the depth ladder (triangulation core done; stereo priority done).

All of it rides on VIO at least initializing a session (the in-flight depth-disable experiment); the
**snap itself is PnP**, so it survives weak mono tracking.
