# AR Consensus Pose Fusion (Sub-project B)

*Design spec — 2026-06-01*

## Context

GraffitiXR locks a sketch overlay to a wall using the artist's marks as a persistent anchor. Today the
rendered anchor pose comes from a **hard toggle** (`ArRenderer.kt:398-403`): before the anchor is
established it's the native cached pose (`getAnchorTransform`), after it's the ARCore-anchor consensus
(`AnchorOrchestrator.getConsensusMatrix`). These never combine, and the native relocalization
("snap-back") is **broken**: on a successful PnP match the reloc thread writes the OpenCV
`T_camera_from_fingerprintWorld` view matrix straight into `mAnchorMatrix`
(`MobileGS.cpp:329-339`), which the renderer then uses as a world-space *model* matrix — so the overlay
**teleports** on every reloc instead of snapping cleanly to the wall. That directly defeats the goal of
zero drift.

Sub-project A (merged) measured the mechanisms; the frame analysis it enabled showed only **two** real
per-frame, world-frame pose sources exist (ARCore consensus, and a *corrected* PnP snap); the mesh is an
anchor-local surface warp (not a pose), and the confidence signals (`getVisibleConfidenceAvg`,
`getGlobalConfidenceAvg`, `getPaintingProgress`) are constant stubs.

This spec is the **"corrected snap-back fusion"** (author-selected): fix the reloc frame math, fuse the
ARCore-consensus backbone with smoothed mark-PnP corrections weighted by real confidence, and replace
the toggle with one pose source. Sub-project C (tap-to-distance) will feed tapped marks into this fusion
as metric support anchors.

## Goals

- **Fix the reloc frame bug** so snap-back converges the overlay onto the wall instead of teleporting.
- **One fused anchor pose** replacing the `ArRenderer.kt:398-403` toggle: ARCore consensus as the smooth
  per-frame backbone, corrected by PnP snaps applied as a **smoothed** (SLERP) delta — never an
  instantaneous jump.
- **Real confidence signals**: expose PnP inlier ratio; replace the stubbed splat-confidence averages
  with real values. Weight the correction by these.
- Keep the new path **switchable** (a flag, reusing the dev infrastructure) so it can be A/B-compared
  against the old behavior with the Sub-project A harness.
- Core math is **pure and unit-tested** in Kotlin.

## Non-goals

- No mesh/surface-warp changes (separate concern; mesh stays anchor-local).
- No new SLAM algorithm; we correct frames and blend existing estimators.
- Tap-as-constraint wiring is Sub-project C (this spec only ensures the fusion *accepts* support anchors,
  which `AnchorOrchestrator.addSupportAnchor` already does).

## The correction math (the crux)

At fingerprint capture, the wall keypoints `mWallKeypoints3D` and the anchor model matrix are both in the
**fingerprint world frame** (ARCore world at capture). PnP solves
`p_camera = R·p_fpWorld + t`, i.e. `pnpMat = T_camera_from_fpWorld` (a view matrix). The renderer draws
with the **current** ARCore view/proj. For a rigid anchor point `p_local`:

```
p_camera = pnpMat · M_anchor_fpWorld · p_local           (PnP, geometric truth)
p_camera = V_current · M_anchor_currentWorld · p_local    (render path)
⇒ M_anchor_currentWorld = inverse(V_current) · pnpMat · M_anchor_fpWorld
```

So the corrected anchor model matrix needs three inputs: the **fresh** current ARCore view matrix
`V_current` (GL thread only), the PnP result `pnpMat` (reloc thread), and the **fingerprint-frame anchor**
`M_anchor_fpWorld` (captured once). Because `V_current` is only fresh on the GL thread, the composition is
done in **Kotlin**, not the reloc thread — which also makes it unit-testable.

## Architecture / components

### Native (`:core:nativebridge`)
1. **Stop the buggy write.** In `relocThreadFunc` (`MobileGS.cpp:336-340`), do **not** memcpy `pnpMat` into
   `mAnchorMatrix`. Instead store the raw result for Kotlin to consume: `mPnpCamFromFpWorld[16]`,
   `mPnpInlierCount`, `mPnpMatchCount` (the `imgPts.size()` denominator), and a monotonically-increasing
   `mPnpResultSeq` (so Kotlin detects a *new* result). All under `mMutex`.
2. **Capture the fingerprint anchor.** When the fingerprint is generated, snapshot the then-current
   `mAnchorMatrix` into `mFingerprintAnchorMatrix[16]` (the anchor in fp-world frame). Expose via JNI.
3. **Real splat confidence.** Replace `VoxelHash::getVisibleConfidenceAvg`/`getGlobalConfidenceAvg`
   (`VoxelHash.cpp:262-263`, currently `return 0.5f`) with true means over splat `confidence` (global =
   mean over all splats; visible = mean over splats inside the current view frustum). Empty → 0.
4. **JNI + SlamManager**: `getRelocResult(out: FloatArray /*[0..15]=pnpMat,16=inlierCount,17=matchCount,18=seq*/)`,
   `getFingerprintAnchor(out: FloatArray16)`. (Confidence getters already bound.)

### Kotlin (`:feature:ar`)
5. **`PoseFusion`** (new, in `feature/ar/.../anchor/`): the single source of the rendered anchor pose.
   Pure, testable core + a thin stateful wrapper. Each frame it is given:
   - the ARCore-consensus model matrix (from `AnchorOrchestrator.getConsensusMatrix`) — the backbone;
   - the current ARCore view matrix `V_current`;
   - the latest reloc result (pnpMat, inlierRatio = inlier/match, seq) and the fingerprint anchor;
   - splat confidence (global) as a map-maturity gate.
   It computes (pure fn `correctedAnchor(vCurrent, pnpMat, fpAnchor)` =
   `inverse(vCurrent)·pnpMat·fpAnchor`), then **SLERP/lerps** the *output* anchor from the backbone toward
   the corrected target by `α = clamp(baseAlpha · inlierRatio · maturity)` **only when the reloc seq is
   new and inlierRatio ≥ threshold**. Between snaps the output tracks the consensus backbone. This makes
   snap-back a smooth convergence (a few frames), never a teleport. Pure functions:
   `composeCorrected(...)`, `blend(current, target, alpha)` (translation lerp + quaternion SLERP) — both
   unit-tested with known matrices.
6. **Replace the toggle** at `ArRenderer.kt:398-403` with a single `poseFusion.currentAnchor(...)` call,
   fed the consensus matrix, view matrix, reloc result, and confidence. Guarded by a `fusionEnabled`
   flag (default on) that, when off, reproduces the old toggle for A/B.

### Flag / switch
7. A `fusionEnabled` toggle on `ArRenderer` (settable from `ArViewModel`, surfaced in the dev eval overlay
   from Sub-project A) so the A harness can record drift with fusion on vs off.

## Data flow

```
reloc thread: PnP → store pnpMat,inliers,matches,seq (no anchor write)
GL thread (15Hz block): consensus = AnchorOrchestrator.getConsensusMatrix()
                        reloc = slamManager.getRelocResult(); fpAnchor = slamManager.getFingerprintAnchor()
                        anchorMatrix = poseFusion.currentAnchor(consensus, V_current, reloc, fpAnchor, confGlobal)
                        overlayRenderer.draw(view, proj, anchorMatrix, ...)
```

## Error handling / edge cases

- No fingerprint yet / no reloc result (seq unchanged) → fusion returns the consensus backbone unchanged.
- `inlierRatio < threshold` or `matchCount == 0` → ignore the snap (no correction).
- Pre-anchor (`!anchorEstablished`) → backbone is the native cached pose as today; fusion passes it through.
- Degenerate `V_current` (non-invertible) → skip correction, keep backbone.
- Flag off → exact old toggle behavior (regression-safe escape hatch).

## Testing

- **Unit (`:feature:ar`)**: `composeCorrected` — with `V_current = pnpMat` and `fpAnchor = I`, result = I
  (identity round-trip); with a known translated/rotated triple, result matches a hand-computed matrix.
  `blend` — α=0 → current, α=1 → target, α=0.5 → halfway translation and a valid unit quaternion.
  Inlier-ratio gating — below threshold returns backbone; new-seq + above threshold moves toward target.
- **Native**: build-verified; real splat-confidence means sanity (0 when empty, within [0,1]).
- **On-device (deferred, via Sub-project A harness)**: with `fusionEnabled` on vs off, confirm reloc
  snap-back converges without teleport and lowers measured drift. This is the acceptance gate before the
  fusion is trusted as default in release.

## Risks

- **No on-device validation available now.** The correction math is unit-tested for frame-composition
  correctness, but real-world snap quality must be confirmed with the A harness on a device. Mitigation:
  ship behind `fusionEnabled`, math unit-tested, old path preserved.
- **Stale `V_current` in the reloc→consume gap.** Mitigated by composing in Kotlin with the freshest
  per-frame view matrix and smoothing the correction over frames.
- **Hardcoded PnP intrinsics** (`MobileGS.cpp:322`) remain a pre-existing accuracy limitation; out of
  scope here but noted (passing real intrinsics is a follow-up).

## Sequencing

This is **B** of A→B→C. **C** (tap-to-distance) will add per-tap depth readouts and feed tapped marks
into this fusion as metric support anchors via `AnchorOrchestrator.addSupportAnchor`.
