# Perspective- & Distortion-Robust Relocalization Matching

**Status:** design (2026-06-03). Extends `2026-06-02-relocalization-first-design.md`.
**Driver:** Up-close and *off-to-the-side* viewing distorts the wall marks (perspective
foreshortening) and shifts their light/color. The app must (a) still recognize the fingerprint under
that distortion and from partial views, and (b) *know* it is at an oblique angle so it can adapt and
inform the user.

## Where we are (main, post-#1537/#1541)

Reloc pipeline in `MobileGS::relocThreadFunc` (`MobileGS.cpp:278–387`):
- Detector/descriptor: **SuperPoint** (learned) with **ORB** fallback.
- Matching: `knnMatch(k=2)` + Lowe ratio 0.75.
- Pose: `solvePnPRansac` → **IPPE planar refine + flip-resolution** (adopt only if it strictly beats
  the RANSAC reprojection). Real fingerprint intrinsics (`mFingerprintIntrinsics`).
- Publishes `mPnpCamFromFpWorld` + inlier/match counts + seq → `PoseFusion` (D-drift + cold-snap).

**Robustness today:** *moderate.* SuperPoint is fairly viewpoint/illumination-robust, and PnP+IPPE
handles the planar geometry — but the bottleneck is the **2D match**, not the pose solve. Under steep
oblique + light change, descriptor matching degrades and the relock fails. Nothing currently uses the
*known* viewing angle, even though it is derivable from the pose.

## Key insight: the distortion is a homography we can pre-cancel

The marks lie on a **known plane**, and ARCore VIO gives a **pose** every frame (it drifts, but it is
continuous while tracking). The appearance difference between the stored frontal fingerprint and the
current oblique view of a planar target is therefore a **homography**. We can **rectify** (warp) the
live frame toward the fingerprint's canonical frontal view *before* matching, neutralizing the
perspective geometrically. Learned descriptors (SuperPoint, already used) absorb most of the
illumination change. So: **rectify (geometry) + learned features (appearance)** — no custom model
training required.

This also breaks the chicken-and-egg: rectification does **not** need matches first — the VIO pose
supplies the warp; PnP then refines on the rectified matches.

## Decision: do NOT train a custom model

What "recognize an image/parts under perspective + light/color change" needs is **learned local
features + a learned matcher**, which exist pretrained and already encode that invariance:
- **SuperPoint + LightGlue** (LightGlue = modern, mobile-friendly matcher), or **XFeat**
  (lightweight, built for constrained devices).
Training a bespoke perspective/illumination-invariant model needs large augmented data and would only
re-derive what these give for free. Use pretrained; spend effort on rectification + the multi-view
fingerprint instead.

## Staged design

### Stage 1 — Plane-guided rectification (rectify-then-match)  ← next build
Data already present: `mViewMatrix` (current VIO view), `mFingerprintAnchorMatrix` (wall anchor at
capture), `mFingerprintIntrinsics`, `mWallKeypoints3D` (define the plane in the fingerprint-camera
frame). **Add** `mFingerprintViewMatrix` (capture-time VIO view — `generateFingerprint` already
*receives* `viewMat` but ignores it).

1. Fit a plane to `mWallKeypoints3D` (normal `n_fp`, offset `d_fp`) in the fingerprint-camera frame.
2. Relative pose fingerprint-camera → current-camera ≈ `view_cur · inverse(view_fp)` (valid while VIO
   tracks; both share the VIO world).
3. Plane-induced homography `H = K_fp · (R − t·nᵀ/d) · K_cur⁻¹` mapping current image → fingerprint
   image. Warp the current gray by `H` (frontal-equivalent), then detect+match against the stored
   fingerprint descriptors in fingerprint-image coordinates; un-warp matched 2D back for PnP.
4. **Obliquity gate:** only rectify when the angle between `n` (current camera frame) and the camera
   axis exceeds ~25° (frontal views need no warp), and only when VIO is tracking.
5. **Never-worse fallback:** if VIO isn't tracking, or rectified matching yields fewer good matches
   than the plain path, keep the plain result. Extra cost = one warp + one SuperPoint inference when
   oblique — acceptable (accuracy ≫ compute here).

### Stage 2 — Multi-viewpoint / multi-scale fingerprint
The fingerprint "looks different up-close vs far" (scale) and from the side (perspective). Store
descriptors captured at several distances/angles and match against the union. The **teleological
self-growing fingerprint** (`tryUpdateFingerprint`, currently a stub at `MobileGS.cpp:389`) is the
natural accumulator: as the artist paints from different spots, validated new marks enter the
fingerprint with their viewpoint, widening coverage over time.

### Stage 3 — Learned matcher (LightGlue / XFeat) — optional upgrade
Replace knn+Lowe with a learned matcher for wide-baseline robustness. Pretrained ONNX, no training;
larger integration, deferred until Stages 1–2 are validated on-device.

## "Does it know the angle?" — yes, and it should act on it

Obliquity = angle(plane-normal-in-camera, camera forward), computable each frame from `view · anchor`.
Use it to (a) trigger rectification, (b) scale/gate relock confidence, (c) optionally inform the user
("steep angle — move around to lock"). This is the "ML recognizing and informing" the user asked for,
achieved with geometry + the existing learned descriptor.

## Concrete change shipped with this spec

`MobileGS.cpp:335` — lower the published-match floor `inliers.size() >= 12 → >= 6`. The comment above
the block already promises permissiveness for **partial** close-up views, and `PoseFusion` is the real
gate: a small match can only *smooth-correct* (hard-snap still needs ratio ≥ 0.7 **and** ≥ 20
inliers), so a 6-inlier partial nudges the overlay without risking a teleport to a bad pose.

## Verification

- Stage 1: unit-test the homography/obliquity math (pure, JVM-testable if mirrored in Kotlin, or a
  native gtest); on-device A/B with the existing Fusion ON/OFF dev overlay — relock success rate from
  oblique/partial positions vs. frontal.
- Floor change: on-device — confirm partial close-up relocks now nudge the overlay and that no false
  teleports appear (they can't, per the PoseFusion gate).
