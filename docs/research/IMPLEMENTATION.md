# Implementation plan — wiring the hybrid relocalizer

Companion to [`PAPER.md`](PAPER.md). The paper argues *what* to build and *why*;
this document says *where the code goes*, *what it is called*, *what breaks it*,
and *how you know it worked*. It assumes the paper's vocabulary — the **footprint
operator** Φ, the **backbone set** `F_out`, the **corroboration set** `F_in`.

Everything cited as "already fixed" landed in PRs #1785–#1788 and is on `main`.
Since then Phase 1, Phase 5a and three of Phase 6a's four todos have landed;
Phase 0 itself is still unbuilt and still gates Phases 2, 3, 4 and 5b. See
[`README.md`](README.md) "Status" for the current picture — this line said
"nothing below Phase 0 is built" long after that stopped being true.

---

## 0. Ground rules for this work

These are constraints on the engineering, not on the design. They are listed
first because every phase inherits them.

**No compiler in the dev container.** There is no Android SDK here; `./gradlew`
dies with "SDK location not found". The only compile check is CI, and CI runs
both the Kotlin/JVM unit tests *and* the NDK build. Those are two independent
gates and one can pass while the other fails — that has already happened once in
this project's history (`int matches` shadowed a `std::vector<...> matches` in
`tryUpdateFingerprint`; the Kotlin tests were green on the same commit). **Do not
report a native change as verified on the strength of a green unit-test run.**

**Pure math goes in Kotlin objects with no Android imports.** That is the
existing convention — `PoseMath`, `Triangulation`, `PlaneMarks`, and
`PoseFusion`'s companion are all testable because the geometry was hoisted out of
the Android-dependent callers. Every new formula in this plan follows it. If a
piece of math cannot be unit-tested, it is in the wrong file.

**One behavioural change per commit, each behind a readable flag where it can
be.** The relocalizer is a feedback loop: a change to the fingerprint contents
changes the match count, which changes the inlier ratio, which changes whether
`PoseFusion` snaps, which changes the pose the next fingerprint update is built
from. Landing two changes together makes the eval uninterpretable.

**Do not touch `PoseFusion.currentAnchor`'s composition and
`PlaneMarks.backProject`'s frame convention in separate commits.** They are two
halves of one convention. Phase 0 exists because of this.

---

## Phase 0 — Resolve the display-rotation convention

**Status: prerequisite. Gates every phase that consumes 3D wall points — 2, 3, 4,
and 5b. Does not gate Phase 1 (pure math on points supplied by the caller), 5a,
or 6a.**

### The defect

`MetricFingerprintBuilder` and the capture path rotate the captured bitmap *and*
the intrinsics into display orientation, then hand `PlaneMarks.backProject` a
view matrix that has **not** been rotated. So the rays are built in a frame
rotated about the optical axis by `R_z` relative to the plane they are
intersected with: `d' = R_z · d`.

This is invisible on a head-on wall. A plane normal of `(0,0,±1)` in the camera
frame is invariant under a rotation about `Z`, so the ray-plane intersection
`t = (n·P)/(n·d)` is unchanged. The error grows with obliquity, which is exactly
the condition a street artist works in — you cannot stand square to a wall you
are painting.

The same rotation question lands on `PoseFusion.composeCorrected`:

```kotlin
fun composeCorrected(vCurrent: FloatArray, pnpMat: FloatArray, fpAnchor: FloatArray): FloatArray =
    PoseMath.multiply(PoseMath.multiply(PoseMath.rigidInverse(vCurrent), pnpMat), fpAnchor)
```

`pnpMat` comes out of the native solver in the frame the 3D points were *stored*
in; `vCurrent` is the live ARCore view. If the stored points carry a baked-in
`R_z` and the live view does not, the composition is wrong by that same rotation
— and it is wrong in a way that partially cancels the back-projection error,
which is why the system limps rather than failing outright.

### Why it must be settled before any 3D-consuming phase

**Phase 2 (partition).** Φ classifies the very 3D points `backProject` produced.
Skewed points land in the wrong region, in proportion to capture obliquity, and
the partition — the structural claim of the whole design — is then computed on
geometry that is wrong in a way nothing downstream can detect.

**Phase 4 (corroboration).** The search radius is derived from a projected
prediction. A skewed point predicts the wrong pixel, so the local search looks in
the wrong place, and a low corroboration rate would be read as the mechanism
failing rather than the frame convention.

**Phase 3 (promotion), worst.** Promotion writes new observations into the
fingerprint using the current relocalized pose (`mPnpCamFromFpWorld` in
`tryUpdateFingerprint`). If the pose carries a rotation error, promotion *writes
that error into the map* and it compounds — a static bias becomes a divergent one.

The general form, which is §8 of the paper's point: any failure measured on top
of an unresolved convention is unattributable between the new mechanism and the
old bug. That is why Phase 0 lands before Phase 2 in the order below, despite
being the slowest and most careful piece of work in the plan.

### The decision to make

There are two self-consistent conventions. Pick one and enforce it everywhere:

| | **A — everything in sensor frame** | **B — everything in display frame** |
|---|---|---|
| Pixels fed to detector | un-rotate back to sensor | rotated (as today) |
| Intrinsics | sensor `fx,fy,cx,cy` | rotated (as today) |
| View matrix | as today (unrotated) | pre-multiply by `R_z` |
| Stored 3D points | sensor camera frame | display camera frame |
| `composeCorrected` | unchanged | unchanged, but `fpAnchor` built in the same frame |
| Cost | one extra rotate of the pixel list per capture | one 4×4 multiply per capture |
| Risk | the detector runs on a rotated image; keypoint orientation histograms shift | must find *every* site that consumes the capture view |

**Recommendation: B.** The pixel path already works in display orientation
throughout, including the OES texture and `transformCoordinates2d`. Option A
means un-rotating the image the whole rest of the pipeline agrees on. B is one
matrix, applied at one place, and the test that proves it is cheap.

### How to prove it without a device

The test is synthetic and lives entirely in the JVM. Construct a virtual wall
plane at a known metric pose, a virtual camera at a known oblique angle, project
a set of known 3D points to pixels analytically, feed those pixels through
`PlaneMarks.backProject` with the *rotated* intrinsics and the candidate view
convention, and assert the recovered 3D points match the ground truth to <1 mm.

Run the same test at obliquity 0°, 20°, 40°, 60°. The current code passes at 0°
and fails progressively at the others — that failure *is* the reproduction of the
bug, and the same test becomes the regression guard.

### Files

| File | Change |
|---|---|
| `feature/ar/.../anchor/PlaneMarks.kt` | Delete the CAUTION block once resolved; document the chosen convention as a contract on `backProject`. |
| `feature/ar/.../anchor/MetricMarks.kt` | Add `glViewToCvDisplay(glView, rotationDeg)` — `glViewToCv` then a `R_z` pre-multiply. Keep `glViewToCv` untouched so the triangulation path is unaffected. |
| `feature/ar/.../anchor/MetricFingerprintBuilder.kt` | Route capture views through the new converter; store the rotation used alongside the fingerprint. |
| `feature/ar/.../ArViewModel.kt` | Pass `displayRotation` (already available via `DisplayRotationHelper`) to the capture path. |
| `core/common/.../model/Fingerprint.kt` | Add `captureRotationDeg: Int` so a fingerprint persisted under one convention is not silently reloaded under another. Default `-1` = legacy/unknown → refuse to reload, force recapture. |

### Exit criteria

- `PlaneMarksObliquityTest` passes at 0/20/40/60° with <1 mm error.
- A fingerprint persisted before this change is rejected on load with a
  user-visible "re-capture your target" message rather than silently misused.
- On device: the reloc inlier *ratio* at 40° obliquity improves. Ratio, not
  count — count moves with texture, ratio moves with geometry.

---

## Phase 1 — The footprint operator Φ

**Depends on: nothing. Do this first; it is pure math and it unblocks 2–4.**

### What it is

Φ maps a world point to the design's own normalized coordinates:

```
Φ(x) = ( (M⁻¹x)_x / h_w ,  (M⁻¹x)_y / h_h )
```

where `M` is the anchor model matrix and `h_w`, `h_h` are the overlay half-extents
already maintained by `OverlayRenderer.setExtent(halfW, halfH)`. Inside the
design, Φ lands in `[-1,+1]²`. Outside it, the magnitude says how far outside, in
units of the design's own size — which is the number every downstream decision
actually wants.

Φ is the single piece of machinery the whole hybrid rests on. It is what lets the
system ask "is this feature *on* the artwork?" — the question the appearance-only
design could never ask.

### Where it goes

New file: `feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/anchor/Footprint.kt`

```kotlin
object Footprint {
    /** Normalized design coordinates of a world point. (0,0) = design centre, ±1 = design edge. */
    fun of(anchorModel: FloatArray, halfW: Float, halfH: Float,
           x: Float, y: Float, z: Float): FloatArray

    /** True when the point lies within the design's rectangle, dilated by [margin] (in Φ units). */
    fun isInside(uv: FloatArray, margin: Float = 0f): Boolean

    /** Chebyshev distance from the design rectangle; 0 inside, grows outside. */
    fun outsideDistance(uv: FloatArray): Float

    /** Classify a point into the backbone set, the corroboration set, or the discard band. */
    fun classify(uv: FloatArray, innerMargin: Float, outerMargin: Float): Region
    enum class Region { INSIDE, BAND, OUTSIDE }
}
```

The `BAND` region matters and is easy to skip. Features within a hair of the
design's edge are the ones whose classification flips as the artist paints to the
boundary — they belong to neither set and should be discarded rather than
assigned. `innerMargin` shrinks the inside test, `outerMargin` grows the outside
test, and the gap between them is the discard band.

#### `M` is not rigid — do not use `rigidInverse`

`Footprint.of` needs a 4×4 inverse, and the obvious move is `PoseMath.rigidInverse`,
which already exists and is already tested. **It is the wrong function here, and
using it silently inverts the partition.**

`M` is `overlayComposedScratch` (`ArRenderer.kt:1763`), built as
`overlayBaseScratch · overlayLocalScratch` — and `overlayLocalScratch` ends with

```kotlin
Matrix.scaleM(overlayLocalScratch, 0, overlayScale, overlayScale, 1f)
```

where `overlayScale` is the user's pinch gesture (`MainScreen.kt:236`). So `M`
carries a similarity scale, not just rotation and translation.

`rigidInverse` inverts the linear part by **transposing** it, which is the inverse
only when there is no scale. For `A = R·S` with `S = diag(s, s, 1)`, the transpose
gives `S·Rᵀ` where the true inverse is `S⁻¹·Rᵀ` — off by a factor of `s²` on the
`x` and `y` components, plus a wrong translation. At `overlayScale = 2`, a feature
sitting exactly on the design edge yields `‖Φ‖∞ = 4`, gets classified `OUTSIDE`,
and is promoted into the backbone — the set defined as *wall that never gets
painted*. Every feature under the artwork lands in `F_out`. That is precisely the
corruption the partition exists to prevent, produced by the partition's own
inverse.

**Do this instead.** Decompose rather than inverting the composite: `Φ` only needs
the *rigid* part of the anchor and the scale separately, because the scale is
already accounted for by the half-extents. Either

- pass the **rigid factor** as `M` and fold `overlayScale` into the effective
  half-extents (`h_w·s`, `h_h·s`) — cheapest and exact. Note the rigid factor is
  `overlayBaseScratch · T(pan) · Rz(overlayRotationDeg)`, **not**
  `overlayBaseScratch` alone: `overlayLocalScratch` is
  `T(markOffset+pan) · Rz(spin) · S(s,s,1)`, so passing the base by itself
  silently drops the user's pan and in-plane spin and offsets every Φ by exactly
  those — a feature dead-centre on the artwork would report non-zero `‖Φ‖∞` and,
  past the margin, land in the backbone. That is the same corruption `ofComposed`
  exists to prevent, arriving through the recipe this bullet recommends; or
- add `PoseMath.similarityInverse(m)`, which extracts `s` from the first column's
  norm, transposes, and divides by `s²`. Needed anyway if any caller only has the
  composed matrix.

Prefer the first. It keeps `Φ` a pure rigid-frame operation and makes the scale
dependency explicit at the call site instead of buried in an inverse.

### Tests — `FootprintTest.kt`

1. Identity anchor, unit extents: world `(0,0,0)` → `(0,0)`; `(h_w,h_h,0)` → `(1,1)`.
2. Translated anchor: Φ is invariant to where the design sits in the world.
3. Rotated anchor (45° about the plane normal): a point on the design's local +X
   axis still maps to `(+1, 0)`, not to a rotated pair. This catches using the
   forward transform where the inverse was needed — the most likely bug here.
4. Non-square extents (`h_w = 2·h_h`): both axes still saturate at 1 at their own
   edge. Catches dividing both axes by the same half-extent.
5. `classify` with `innerMargin=0.05, outerMargin=0.15`: a point at Φ radius 0.90
   is `INSIDE` (the inside test is `d ≤ 1 − innerMargin = 0.95`, so 0.98 is
   **`BAND`**, not `INSIDE` — an earlier draft of this line had it wrong), 1.10 is
   `BAND`, 1.30 is `OUTSIDE`. Assert the boundaries themselves too: 0.95 is
   `INSIDE` and 1.15 is `OUTSIDE`, which pins the comparisons as inclusive.
6. Off-plane point: a point 0.5 m in front of the wall projects to the same `(u,v)`
   as its foot. Document this explicitly — Φ deliberately discards the normal
   component, and a reader will wonder.
7. **Scaled anchor.** With `overlayScale = 2`, a point on the design's edge still
   maps to `‖Φ‖∞ = 1`, not 4 and not 0.25. This is the case that catches the
   `rigidInverse` trap above, and without it the suite ships that bug green.

### Risk

Low. Pure function, no callers yet. The only way this hurts is if the extents it
reads are stale; see Phase 2's note on capture-time snapshotting.

---

## Phase 2 — Partition the fingerprint at capture

**Depends on: Phase 1.**

### What changes

Today `MetricFingerprintBuilder.build` produces one undifferentiated
`Fingerprint`: descriptors plus 3D points, all treated identically by the reloc
PnP. After this phase it produces the same points, each **tagged** with its
region.

The tag is what makes the paper's central asymmetry expressible:

| | `F_out` (backbone) | `F_in` (corroboration) |
|---|---|---|
| Where | outside the design footprint | inside it |
| Expected lifetime | stable — nobody paints there | decaying — it is the work surface |
| Feeds reloc PnP | **yes** | no |
| May assume a prior pose | **no** | **yes** |
| Purpose | recover pose from nothing | confirm and refine a pose you already have |

The load-bearing row is the last-but-one. `F_out` must solve the global problem —
no prior, unconstrained search. `F_in` never has to: by the time you consult it
you already have a pose from `F_out`, so its search is local. That is what makes
the inside features usable at all despite being cross-modal and decaying.

### Signature changes

`core/common/.../model/Fingerprint.kt` — add a parallel `regions: ByteArray`
(one byte per 3D point, values from `Footprint.Region.ordinal`). A parallel array
rather than a struct-of-arrays rewrite: the descriptors are already an
opaque row-major `Mat` blob and the points a flat `FloatArray`, and adding a
third parallel array is consistent with that and cheap to serialize.

`MetricFingerprintBuilder`:

```kotlin
fun build(
    slam: SlamManager,
    kf0: Keyframe, kf1: Keyframe,
    anchorModel: FloatArray,
    overlayHalfW: Float, overlayHalfH: Float,   // NEW
    minPoints: Int = 20,
    ratio: Float = 0.75f,
): Fingerprint?
```

and the same two parameters on the single-view `PlaneMarks` path.

**Snapshot the effective footprint at capture — and watch the right variable.**
The regions are only meaningful against the design's size at the moment they were
computed, so store that size in the `Fingerprint`. But be precise about what
actually changes when the user resizes the design:

`OverlayRenderer.setExtent` has exactly two call sites — `ArRenderer.kt:499` (a
fixed constant at surface-created) and `ArRenderer.kt:1664` (a one-shot initial
screen-fit, guarded by `quadInitialFitApplied`). **Neither is driven by the user.**
The user's resize is `overlayScale` (`ArRenderer.kt:369`, set from
`MainScreen.kt:236`), and it enters the *model matrix*, not the extents.

So a `reclassify(anchorModel, halfW, halfH)` that watches the extents would
recompute regions from two numbers that never moved, while the quantity that did
move sits in the transform. Store and compare **`overlayScale`** — or, following
the Phase 1 recommendation, store the effective half-extents `h_w·s`, `h_h·s`,
which folds both into one number per axis and makes the staleness check a single
float comparison. Recompute regions from the stored 3D points when it changes;
that is a cheap pure-Kotlin pass and much better than forcing a recapture, because
pinching the design is a normal thing to do mid-session.

### Native side

`SlamManager.restoreWallFingerprintMetric` gains a `regions: ByteArray`
parameter; `MobileGS` stores it as `std::vector<uint8_t> mWallRegions` alongside
`mWallKeypoints3D` and filters the reloc correspondence build to
`INSIDE`-excluded rows.

Guard the arity: a legacy persisted fingerprint has no regions. Treat a
zero-length `regions` as "all backbone", which reproduces today's behaviour
exactly. That makes Phase 2 a no-op for old data instead of a crash.

### Tests

- `FingerprintPartitionTest`: given a synthetic point set straddling the design
  edge and a known anchor, the partition assigns the expected counts.
- `RelocDiagnosticsTest` extension: `matches` must now be reported against the
  backbone-only correspondence count, not the total. Add an assertion that the
  two are separately readable — the same shortfall means different things.
- A round-trip serialization test: persist and reload a partitioned fingerprint,
  assert regions survive and the legacy-empty path is honoured.

### Risk

**Medium, and it is the obvious one: `F_out` may be empty.** If the design covers
the entire visible wall, there are no outside features and reloc has nothing to
work with. This is stated in the paper as a limit of the domain of applicability,
and the implementation must make it *legible* rather than mysterious:

- Report the backbone count in `RelocDiagnostics` (add `backboneFeatures`).
- If `F_out` is below a floor at capture time, refuse the capture with a specific
  message: "step back — the target needs some wall around it to lock onto."
  Compare `MainViewModel`'s existing use of
  `MetricFingerprintBuilder.lastDetected/lastPlaced/lastRequired` for the pattern.

### Rollback

Set the partition to "everything is backbone" via a single constant. Behaviour
returns to `main` exactly.

### Exit criteria

- Legacy fingerprints load and relocalize identically (byte-identical inlier
  counts on a replayed recording).
- A newly captured fingerprint reports non-zero `F_out` and `F_in` counts.
- Capture on a wall-filling design is refused with the specific message.

---

## Phase 3 — Geometric promotion

**Depends on: Phase 0 (hard), Phase 1, and Phase 2. Lands last.**

### What changes

`MobileGS::tryUpdateFingerprint` already contains a self-grow path, gated off by
`mSelfGrowEnabled`. It promotes new clean-frame features into the live
fingerprint by placing them on the wall plane via the current relocalized pose.
The gate `growTrusted(inliers, matches)` is:

```cpp
static bool growTrusted(int inliers, int matches) {
    if (inliers >= 20) return true;
    if (inliers < 10 || matches <= 0) return false;
    return (float)inliers / (float)matches >= 0.6f;
}
```

Two changes:

**3a — promote only into the backbone.** Today a promoted feature is
indistinguishable from an original. Under the partition, a promoted feature must
be assigned a region via Φ *at promotion time*, and only `OUTSIDE` promotions
enter the reloc set. A promoted `INSIDE` feature is wet paint: it is going to
change again. Promote it into `F_in` where a short lifetime is expected, or drop
it.

**3b — cap promotion by pose quality, not just inlier count.** `growTrusted`
measures the *match*, not the *pose*. A tight inlier ratio on a small, clustered
correspondence set produces a confident-looking pose with a poorly conditioned
rotation. Add a spread term: the promotion is only trusted if the inliers span a
minimum fraction of the frame. Cheap to compute — bounding box of the inlier
image points over the frame area — and it kills the specific failure where all
inliers land in one textured corner.

### Files

- `core/nativebridge/src/main/cpp/MobileGS.cpp` — `tryUpdateFingerprint`;
  `growTrusted` gains an `inlierSpread` argument.
- `core/nativebridge/src/main/cpp/include/MobileGS.h` — `mWallRegions`.
- New native unit coverage is awkward; instead expose `growTrusted`'s decision
  through `RelocDiagnostics` so the eval harness can log it, and port the
  *predicate* to Kotlin (`PromotionGate.kt`) with tests, keeping the C++ a
  transliteration. That is the same trick `PlaneRenderer.isCoplanar` uses —
  hoisted into the companion so it is testable.

### Risk

**High. This mutates the authoritative map.** It is the only phase that can make
the system worse permanently rather than transiently. Keep it behind
`setSelfGrowEnabled` — default off — through the entire evaluation, and only flip
the default once E5 (see `EVALUATION.md`) shows a positive effect on a replayed
dataset.

### Rollback

`setSelfGrowEnabled(false)`. Already exists.

---

## Phase 4 — Spatially-constrained corroboration

**Depends on: Phase 0, Phase 1, and Phase 2. Independent of 3.**

### What changes

This is where the hybrid actually pays. Once `F_out` has produced a pose, each
`F_in` feature has a *predicted* image location. Matching becomes local:

1. Project each `F_in` 3D point through the current pose → predicted pixel `p̂ᵢ`.
2. Consider only detected keypoints within radius `r` pixels of `p̂ᵢ`, where `r`
   is derived from the pose uncertainty and `ρ` (the paper's normalized search
   radius) via the design's on-screen scale.
3. Run the ratio test **within that candidate set only**.

The consequence, from the paper: at `ρ = 0.05` the candidate set is about 0.2% of
the design's features. False-positive matches collapse — and because they
collapse, the Lowe ratio threshold can be *loosened* from 0.75 toward 0.85–0.9,
recovering true matches that a global search had to throw away. This is the one
place in the system where a constraint buys back recall instead of costing it.

That inversion is the phase's whole justification, so the eval must measure both
sides of it (E6/E7).

### Files

- `core/nativebridge/src/main/cpp/MobileGS.cpp` — `tryUpdateFingerprint`'s
  `knnMatch` becomes a gated match. Simplest correct implementation: bucket the
  frame's keypoints into a uniform grid sized to `r`, then for each `F_in` point
  gather the 9 neighbouring cells. No new dependency, `O(n)` build, and it beats a
  `cv::flann` index for the sizes involved (~1500 keypoints).
- `feature/ar/.../anchor/SearchRadius.kt` — pure Kotlin computation of `r` from
  `ρ`, the design's half-extents, the pose's translation distance and the
  intrinsics. Tested; transliterated into C++.

### Tests

- `SearchRadiusTest`: `r` scales inversely with distance to the wall, linearly
  with focal length, linearly with `ρ`. Assert on ratios, not absolutes.
- A grid-bucketing test in Kotlin against a brute-force reference on random
  points: identical candidate sets for a range of radii. Port the same fixture
  values into a native smoke assertion.

### Risk

Medium. The failure mode is a search radius that is too tight after a pose has
drifted, which starves corroboration exactly when it is most needed. Mitigate by
scaling `r` with the *measured* recent pose error (already available as the
`DriftCostProbe` `errMm` channel), not a constant. Floor and ceiling it.

### Exit criteria

- Corroborated-feature count at fixed painting progress rises relative to the
  global-search baseline on a replayed dataset.
- The loosened ratio threshold does not raise the reloc reprojection error.

---

## Phase 5 — Separate progress from pose error

**5a depends on nothing — land it early. 5b depends on Phases 2 and 4.**

### The defect

`ArRenderer` passes painting progress into `PoseFusion` as the corroboration
confidence:

```kotlin
confGlobal = slamManager.getPaintingProgress(),
```

and `PoseFusion` uses it to scale correction strength above `CONF_FLOOR`. But
`getPaintingProgress()` is doing two jobs at once. In `tryUpdateFingerprint` it is
`matched / artDescs.rows` — the fraction of the *design's* features the wall
answers for. That number goes **up** as the artist paints, which is the intended
"the further along, the tighter it locks" behaviour. It also decays by `×0.9`
per tick whenever the distortion head is loaded but produces nothing, which is a
*tracking* signal wearing a *progress* signal's clothes.

A single scalar cannot mean both "the mural is 60% painted" and "I am currently
confident in the pose". They move on completely different timescales — progress
over hours and monotonically, pose confidence over frames and in both directions.
Collapsing them means a momentary tracking hiccup reads as the mural being
un-painted, and the `×0.9` decay makes that stick around for seconds.

### What changes

Two independent published signals:

| Signal | Meaning | Timescale | Consumer |
|---|---|---|---|
| `paintingProgress` | fraction of `F_in` the wall now answers for | hours, ~monotonic | UI, `ArViewModel` |
| `corroborationConfidence` | fraction of *currently predicted-visible* `F_in` matched this frame | frames, bidirectional | `PoseFusion.confGlobal` |

`PoseFusion` takes the second. It is the one that actually answers "should I
trust this correction more than the inlier ratio alone suggests?"

The denominator change matters as much as the split. Today's denominator is
`artDescs.rows` — *every* feature of the design, including the parts behind the
camera. A close-up of one corner can never score above the fraction of the design
that corner represents, so the confidence is capped by framing rather than by
agreement. Phase 4's projection step already computes which `F_in` points are
predicted visible; use that count as the denominator.

### Files

- `core/nativebridge/src/main/cpp/MobileGS.cpp` — add
  `mCorroborationConfidence` atomic; stop overloading `mPaintingProgress`; remove
  the `×0.9` decay from the progress channel and apply it (or better, a proper
  per-frame recompute) to the confidence channel only.
- `core/nativebridge/.../SlamManager.kt` — `getCorroborationConfidence()`.
- `feature/ar/.../rendering/ArRenderer.kt` — `confGlobal =
  slamManager.getCorroborationConfidence()`.
- `feature/ar/.../ArViewModel.kt:1469` — keeps `getPaintingProgress()`; that call
  site is correct as-is and is the reason the two must be split rather than one
  being renamed.

### Tests

`PoseFusionTest` extension: assert that `confGlobal` at the floor still produces a
non-zero correction (the existing `CONF_FLOOR` contract), and that a confidence
drop cannot reverse a correction — only slow it.

### Risk

Low, and it is the highest value-per-risk item in the plan. Do it early.

---

## Phase 6 — Telemetry so the evaluation is possible at all

**6a depends on nothing — land it early. 6b lands with each phase that sources its columns.**

`EVALUATION.md` cannot run without these channels. Extend the CSV.

**Do not restate the header here.** An earlier version of this section
hand-copied the 16-column header as "currently:", and the commit whose headline
change was *"header and row now derive from one `COLUMNS` list"* added four
columns without updating this third copy — inside the same commit. The list lives
in `EvalSampleLog.COLUMNS`; read it there.

Columns to add:

| Column | Source | Why the eval needs it |
|---|---|---|
| `backboneFeatures` | `F_out` size | Denominator for every reloc rate; also the wall-filling-design guard. |
| `backboneMatches` | reloc correspondences | Separates "no texture" from "wrong aim". |
| `backboneInliers` | RANSAC survivors | The ratio `PoseFusion` gates on. |
| `inlierSpread` | Phase 3b | Distinguishes a conditioned pose from a lucky cluster. |
| `corrobPredicted` | Phase 4 step 1 | Denominator of `corroborationConfidence`. |
| `corrobMatched` | Phase 4 step 3 | Numerator. |
| `paintingProgress` | Phase 5 | The independent variable in every decay experiment. |
| `relocReject` | existing `RelocDiagnostics.reject` ordinal | Turns a null result into a diagnosis. |
| `searchRadiusPx` | Phase 4 | The swept parameter must be in the log that measures it. |

`RelocDiagnostics` already carries `reject`, `matches`, `inliers`, `detected`,
`obliquityDeg`, `rectifiedCorrespondences` and is already surfaced in release
builds through `RelocDiagnosticsOverlay`. Extend that record rather than adding a
parallel channel; the overlay is the fastest on-device debugging surface in the
project and every new number should appear there.

---

## Dependency graph

Two phases split, because each has a part that is genuinely independent and a
part that is not:

- **5a** — publish `corroborationConfidence` as its own channel, move the ×0.9
  decay off the progress channel. Keeps today's `artDescs.rows` denominator.
  Depends on nothing.
- **5b** — change the denominator to *predicted-visible* `F_in`. Depends on 2 and 4.
- **6a** — the telemetry plumbing: sidecar metadata, the CSV-shape test, and the
  columns whose sources already exist (`relocReject`, the existing
  `RelocDiagnostics` fields). Depends on nothing.
- **6b** — the columns sourced from new phases (`backbone*` from 2, `inlierSpread`
  from 3, `corrob*` and `searchRadiusPx` from 4). Each lands *with* its phase.

```
Phase 1 (Φ) ─────────────────────────────┐
                                         ▼
Phase 0 (rotation) ──► Phase 2 (partition) ──► Phase 3 (promotion)
                            │                       │
                            ├──► Phase 4 (local corroboration)
                            │         │
                            │         └──► Phase 5b (visible denominator)
                            │
                            └──► Phase 6b (partition columns)

Phase 5a (split signals) ── independent
Phase 6a (telemetry plumbing) ── independent
```

**Phase 0 gates Phase 2, not only Phase 3.** Φ classifies 3D points that
`PlaneMarks.backProject` produced. If those points are skewed by the rotation
convention, the partition is computed on skewed geometry and a feature's region
is wrong in proportion to capture obliquity. This is what §8 of the paper means
by "prerequisite, not a parallel task" — an earlier draft of this document said
Phase 0 gated only Phase 3, which contradicted the paper and would have put every
E4–E8 measurement on an unattributable footing.

Recommended landing order, which is not the numeric order:

**1 → 5a → 6a → 0 → 2 (+6b) → 4 (+6b) → 5b → 3**

Rationale: Phase 1 is free, pure, and unblocks everything. 5a is the best
risk-adjusted fix in the plan and needs nothing. 6a makes every later change
measurable. Phase 0 then lands *before* any 3D-consuming work, per the paragraph
above. Phase 2 is the structural change and carries its own telemetry columns
with it; Phase 4 is the payoff; 5b closes out the denominator once its inputs
exist. Phase 3 is the only phase that can do lasting damage, so it goes last.

---

## Granular todo list

Each item is one commit-sized unit of work. Checkboxes are for tracking across
sessions. `[T]` = has a test that must be written with it. `[N]` = touches native
code, so CI's NDK build is the only compile check.

**Sections are in landing order (1 → 5a → 6a → 0 → 2 → 4 → 5b → 3), not numeric
order.** Work top to bottom.

### Phase 1 — footprint operator

- [x] **1.1** Create `feature/ar/.../anchor/Footprint.kt` with `Region` enum and
      empty `of` / `isInside` / `outsideDistance` / `classify` signatures.
- [x] **1.2** Implement `Footprint.of`. **Do not use `PoseMath.rigidInverse`** —
      the composed anchor carries `overlayScale`; take the rigid factor
      (`overlayBaseScratch`) and fold the scale into the half-extents instead.
      Return through a caller-supplied buffer overload to avoid per-feature
      allocation in the hot path. **[T]**
- [x] **1.3** Implement `isInside` and `outsideDistance` (Chebyshev). **[T]**
- [x] **1.4** Implement `classify` with the inner/outer margin discard band. **[T]**
- [x] **1.5** Write `FootprintTest.kt` — all seven cases from Phase 1 above. The
      rotated-anchor, non-square-extent, and **scaled-anchor** cases are the three
      that catch real bugs; the scaled-anchor case is non-optional. **[T]**
- [x] **1.6** Document in the KDoc that Φ discards the plane-normal component,
      with the reason.

### Phase 5a — split progress from confidence (no new dependencies)

- [x] **5a.1** Add `mCorroborationConfidence` atomic to `MobileGS.h`. **[N]**
- [x] **5a.2** In `tryUpdateFingerprint`, compute and publish corroboration
      confidence separately from `mPaintingProgress`. Keep today's
      `artDescs.rows` denominator for now — 5b changes it. **[N]**
- [x] **5a.3** Remove the `×0.9` decay from the painting-progress channel; leave
      progress as a stored fraction that only a real measurement changes. **[N]**
- [x] **5a.4** Apply decay (or a per-frame recompute) to the confidence channel
      only. **[N]**
- [x] **5a.5** Add `SlamManager.getCorroborationConfidence()`.
- [x] **5a.6** Switch `ArRenderer`'s `confGlobal` to the new getter; leave
      `ArViewModel:1469` on `getPaintingProgress()`.
- [x] **5a.7** Extend `PoseFusionTest`: floor confidence still corrects; a
      confidence drop slows but never reverses a correction. **[T]**

### Phase 6a — telemetry plumbing (no new dependencies)

- [x] **6a.1** Add the run-identity sidecar JSON next to every `DriftCostProbe`
      CSV: recording hash, git commit, all parameter values, RNG seed, sync/async
      mode, device model. A CSV without a sidecar is not evidence.
- [x] **6a.2** Add a CSV-shape test: header column count equals the emitted row's
      field count. This is the class of bug that silently corrupts every
      downstream analysis. **[T]**
- [x] **6a.3** Add the `relocReject` ordinal column — its source already exists.
- [ ] **6a.4** Add the eval-only fixed RNG seed and the synchronous-reloc mode
      from `EVALUATION.md` §3.1. Both must be inert in release builds. *(The
      sidecar already records `rngSeed` and `syncReloc` so a run states which it
      used; the native plumbing that honours them is still outstanding, and until
      it lands every replay A/B carries un-quantified RANSAC variance.)*

### Phase 0 — rotation convention

- [x] **0.1** Write `PlaneMarksObliquityTest` against a synthetic wall + camera at
      0/20/40/60°. **Delivered as passing characterization rather than a red
      reproduction**: a permanently-failing test cannot live in CI, so the
      magnitudes are asserted as ranges that hold *because* the defect is present,
      each marked with what to invert when Phase 0 lands. Ground truth is built
      forward (place 3D points on the wall, project them) so the controls are not
      the implementation compared against itself. **[T]**
- [x] **0.2** Add `MetricMarks.glViewToCvDisplay(glView, rotationDeg)`; leave
      `glViewToCv` untouched. **[T]**
- [x] **0.3** Unit-test `glViewToCvDisplay` at 0/90/180/270° against hand-computed
      matrices. **[T]**
- [x] **0.4** Route the capture path through the new converter. `buildSingle` now takes
      `rotationDeg` with **no default** — a default would let a caller silently reinstate the
      defect. Plumbed from `MainActivity` → `MainViewModel` and from the doodle path.
- [x] **0.5** Audit *every* consumer of the capture view matrix for the same
      convention — `MetricFingerprintBuilder`, `PlaneMarks` callers,
      `targetCaptureViewMatrix` in `ArViewModel`, `fingerprintViewMatrix` in
      `MainViewModel`, and `restoreWallFingerprintMetric`'s `viewMatrix16`.
      List each site in the commit message with its verdict.
- [ ] **0.6** Confirm `PoseFusion.composeCorrected` is consistent under the chosen
      convention. `PoseFusionTest` now pins the factor order with non-commuting
      operands; extend it for the rotation, not with another round-trip (a
      round-trip is order-insensitive and would pass either way). **[T]**
- [ ] **0.7** Add `captureRotationDeg` to `Fingerprint`; default `-1`; refuse to
      reload a legacy fingerprint and prompt for re-capture. **[T]**
      *(STILL OPEN — the one Phase 0 item not landed. A fingerprint saved before this
      change stores sensor-frame points; it is no **worse** than it was, because the live
      side was already display-frame and the pair never agreed, but it is not fixed either
      and nothing tells the user. Separable from the geometry: it is persistence and
      migration, not frames. Until it lands, a legacy project silently keeps the §8 defect.)*
- [x] **0.8** Re-run `0.1` and confirm <1 mm at all four obliquities. Done at four
      obliquities × four rotations, through the real converter rather than a hand-built
      mismatch, with a negative control asserting the old wiring still fails on the same
      fixture. The rotation direction is mutation-tested: transposing `R_z` fails 8 tests.

      **0.6 WAS TICKED AND IS NOW UN-TICKED.** The rotation half of it is settled —
      `vCurrent` is `Camera.getViewMatrix`, documented as display-oriented, so
      convention B genuinely does align the two sides *for rotation*. But the audit
      found `composeCorrected` is missing two further factors that have nothing to do
      with rotation, and the original tick declared the whole expression sound on the
      strength of checking the one thing it went looking for. Trace of
      `inv(vCurrent) · pnpMat · fpAnchor`:

      - `vCurrent` — world → live camera, **GL** convention (−Z forward).
      - `pnpMat` — raw `solvePnPRansac` output (`MobileGS.cpp:528-535`), **CV**
        convention (+Z), mapping `objPts` → live camera.
      - `objPts` = `mWallKeypoints3D` = `PlaneMarks.backProject`'s `pointsCam`, which
        are in the **capture camera's** frame, not a world frame — despite the native
        field being named `mPnpCamFromFpWorld`.
      - `fpAnchor` = `mFingerprintAnchorMatrix` = `getAnchorTransform()`, which
        `MobileGS.cpp:525`'s own comment calls "a world-space MODEL matrix".

      So world coordinates are fed into a map whose domain is capture-camera
      coordinates, and CV camera coordinates into a GL camera-to-world inverse. The
      chain needs `inv(V_cur_gl) · C · pnpMat · V_capture_cv · fpAnchor` with
      `C = diag(1,−1,−1)`. Both `C` and `V_capture_cv` are absent. The codebase knows
      about `C`: `MobileGS.cpp:600-604` applies exactly it in `computeRectifyHomography`,
      commented *"Without this the homography is meaningless."*

      This is **pre-existing**, not introduced by Phase 0, and it is not a rotation
      problem — which is why looking only for rotation missed it.

- [ ] **0.9** Fix `composeCorrected`'s GL/CV and capture-view factors (see 0.6). Needs
      its own experiment: it changes where the overlay lands, and §8.3's warning about
      getting every sign right applies with full force. Do not fold it into a
      rotation commit. **[T]**
- [x] **0.10** `MetricFingerprintBuilder.ingestSingle` stores display-frame
      `res.pointsCam` alongside the **un-rotated** `glView` in the same
      `restoreWallFingerprintMetric` call. Native pairs them in
      `computeRectifyHomography` (`viewFp` at `MobileGS.cpp:571`, `mWallKeypoints3D`
      at `:576`), so the rectifying homography is off by `R_z` on the fingerprint
      side. This is a **live** gap in the path Phase 0 just fixed, not a legacy-data
      gap — it was mis-filed under 0.7 in PR #1797. In GL convention the matching
      rotation is `R_z(−θ)`, since `D·R_z(θ)·D = R_z(−θ)` for `D = diag(1,−1,−1)`.
      **[T]** *(Done: `MetricMarks.glViewDisplayOriented`. The sign flip is pinned by
      asserting `glViewToCv(glViewDisplayOriented(v, θ)) == glViewToCvDisplay(v, θ)`
      through the literal-matrix-tested CV converter, rather than rebuilding `D·R_z·D`
      in the test — which would have been the implementation retyped. Mutation-tested:
      using the same sense as CV fails 2 tests.)*
- [ ] **0.11** Persist `captureRotationDeg` in `GraffitiProject` at save time.
      Without it, projects captured *after* Phase 0 — the correct ones — are
      indistinguishable on disk from legacy ones, so 0.7's "refuse to reload a legacy
      fingerprint" would reject them too.

### Phase 2 — partition the fingerprint

- [ ] **2.1** Add `regions: ByteArray`, `captureHalfW`, `captureHalfH` to
      `Fingerprint`; empty `regions` means legacy all-backbone. **[T]**
- [ ] **2.2** Add `Fingerprint.reclassify(...)` for the user-rescales-the-design
      case; pure Kotlin over the stored points. Key the staleness check on
      **`overlayScale`** (or the effective scaled half-extents), NOT on
      `OverlayRenderer`'s extents — those have no user-driven call site and never
      change. **[T]**
- [ ] **2.3** Thread `overlayHalfW` / `overlayHalfH` into
      `MetricFingerprintBuilder.build` and the `PlaneMarks` single-view path.
- [ ] **2.4** Classify each surviving point via `Footprint.classify` during
      `assemble`; populate `regions`. **[T]**
- [ ] **2.5** Add `FingerprintPartitionTest` — synthetic straddling point set,
      expected counts per region. **[T]**
- [ ] **2.6** Add `regions` to `SlamManager.restoreWallFingerprintMetric`'s
      signature and JNI binding.
- [ ] **2.7** Store `mWallRegions` in `MobileGS`; filter the reloc correspondence
      build to exclude `INSIDE`. Zero-length regions → all backbone. **[N]**
- [ ] **2.8** Add the `F_out`-too-small capture refusal with the specific
      "step back" message, following `MainViewModel`'s existing
      `lastDetected/lastPlaced/lastRequired` toast pattern.
- [ ] **2.9** Add a persistence round-trip test including the legacy-empty path. **[T]**
- [ ] **2.10** Verify on a replayed recording that a legacy fingerprint produces
      byte-identical inlier counts to `main`.
- [ ] **2.11** (6b) Add `backboneFeatures` / `backboneMatches` / `backboneInliers`
      to `RelocDiagnostics`, the CSV, and the overlay. Defaults encode "not
      measured", not zero — follow the `obliquityDeg = -1` precedent. **[T][N]**

### Phase 4 — spatially-constrained corroboration

- [ ] **4.1** Create `feature/ar/.../anchor/SearchRadius.kt`; compute `r` from
      `ρ`, half-extents, wall distance, focal length. Floor and ceiling it. **[T]**
- [ ] **4.2** `SearchRadiusTest` — assert scaling relationships, not absolutes. **[T]**
- [ ] **4.3** Feed the measured `errMm` from `DriftCostProbe` into `r` so the
      radius widens when the pose is known to be drifting. **[T]**
- [ ] **4.4** Implement uniform-grid keypoint bucketing in Kotlin as the
      reference implementation, with a brute-force equivalence test. **[T]**
- [ ] **4.5** Transliterate the grid into `MobileGS.cpp`; replace the global
      `knnMatch` against `artDescs` with the gated match. **[N]**
- [ ] **4.6** Publish `corrobPredicted` / `corrobMatched` / `searchRadiusPx`
      through the Phase 6 channels. **[N]**
- [ ] **4.7** Make the Lowe ratio for the *corroboration* match a separate
      constant from the reloc ratio, so E7 can sweep it independently.
- [ ] **4.8** Guard: if the predicted-visible count is zero, publish confidence as
      "not measured" rather than 0.0 — those are different states and
      `PoseFusion` must not read the second as the first.

### Phase 5b — the predicted-visible denominator

- [ ] **5b.1** Switch `corroborationConfidence`'s denominator from
      `artDescs.rows` to the Phase 4 predicted-visible count, so a close-up of
      one corner is no longer capped by framing rather than by agreement. **[N]**
- [ ] **5b.2** Re-derive `CONF_FLOOR` against the new denominator. Its current
      0.5 was reasoned against the old one and does not transfer unexamined. **[T]**

### Phase 3 — geometric promotion

- [ ] **3.1** Port `growTrusted` into Kotlin as `PromotionGate.kt` with tests;
      keep the C++ a literal transliteration. **[T]**
- [ ] **3.2** Add the inlier-spread term (bounding box of inlier image points over
      frame area) to `PromotionGate` and its transliteration. **[T][N]**
- [ ] **3.3** Publish `inlierSpread` through the Phase 6 channels. **[N]**
- [ ] **3.4** Classify each promotion candidate via Φ at promotion time; only
      `OUTSIDE` enters the reloc set. **[N]**
- [ ] **3.5** Route `INSIDE` promotions into `F_in` with a bounded lifetime, or
      drop them — decide from E5's result, not in advance.
- [ ] **3.6** Keep `setSelfGrowEnabled` defaulted **off**. Flip only after E5
      shows a positive replayed-dataset effect. (The native default and the eval
      overlay's toggle both said ON until this was corrected — re-check both
      `MobileGS.h`'s initializer and `MainActivity`'s `selfGrowOn` if you touch
      either, because the comment and the initializer disagreed for months.)

### Cross-cutting

- [ ] **X.1** Add `docs/research/PARAMETERS.md` entries for every new constant as
      it is introduced — not in a batch at the end.
- [ ] **X.2** After each phase, record a fresh replay run of the standard dataset
      so the eval has a per-phase baseline rather than one before/after pair.
- [ ] **X.3** Never land two phases in one CI run. The relocalizer is a feedback
      loop and overlapping changes make the measurements uninterpretable.

---

## What this plan does not do

Stated plainly so it is not mistaken for an oversight.

- **It does not make a wall-filling design work.** If the artwork covers every
  visible surface, `F_out` is empty and there is no backbone. The plan makes that
  case *diagnosable and refused*, not solved. Solving it needs a different
  mechanism — probably relocalizing against the room rather than the wall.
- **It does not remove ARCore.** The backbone still rides on ARCore's tracking;
  `PoseFusion` still corrects a backbone rather than replacing it.
- **It does not address the distortion head.** `mDistortionHead` is referenced in
  the progress path and takes precedence when loaded. Phase 5 splits the signals
  around it without changing what it does. If the head is doing something wrong,
  that is a separate investigation.
- **It does not guarantee the hybrid beats appearance-only.** That is prediction
  P6 in the paper, and P6 is falsifiable on purpose. `EVALUATION.md` says how it
  would be falsified and what to conclude if it is.
