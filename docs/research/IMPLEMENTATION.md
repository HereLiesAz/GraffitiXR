# Implementation plan — wiring the hybrid relocalizer

Companion to [`PAPER.md`](PAPER.md). The paper argues *what* to build and *why*;
this document says *where the code goes*, *what it is called*, *what breaks it*,
and *how you know it worked*. It assumes the paper's vocabulary — the **footprint
operator** Φ, the **backbone set** `F_out`, the **corroboration set** `F_in`.

Nothing below Phase 0 is built. Everything cited as "already fixed" landed in PRs
#1785–#1788 and is on `main`.

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

**Status: prerequisite. Gates Phase 3. Does not gate Phases 1, 2, 4, 5.**

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

### Why it must be settled before Phase 3

Phase 3 promotes new observations into the fingerprint using the current
relocalized pose (`mPnpCamFromFpWorld` in `tryUpdateFingerprint`). If the pose
carries a rotation error, promotion *writes that error into the map* and it
compounds. Building geometric promotion on an unresolved convention converts a
static bias into a divergent one.

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

`Footprint.of` needs a 4×4 inverse. The anchor model is rigid, so use
`PoseMath.rigidInverse` — it already exists, it is already tested, and it avoids
a general inverse.

### Tests — `FootprintTest.kt`

1. Identity anchor, unit extents: world `(0,0,0)` → `(0,0)`; `(h_w,h_h,0)` → `(1,1)`.
2. Translated anchor: Φ is invariant to where the design sits in the world.
3. Rotated anchor (45° about the plane normal): a point on the design's local +X
   axis still maps to `(+1, 0)`, not to a rotated pair. This catches using the
   forward transform where the inverse was needed — the most likely bug here.
4. Non-square extents (`h_w = 2·h_h`): both axes still saturate at 1 at their own
   edge. Catches dividing both axes by the same half-extent.
5. `classify` with `innerMargin=0.05, outerMargin=0.15`: a point at Φ radius 0.98
   is `INSIDE`, at 1.10 is `BAND`, at 1.30 is `OUTSIDE`.
6. Off-plane point: a point 0.5 m in front of the wall projects to the same `(u,v)`
   as its foot. Document this explicitly — Φ deliberately discards the normal
   component, and a reader will wonder.

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

**Snapshot the extents at capture.** `OverlayRenderer`'s extents change when the
user scales the design. The fingerprint's regions are only meaningful against the
extents in force when it was built, so store `halfW`/`halfH` in the `Fingerprint`
alongside the regions. If the user rescales the design afterwards, the stored
regions are stale — recompute them from the stored 3D points and the new extents
rather than forcing a recapture. That is a cheap pure-Kotlin pass over the point
array and it is worth doing because rescaling the design is a normal thing for a
user to do mid-session.

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

**Depends on: Phase 0 (hard) and Phase 2.**

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

**Depends on: Phases 1 and 2. Independent of 3.**

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

**Depends on: Phase 2. Independent of 3 and 4.**

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

**Depends on: 2. Should land alongside 2.**

`EVALUATION.md` cannot run without these channels. Extend
`EvalSampleLog.CSV_HEADER` — currently:

```
tsMs,deviceClass,marksVisible,errMm,errDeg,jitterMm,availability,voxelUpdateMs,
voxelKeyframeMs,surfaceMeshMs,drawMs,pnpRelocMs,cpuPct,batteryMa,tempC,nativeHeapKb
```

with:

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

```
Phase 0 (rotation)  ──────────────┐
                                  ▼
Phase 1 (Φ) ──► Phase 2 (partition) ──► Phase 3 (promotion)   [gated by 0]
                     │
                     ├──────────► Phase 4 (local corroboration)
                     │
                     ├──────────► Phase 5 (split signals)
                     │
                     └──────────► Phase 6 (telemetry)
```

Recommended landing order, which is not the numeric order: **1 → 6 → 5 → 2 → 4 →
0 → 3.** Rationale: Phase 1 is free and unblocks everything; Phase 6 makes every
later change measurable; Phase 5 is the best risk-adjusted fix and is
independent; Phase 2 is the structural change; Phase 4 is the payoff; Phase 0 is
slow and careful and Phase 3 is the only one that can do lasting damage, so both
go last.

---

## Granular todo list

Each item is one commit-sized unit of work. Checkboxes are for tracking across
sessions. `[T]` = has a test that must be written with it. `[N]` = touches native
code, so CI's NDK build is the only compile check.

### Phase 1 — footprint operator

- [ ] **1.1** Create `feature/ar/.../anchor/Footprint.kt` with `Region` enum and
      empty `of` / `isInside` / `outsideDistance` / `classify` signatures.
- [ ] **1.2** Implement `Footprint.of` using `PoseMath.rigidInverse`; return a
      reused 2-element `FloatArray` from a caller-supplied buffer overload to
      avoid per-feature allocation in the hot path. **[T]**
- [ ] **1.3** Implement `isInside` and `outsideDistance` (Chebyshev). **[T]**
- [ ] **1.4** Implement `classify` with the inner/outer margin discard band. **[T]**
- [ ] **1.5** Write `FootprintTest.kt` — all six cases from Phase 1 above,
      including the rotated-anchor case and the non-square-extent case. **[T]**
- [ ] **1.6** Document in the KDoc that Φ discards the plane-normal component,
      with the reason.

### Phase 6 — telemetry (land before behaviour changes)

- [ ] **6.1** Extend `RelocDiagnostics` with `backboneFeatures`, `inlierSpread`,
      `corrobPredicted`, `corrobMatched`. Defaults must encode "not measured",
      not "zero" — follow the existing `obliquityDeg = -1` precedent. **[T]**
- [ ] **6.2** Extend `RelocDiagnosticsTest` for the new defaults and for the
      independence of each new counter.
- [ ] **6.3** Add the matching fields to the native diagnostics publish path in
      `MobileGS.cpp`; keep the ordinal contract with the Kotlin enum intact. **[N]**
- [ ] **6.4** Extend `EvalSampleLog.CSV_HEADER` with the nine columns above and
      the matching `EvalLiveMetrics` fields.
- [ ] **6.5** Add a CSV-shape test: header column count equals the emitted row's
      field count. This is the class of bug that silently corrupts every
      downstream analysis. **[T]**
- [ ] **6.6** Surface the new numbers in `RelocDiagnosticsOverlay`, laid out so
      the backbone and corroboration groups are visually separated.

### Phase 5 — split progress from confidence

- [ ] **5.1** Add `mCorroborationConfidence` atomic to `MobileGS.h`. **[N]**
- [ ] **5.2** In `tryUpdateFingerprint`, compute and publish corroboration
      confidence separately from `mPaintingProgress`. **[N]**
- [ ] **5.3** Remove the `×0.9` decay from the painting-progress channel; leave
      progress as a stored fraction that only a real measurement changes. **[N]**
- [ ] **5.4** Apply decay (or a per-frame recompute) to the confidence channel
      only. **[N]**
- [ ] **5.5** Add `SlamManager.getCorroborationConfidence()`.
- [ ] **5.6** Switch `ArRenderer`'s `confGlobal` to the new getter; leave
      `ArViewModel:1469` on `getPaintingProgress()`.
- [ ] **5.7** Extend `PoseFusionTest`: floor confidence still corrects; a
      confidence drop slows but never reverses a correction. **[T]**

### Phase 2 — partition the fingerprint

- [ ] **2.1** Add `regions: ByteArray`, `captureHalfW`, `captureHalfH` to
      `Fingerprint`; empty `regions` means legacy all-backbone. **[T]**
- [ ] **2.2** Add `Fingerprint.reclassify(anchorModel, halfW, halfH)` for the
      user-rescales-the-design case; pure Kotlin over the stored points. **[T]**
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

### Phase 0 — rotation convention

- [ ] **0.1** Write `PlaneMarksObliquityTest` against a synthetic wall + camera at
      0/20/40/60°. Expect it to fail at >0° on current `main` — that failure is
      the reproduction. **[T]**
- [ ] **0.2** Add `MetricMarks.glViewToCvDisplay(glView, rotationDeg)`; leave
      `glViewToCv` untouched. **[T]**
- [ ] **0.3** Unit-test `glViewToCvDisplay` at 0/90/180/270° against hand-computed
      matrices. **[T]**
- [ ] **0.4** Route the capture path through the new converter.
- [ ] **0.5** Audit *every* consumer of the capture view matrix for the same
      convention — `MetricFingerprintBuilder`, `PlaneMarks` callers,
      `targetCaptureViewMatrix` in `ArViewModel`, `fingerprintViewMatrix` in
      `MainViewModel`, and `restoreWallFingerprintMetric`'s `viewMatrix16`.
      List each site in the commit message with its verdict.
- [ ] **0.6** Confirm `PoseFusion.composeCorrected` is consistent under the chosen
      convention; add a composition test that round-trips a known pose. **[T]**
- [ ] **0.7** Add `captureRotationDeg` to `Fingerprint`; default `-1`; refuse to
      reload a legacy fingerprint and prompt for re-capture. **[T]**
- [ ] **0.8** Re-run `0.1` and confirm <1 mm at all four obliquities.

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
      shows a positive replayed-dataset effect.

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
