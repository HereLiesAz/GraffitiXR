# Teleological SLAM (Painting-Progress Correction)

Conventional relocalization treats the wall as a fixed target: capture a
doodle once, then match against it forever. That assumption breaks for a
muralist, because **the wall changes as you paint it** — the very marks the
doodle relies on get covered by the artwork, so matching gets *worse* the
further along you are.

GraffitiXR turns that around. Because the app already knows what the finished
piece is supposed to look like (the overlay you loaded), it treats the goal
state as additional information — hence *teleological* (goal-directed). The
further along the painting is, the more real-world corroboration the engine
has, and the more tightly the overlay locks to the wall.

## Mechanism

The work happens entirely in the native engine (`core/nativebridge`), layered
on top of the OpenCV relocalizer:

1. **Baseline doodle.** When the artist registers the wall, the engine
   stores ORB/feature descriptors of the clean surface (the relocalization
   doodle used by `relocThreadFunc` for snap-back).
2. **Progress measurement.** Two producers exist, and only one runs in a
   shipped build:
   - `core/nativebridge/src/main/assets/distortion_head.onnx` is bundled and
     `ArViewModel` loads it unconditionally on every AR session
     (`slamManager.loadDistortionHead`). When it is loaded — which is the
     default, out-of-the-box state — `MobileGS::runRelocPass` (the block
     guarded by `mDistortionHead.isLoaded()`) crops the live frame around the
     coarse match centroid, runs it against the wall's canonical patch, and
     reads `matchability` (`dist[11]`) off the model's output as the
     corroboration confidence, gated at `matchability > 0.5f`. Its `coverage`
     output is discarded (`(void)clampedCoverage`): it is the visible fraction
     of the ORIGINAL marks, which falls as they are painted over, so it is not
     progress. Progress always comes from `tryUpdateDoodle`. Because
     matchability is measured against those same original marks, it too falls
     as they are covered; `tryUpdateDoodle` therefore also computes the
     design-based descriptor ratio (matched / predicted-visible) on a gated
     attempt, and the published confidence is the stronger of the two. See
     `docs/DISTORTION_HEAD.md`.
   - `MobileGS::tryUpdateDoodle`'s descriptor-similarity test (below,
     "What matching the image does and does not mean") is the fallback: it
     only runs — and only then, via the `!mDistortionHead.isLoaded()` guard
     around the whole publication block — in a build with the ONNX asset
     stripped or missing. It is not what a normal install runs.
3. **Confidence weighting.** As `mPaintingProgress` rises, the corroborated
   marks contribute more to the pose solution, so global drift correction
   becomes more aggressive — the overlay "snaps" more tightly the more of the
   mural exists on the wall.

   Concretely: `ArRenderer` passes the CORROBORATION CONFIDENCE — not painting
   progress; `slamManager.getCorroborationConfidence()`, per the comment beside
   that call site — as `PoseFusion.currentAnchor`'s `confGlobal`, which scales
   the smoothing rate as
   `alpha = BASE_ALPHA * inlierRatio * (CONF_FLOOR + (1 - CONF_FLOOR) * confGlobal)`.
   The floor means a bare wall still corrects at half strength on the PnP inlier
   ratio alone. `PoseFusion`'s own doc is explicit that the "twice that" ceiling
   this formula implies is not reachable on any real wall — read it before citing
   the 2x figure as a property of the system rather than arithmetic at an input
   the system cannot produce.

   (Until 2026-07 this stage was **not wired**: `confGlobal` was pinned at `1f`
   with a comment about the retired voxel map, so progress reached the HUD and
   nothing else and correction strength was identical at 0% and 100% painted.)

4. **Self-grow.** Live features that pass the same corroboration test are
   promoted into the reloc doodle (`mSelfGrowEnabled`: the engine starts with it off and
   the app pushes the persisted setting, default **on**, switchable from the diagnostic overlay).
   Promoted marks are placed where they were SEEN and tagged OUTSIDE, so the reloc filter uses
   them, and relocalization survives the original marks being painted over. The grown rows
   are saved with the project (`grownDoodle`, `MobileGS::exportGrownWall`) and appended behind
   the captured Doodle on reload (`restoreGrownWall`), so what the Doodle learned outlives the
   session. The promotion
   gate is `MobileGS::growTrusted` — a strong inlier ratio qualifies at a lower
   absolute count, because a half-covered wall rarely reaches a large raw inlier
   count and the old flat `inliers >= 20` meant the doodle could only grow
   when it was already strong.

This is the inverse of the failure mode other tracing apps hit, where accuracy
degrades as the original reference marks disappear under paint.

## What "matching the image" does and does not mean

This section describes the **fallback** descriptor path
(`MobileGS::tryUpdateDoodle`), which only runs in a build without
`distortion_head.onnx` — not the shipped default; see the mechanism section
above. In that fallback, the corroboration test is **descriptor similarity,
not geometric accuracy**. A live feature corroborates the artwork when its
nearest neighbour among the design composite's descriptors passes a Lowe
ratio of 0.85 (`kCorrobLoweRatio`, `MobileGS::tryUpdateDoodle`) — a
looser test than relocalization's own 0.75 (`kRelocLoweRatio`), and, per
`PARAMETERS.md`, one that has not itself been validated against real
painted-wall photos. There is no positional tolerance, no scale or colour
check, and nothing anywhere compares your brushwork to the design
geometrically. Painting "more accurately" only helps insofar as it makes the
wall's local appearance descriptor-match the design image.

In the shipped default (distortion head loaded), corroboration is instead
whatever the ONNX model's `matchability`/`coverage` outputs encode — an
opaque, learned equivalent of the same idea, trained on synthetic
homographies with occlusion masks (`docs/DISTORTION_HEAD.md`), not on real
painted murals.

Tracking itself never consults the artwork at all: relocalization matches the
live camera against the **photograph of the wall taken at target creation**.

## Diagnosing it

Every failure in this chain used to be silent. `RelocDiagnostics` (surfaced by
the Diagnostic Overlay, in release as well as debug) reports which gate the last
attempt missed:

| State | Meaning |
|---|---|
| `NO_DOODLE` | no target created, or one with no 3D points — nothing to match |
| `NO_FEATURES` | live frame had no usable texture (light, focus, blur) |
| `FEW_MATCHES` | fewer than 8 correspondences survived the ratio test |
| `PNP_FAILED` | matches found, none geometrically consistent |
| `FEW_INLIERS` | PnP solved but fewer than 6 inliers agreed |
| `OK` | pose published; PoseFusion applies it if the inlier ratio ≥ 0.5 **and** reloc is the active correction source for that frame (see [Correction sources](#correction-sources)) |

### Correction sources

Two estimators can correct the anchor: this document's MobileGS PnP reloc, and — on a project with a
physically metric KPM page — SphereSLAM's timestamp-aligned hybrid KPM observation. Both write the one
standing anchor-local correction, so exactly one drives it on any frame
(`CorrectionSourceArbiter`, applied in `ArRenderer`):

1. a hybrid observation accepted this frame is applied;
2. otherwise, **while hybrid is fresh** (accepted within the last 3 s) the correction is **held** —
   reloc is not consulted, even when it has a new result, and even if the KPM page was just unloaded
   (the window, not the page, decides when reloc may take over);
3. otherwise reloc drives (when a capture pose exists and reloc is not gated, below): before hybrid
   has ever accepted, or after it has been silent for the whole 3 s window. With only a hybrid page and no capture pose the
   correction is held; with neither, the raw ARCore backbone is used.

The handover is one-way and timed — reloc until hybrid first accepts, hybrid while it keeps
accepting, reloc again only after a full silent window — so the correction never flips between the two
estimators frame by frame (they differ in latency and bias).

**Paint goes through.** The KPM page is a photo of the wall before the paint, so it degrades as the
wall is painted, while a reloc lock that stands on paint gets better. A lock with at least
`PAINT_BACKED_MIN_INLIERS` (8) inliers on paint marks or grown rows (`SlamManager.getRelocPaintInliers`,
fresh within `kRelocPriorMaxAgeMs`) drives whenever hybrid is not holding well: silent past the window,
or its last accepted observation below `HYBRID_DEGRADED_CONF` (0.5). Once paint has driven it keeps the
anchor the way hybrid does (held through frames without a paint-backed lock for the window; a hybrid
acceptance meanwhile does not take it back). Both constants are unmeasured. A re-capture resets the history. The 3 s
window is unmeasured; it belongs to the same eval sweep as the other fusion constants.

On a re-capture `PoseFusion.reset(seq)` also adopts the native reloc seq current at that instant as
already consumed, and every doodle replacement zeroes the latched result's counts, so the
pre-capture fix cannot cold-snap the new anchor.

That alone left a window: between the anchor being re-established and the NEW doodle being
installed, native keeps solving against the OLD doodle (the seq advances) while Kotlin holds the
old (or no) capture pose, so a later seq would cold-snap the new anchor onto the old wall. An explicit
**awaiting-new-doodle gate** closes it. `ArViewModel.setInitialAnchorFromCapture` raises it
(`SlamManager.beginDoodleReplacement`, which also drops `captureAnchorCam`); it is lowered only by
`completeDoodleReplacement`, which `MetricDoodleBuilder` calls after the new doodle is
installed natively and the new `captureAnchorCam` is computed (or a project load does after restoring
both), or by `clearWallDoodle`. While it is up `ArRenderer` ignores reloc results, the arbiter
never returns `RELOC` (`relocGated`), and fusion reports `NO_DOODLE`. Native independently drops
any solve that was in flight when the doodle changed (doodle generation, see
NATIVE_ENGINE.md), so a result computed against the old doodle can never be published after the
swap.

The overlay also shows how many features the live frame yielded *before* matching.
That disambiguates `FEW_MATCHES`, which means opposite things depending on it: a
handful of features in frame is a capture problem (light, focus, a blank wall),
while a thousand features that don't match is an aiming problem.

The reloc thread runs at 5 Hz once locked and ~16 Hz while hunting, since the
cost of an extra attempt is far smaller than the cost of the overlay staying
adrift. Once the anchor exists, the renderer offers every camera frame and
assembles only the ones the worker is ready for (`relocWantsFrame`), so the
worker's own pace is the real rate. Frames keep coming while ARCore has lost
tracking (out of a pocket, after screen-off), which is when the Doodle has to
find the wall.

After a loss, PoseFusion snaps to a spread-gated relock with at least
`COLD_SNAP_MIN_INLIERS` inliers instead of easing in. A weak fix that disagrees
with the standing correction still needs `DIVERGED_AGREEMENT` agreeing fixes
first; only the very first lock is exempt.

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
doodle's 3D structure is not the real wall, and no consistent PnP pose
exists over it.

Two details make this fit the observed "never locks" behaviour:

- The error **vanishes for a head-on wall** — a plane normal of `(0,0,±1)` is
  invariant under rotation about Z — and grows with obliquity. That matches a
  failure that feels intermittent rather than absolute.
- The onboarding doodle path (`buildDoodle`) uses the identical
  convention, so it would fail the same way.

**RESOLVED — Phase 0 (PR #1797).** The description above is of the pre-fix code;
the algebra in it is correct and matches the fix's independently-derived direction
(`d' = R_z(+90)·d`). `MetricMarks.glViewToCvDisplay` now rotates the capture view
to match the pixels and intrinsics, and `MetricDoodleBuilder.buildSingle`
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

## Anticipation: the wall as it will be seen

The doodle's KPM page is matched not only as captured but as the artist will see it: the
app renders the page from a half-sphere of viewpoints (Settings → Anticipation) and SphereSLAM learns
each as another image of the page, in every AR mode. Close-ups get the most: nearer half-spheres
repeat over a grid of aim points across the wall, since the artist is up close most of the time and
always when it matters, and throttling for battery, heat and load takes the far views first and the
close-ups last. See `feature/ar/.../anticipation/` and [SPHERESLAM_TODO.md](SPHERESLAM_TODO.md).

The ORB/SuperPoint doodle meets the view from the other side: a lost frame is turned by the
phone's sensors square-on to the wall before it is matched (the sensor turn, NATIVE_ENGINE.md §2b),
using the wall's normal learned at earlier locks and saved with the project.

## Relationship to the rest of the engine

- **Relocalization** (snap-back after tracking loss / screen-off) is the
  `relocThreadFunc` background thread — see [NATIVE_ENGINE.md](NATIVE_ENGINE.md)
  and [SLAM_SETUP.md](SLAM_SETUP.md).
- **Pose tracking** itself is provided by ARCore; the native engine layers
  relocalization and this teleological correction on top of ARCore's poses.
  There is no persistent voxel or splat map — see `NATIVE_ENGINE.md`.
- Pose smoothing/fusion lives in the Kotlin `PoseFusion` layer, not in C++.
- **Drift correction, self-grow and the feature map ship on by default**; each can be
  switched off from the diagnostic overlay (Settings > diagnostic overlay).

---
*Documentation updated on 2026-10-12: added Anticipation (half-sphere views of the KPM page, close-ups first). Earlier: 2026-09-04 (later pass): corrected the mechanism section — the bundled ONNX distortion head, not the descriptor-similarity test, is the producer of `mPaintingProgress`/`mCorroborationConfidence` in a normal (asset-present) install; the descriptor test only runs as a fallback when that asset is stripped. Earlier same-day pass corrected the confGlobal formula's input (corroboration confidence, not painting progress), self-grow's actual default (off, not on), the corroboration Lowe ratio (0.85, not 0.75), and removed a stale reference to the deleted voxel/splat map. Prior update: 2026-06-22, SLAM right-size and documentation-accuracy pass.*

## The reference marks and the design (current flow)

The artist taps their painted reference marks and the capture is masked to them, as before. Those
tapped marks are recorded per point as `Doodle.pinned`. When the design is placed,
`DoodlePartition` forces the pinned marks OUTSIDE (the backbone the relocalizer matches against)
wherever the design falls, instead of classifying them INSIDE just because the design is laid over
them. Any point that is not pinned is classified by where the design falls. A mark under the design
(INSIDE or BAND) stays in the relocalizer's solve until the paint grid has its cell painted over; only
then is it dropped, because only then is it no longer the doodle the artist drew. (The old rule
dropped every non-OUTSIDE mark as soon as a dozen OUTSIDE rows existed, and self-grow tags every grown
row OUTSIDE, so a few promotions threw away every mark inside the work area.)

Paint marks are matched in every reloc pass (plain, scaled, rectified, sensor-turned), never from a
live keypoint the Doodle already took, and, when a fresh prior pose exists, only near where that pose
predicts them. The persistent map joins a solve only with a fresh prior (published in the last
`kRelocPriorMaxAgeMs` with tracking in between) and only through points re-observed or carrying real
confidence.

Other behaviour worth knowing:
- A repartition (design nudged) keeps marks self-grow appended and the distortion head's patch: the
  Kotlin copy it re-pushes is only the base, and native treats "base is a prefix of what I hold" as
  the same doodle. The staged Doodle build (photo features, then close-ups) is treated the same way:
  each stage is the previous plus rows, and keeps the grown tail and the partition.
- Grown rows are capped at `kMaxGrownRows` behind the captured Doodle; past it, the grown rows no lock
  has seen for longest make room, so self-grow never stops. The captured rows are never evicted.
- If the design was registered in another descriptor type than the Doodle (`GrowOutcome.TYPE_MISMATCH`),
  the app registers it again; paint marks of the old type are dropped then.
- Corroboration needs `kCorrobConfirmations` CONSECUTIVE hits for a feature predicted in view;
  an observed miss resets an unconfirmed feature. Moving the design re-projects corroboration: a
  feature whose wall spot moved less than `kRho` of the design's extent keeps its count (and promoted
  flag); one that moved further starts over. Paint marks already on the wall are kept. The counts are
  saved in the paint-grid blob and resume on reload of the same design.
- Tone and opacity are how the overlay looks, not what the artwork is: the painting guide is the
  design at full opacity and neutral tone, and a tone edit never re-registers it (only the source
  bitmap, scale or rotation do). Paint marks store the LIVE descriptor that corroborated them.
- While no ARCore anchor tracks, the backbone holds its last good pose. A relock in that time is a
  correction against the held pose; when the anchor resumes, that correction is re-based
  (`PoseFusion.rebase`) so the overlay stays where it was drawn. A correction untouched during the
  hold carries over as it is.
- `PoseFusion` believes a diverged, not-high-confidence fix only after three consecutive agreeing
  fixes, and a cold snap needs the inliers spread across the frame (`reloc[35]`, published with the
  pose).
- Return-visit anchors are refused when the solved wall's orientation disagrees with the saved wall
  normal (`WallOrientationCheck`).
