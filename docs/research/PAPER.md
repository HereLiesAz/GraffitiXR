# Goal-Directed Relocalization for Surfaces That Are Destroyed by Use

**A formal account of the reference-decay problem in mural augmented reality, an
analysis of why the deployed mechanism could not solve it, and a proposed
partition of the goal state into spatial and appearance channels.**

GraffitiXR engineering note, 2026-07.

---

## Abstract

Visual relocalization assumes the scene is static. A muralist's wall is not: the
artist physically destroys the reference features by painting over them, and does
so *because* the system is working. Accuracy therefore degrades monotonically with
task progress — the failure is structural, not incidental.

GraffitiXR's response, *teleological SLAM*, observes that the system already knows
the intended final state of the wall (the artwork the user loaded) and treats that
goal state as an additional source of evidence. The deployed implementation used
the goal state as an **appearance validator**: a live camera feature corroborates
the target if its descriptor matches a descriptor extracted from the design image.

We report two findings. First, the mechanism was never reachable — of four defects
verifiable from source (§4.2), three are outright disconnections between stages and
the fourth is a gate set too strictly to fire, so no field evidence about the
approach exists. Second, and independently,
the appearance-validator formulation asks the goal state for the one kind of
information it supplies least reliably: a cross-modal descriptor comparison
between a synthetic image and photographed pigment, executed as an unconstrained
global search (§4.3).

We propose instead a **partition of the goal state into a spatial channel and an
appearance channel** (§5). The design's projected footprint on the wall plane is
known exactly, at *t=0*, before any paint exists, and requires no matching. It
partitions detected features into a *backbone set* outside the footprint — the
region that survives the entire job — and a *corroboration set* inside it. The two
sets have different jobs and, critically, different informational preconditions:
the backbone must localize without a prior pose, while the corroboration set may
assume one. This is what makes the partition principled rather than merely
convenient.

The partition also converts the appearance comparison from a global search over
the whole design into a spatially-constrained local one, which is the change that
makes the cross-modal problem tractable (§5.5). Finally, disagreement between the
two channels — the footprint predicts paint, appearance denies it — becomes an
observable that separates *task progress* from *pose error*, two quantities the
deployed system conflates into a single scalar (§5.6).

We give the algorithm, its parameters, six falsifiable predictions, and the
failure modes we expect (§6, §7).

---

## 1. The problem

### 1.1 Setting

An artist paints a mural on a wall, guided by an AR overlay of the intended
artwork registered to that wall. The overlay must remain locked to the physical
surface across a working session of hours, through the device being pocketed, the
artist stepping back and returning, and changing light.

The pose backbone is ARCore's VIO. VIO drifts. The standard correction is visual
relocalization: fingerprint the surface once, then periodically re-detect it and
solve a PnP correction. GraffitiXR does this — `MobileGS::relocThreadFunc` matches
live features against a stored wall fingerprint and publishes a
camera-from-fingerprint pose, which `PoseFusion` folds into the rendered anchor as
a persistent drift correction.

### 1.2 The reference-decay problem

The assumption underneath all of this is that the fingerprinted surface persists.
For a muralist it does not. The fingerprint is built from the wall's appearance —
its grain, its stains, its existing marks — and the artist covers that appearance
with paint. Every stroke deletes reference features.

Three properties make this different from ordinary relocalization degradation:

1. **It is monotonic.** Coverage only increases. There is no recovery.
2. **It is caused by success.** The better the tool works, the faster its own
   reference is destroyed.
3. **It is concentrated exactly where the user is looking.** The artist works
   close to the artwork, so the painted region dominates the frame precisely when
   the lock matters most.

A system that merely tolerates decay is not enough; the reference set has to be
*replenished* from something.

### 1.3 The teleological observation

GraffitiXR's insight is that the destruction is not arbitrary. The system knows
what the wall is *going to look like* — the artwork is loaded before the first
stroke. The new appearance is not noise to be survived; it is *predicted*.

This is the sense in which the approach is teleological (goal-directed): the final
cause of the process is available in advance and can be used as evidence. Stated
as a claim:

> As the artist paints, the wall converges toward a state the system already
> knows. Correspondence between the wall and that known state is therefore
> evidence about pose, and it *grows* with task progress.

If that can be realized, the failure mode inverts: accuracy improves with
progress instead of degrading. We believe the observation is correct. The question
this note addresses is how to extract the evidence.

---

## 2. Why existing work does not transfer

- **Static-scene SLAM** (ORB-SLAM and descendants) assumes a rigid, unchanging
  map. Map points that disappear are treated as outliers and culled. Here the
  culling is total and predictable.
- **Dynamic SLAM** segments and *rejects* moving or changing regions. That is the
  opposite of what is wanted: the changing region is the artwork, which is
  precisely the region we have prior information about.
- **Lifelong / long-term localization** handles appearance change across seasons
  and illumination, but the change is exogenous and unpredicted. Here it is
  endogenous and specified in advance.
- **Model-based tracking from CAD** assumes the object already matches the model.
  Here the surface converges toward the model over hours, and must be tracked
  throughout the transition, including at 0% when it matches nothing.

The distinguishing feature is a *known, gradual, self-inflicted* transformation of
the reference surface toward a specified target. We are not aware of a system that
exploits this, which cuts both ways: no prior art to borrow, and no reason to
assume the first mechanism drafted is right.

---

## 3. System context

Definitions used throughout.

| Symbol | Meaning |
|---|---|
| $W$ | ARCore world frame |
| $V_t$ | camera view (world→camera) at time $t$ |
| $\Pi$ | the wall plane, in $W$ |
| $F = \{(\mathbf{x}_i, \mathbf{d}_i)\}$ | wall fingerprint: 3D points on $\Pi$ with descriptors |
| $A = \{(\mathbf{u}_j, \mathbf{a}_j)\}$ | artwork base: design-image pixels with descriptors |
| $T$ | anchor pose of the artwork on $\Pi$ |
| $p$ | painting progress $\in [0,1]$ |

Pipeline as deployed (`MobileGS.cpp`, `PoseFusion.kt`):

1. **Capture.** One frame; detect features; back-project onto $\Pi$ via
   `PlaneMarks.backProject` → $F$. Requires ≥20 points.
2. **Relocalize.** Detect on the live frame; Lowe-ratio match (0.75) against $F$
   at three scales plus an oblique-rectified pass; `solvePnPRansac`
   (100 iterations, 8 px, 0.99) needs ≥8 correspondences and ≥6 inliers.
3. **Fuse.** `PoseFusion` maintains a persistent world-frame drift correction
   $D$, re-applied every frame as $D \circ \text{backbone}$. New fixes are
   hard-snapped when confident and cold, otherwise eased at
   $\alpha = 0.25 \cdot \rho \cdot c$, where $\rho$ is the PnP inlier ratio and
   $c$ the corroboration confidence.
4. **Corroborate and grow.** Match the live frame against $A$; the fraction of $A$
   hit is $p$; validated features are promoted into $F$.

Stage 4 is the teleological part. Stages 1–3 are conventional.

---

## 4. Approach A — the goal state as appearance validator

### 4.1 What was specified

$p$ is the fraction of artwork descriptors corroborated by the live wall:

$$
p = \frac{\left|\{\,j : \exists i,\ \text{lowe}(\mathbf{f}_i, \mathbf{a}_j) < 0.75\,\}\right|}{|A|}
$$

with $\mathbf{f}_i$ the live descriptors. $p$ then (a) drives the confidence term
$c$ in the fusion rate, and (b) gates *self-grow*: a live feature matching $A$ is
promoted into $F$, replenishing the fingerprint from real paint.

The design is coherent. Each stage is implementable. It is the natural first
formulation of the teleological observation.

### 4.2 Finding 1 — it was never reachable

Every stage after (a) was disconnected. These are point facts, each checkable in
seconds:

| Stage | Defect | Evidence |
|---|---|---|
| Confidence weighting | `confGlobal` passed as literal `1f` | one line in `ArRenderer.kt`; $p$ reached the HUD and nothing else, so fusion behaved identically at 0% and 100% painted |
| Oblique robustness | `mHasFingerprintView` assigned in exactly one function — `generateFingerprint`, on the ARCore-depth path, which is disabled | the rectification pass could not run on any fingerprint the app builds |
| Artwork registration | `setArtworkFingerprintFromComposite` had zero callers and returned on its first line (`targetDepthBuffer ?: return`, always null) | dead |
| Self-grow | required `inliers >= 20` | a half-covered wall rarely reaches 20 raw inliers, so $F$ could only grow when already strong — exactly when it needed to least |

Compounding this, every failure was silent. `mPnpMatchCount` and
`mPnpInlierCount` updated only on success, so a relocalizer that had never locked
was indistinguishable from an idle one. Three separate bails on the capture path
discarded a confirmed target with no message.

**Consequence for the record: there is no field evidence about Approach A.** Its
reported failure is evidence about wiring, not about the idea. Any claim that the
teleological approach "does not work" is unsupported. (All defects in this table
are repaired on `main`; see PRs #1785–#1788.)

### 4.3 Finding 2 — the formulation is weak independent of the wiring

Even fully wired, the appearance-validator formulation has three structural
problems.

**(a) It is a cross-modal comparison.** $A$ is extracted from a synthetic image —
flat colour, hard edges, no substrate. The live descriptors come from pigment on
masonry under a torch: texture, specularity, edge bleed, colour shifted by the
wall beneath. Descriptors are not designed for this. Both sides are CLAHE-
normalized (`normalizeForFeatures`), which helps with illumination and not at all
with modality.

**(b) The search is unconstrained.** The test asks whether a live descriptor
matches *anything anywhere* in $A$. With $|A|$ in the hundreds to low thousands,
the nearest-neighbour distance under the null hypothesis concentrates, the Lowe
ratio loses discriminative power, and the false-positive rate rises with $|A|$.
Worse, a false positive is not merely noise: it promotes a wrongly-placed point
into $F$, corrupting the authoritative reference set. RANSAC in the reloc PnP is
the only backstop.

**(c) It cannot bootstrap.** $p$ is zero until paint exists, so the mechanism
contributes nothing at the start — which is when the fingerprint is strongest and
help is least needed — and would contribute most at the end, if the corruption in
(b) has not accumulated.

There is also a subtler issue: promotion requires a pose to place the new point in
3D, and the pose is what relocalization is trying to establish. The deployed code
resolves this by requiring a fresh confident relock first, which is sound but
means the mechanism is a *refinement* of an existing lock, never a way to obtain
one. That is a real constraint on what stage 4 can do, and it is not stated
anywhere in the original design.

---

## 5. Approach B — partitioning the goal state

### 5.1 The observation

The goal state carries two entirely different kinds of information, and the
deployed system uses only the harder one.

- **Appearance:** *what the painted wall will look like.* Requires cross-modal
  matching. Available only after paint exists. Uncertain.
- **Spatial:** *where the paint will be.* A rectangle on a known plane, given by
  the anchor pose $T$ and the overlay half-extents. Available at $t=0$. Exact —
  no matching of any kind.

The spatial channel is free, certain, and available before the appearance channel
produces anything. It is currently unused.

### 5.2 The footprint operator

The overlay is rendered as a quad on $\Pi$ spanning $[-h_w, h_w] \times [-h_h,
h_h]$ in the overlay's local frame, with world transform $M$ (`overlayComposedScratch`
in `ArRenderer`, half-extents from `OverlayRenderer.setExtent`). Define the
footprint operator $\Phi$ mapping a world point to design texture coordinates:

$$
\Phi(\mathbf{x}) = \left( \frac{(M^{-1}\mathbf{x})_x}{h_w},\ \frac{(M^{-1}\mathbf{x})_y}{h_h} \right) \in \mathbb{R}^2
$$

A point is **inside the footprint** iff $\|\Phi(\mathbf{x})\|_\infty \le 1$. With
a dilation margin $\mu$ (§5.3) the *protected* region is $\|\Phi\|_\infty \le 1 +
\mu$.

$\Phi$ is exact, cheap (one 4×4 inverse per frame, reusable), and defined the
moment the artwork is placed. It also inverts: $\Phi^{-1}$ maps a design pixel to
its world position on $\Pi$, which §5.5 needs.

### 5.3 The partition

At capture, every back-projected feature is classified:

$$
F = \underbrace{\{\,i : \|\Phi(\mathbf{x}_i)\|_\infty > 1 + \mu\,\}}_{F_{\text{out}}\ \text{— backbone set}}
\ \cup\
\underbrace{\{\,i : \|\Phi(\mathbf{x}_i)\|_\infty \le 1 + \mu\,\}}_{F_{\text{in}}\ \text{— corroboration set}}
$$

The margin $\mu$ absorbs anchor error, overlay adjustment after capture, and
overspray. It should be tuned (§`EVALUATION.md` E8), not guessed.

The two sets are not two priorities on one list. They differ in what they are
*for* and in what they may assume:

| | $F_{\text{out}}$ (backbone) | $F_{\text{in}}$ (corroboration) |
|---|---|---|
| Survives the job | yes | no — deleted by design |
| Role | obtain and hold the lock | measure progress; replenish $F$ |
| May assume a prior pose | **no** — must bootstrap cold | **yes** — a lock already exists |
| Promotion criterion | geometric stability (§5.4) | spatially-constrained appearance (§5.5) |
| Available at $t=0$ | yes | yes, but uninformative until paint |

The right-hand column of row "may assume a prior pose" is the load-bearing one. It
dissolves the circularity noted at the end of §4.3: the set that must work without
a pose is exactly the set that does not need the goal state's appearance channel,
and the set that exploits a prior pose is exactly the set for which a prior pose
is available.

### 5.4 Backbone promotion is geometric, not semantic

For $F_{\text{out}}$, "is this a real, static wall feature?" is answerable without
the design at all. Given a current lock, a candidate observed over $N$ frames with
sufficient camera baseline $b$ is promoted iff its plane-projected position is
stable:

$$
\text{promote}(k) \iff \big(\max_{n \le N} \|\mathbf{x}_k^{(n)} - \bar{\mathbf{x}}_k\| < \varepsilon\big)
\ \wedge\ \big(b > b_{\min}\big) \ \wedge\ \big(N \ge N_{\min}\big)
$$

This is standard landmark promotion. It is more robust than descriptor matching
against a PNG, and it is *simpler* than what is deployed. Its parameters
($\varepsilon$, $N_{\min}$, $b_{\min}$) are physical and measurable, not
perceptual.

Note this makes the backbone independent of the artwork entirely — which is
correct. The wall outside the mural has nothing to do with the mural.

### 5.5 Corroboration becomes a local match

This is the change that rescues the cross-modal comparison.

Given a pose (available by §5.3, right column), a live feature at pixel
$\mathbf{p}$ back-projects to $\mathbf{x} \in \Pi$ and therefore to a *known
design coordinate* $\Phi(\mathbf{x})$. The corroboration test is no longer

> does $\mathbf{f}$ match anything in $A$?

but

> does $\mathbf{f}$ match $A$ **at $\Phi(\mathbf{x})$**, within radius $\rho$?

Let $|A_\rho|$ be the number of design descriptors within $\rho$ of
$\Phi(\mathbf{x})$. The candidate set shrinks from $|A|$ to $|A_\rho|$, and
$|A_\rho| / |A| \approx \pi\rho^2 / 4$ for $\rho$ in normalized units — for
$\rho = 0.05$ that is under 0.2% of the design.

Two consequences follow, and they compound:

1. **False positives collapse.** Under the null, the probability that some
   descriptor in the candidate set passes the ratio test scales with the candidate
   count. Cutting the candidate set by two to three orders of magnitude cuts the
   spurious-match rate correspondingly.
2. **The ratio test can be *loosened*.** Precision is now being supplied by
   geometry rather than by descriptor distance, so the Lowe threshold can rise
   above 0.75 — recovering true matches that the cross-modal gap would otherwise
   have rejected — while *still* ending up more precise than the global test at
   0.75.

That second point is the answer to §4.3(a). The cross-modal comparison is hard
in the abstract; it is much less hard when you already know which few square
centimetres of the design you are comparing against. We are not making the
descriptors better. We are making the question easier.

The mechanism also self-checks: a match at $\Phi(\mathbf{x})$ that survives is
consistent with the pose that produced $\Phi$, so corroborations reinforce the
pose rather than merely counting.

### 5.6 Disagreement is an observable

With both channels running, each footprint cell has a prediction and a
measurement, and there are four cases:

| Footprint says | Appearance says | Interpretation |
|---|---|---|
| paint here | matches | **corroborated** — painted, and where expected |
| paint here | no match | **not yet painted** (early) *or* **pose error** (late) |
| no paint here | matches | design self-similarity, or pose error |
| no paint here | no match | expected — bare wall |

Row 2 is the useful one. Today its two readings are conflated: $p$ is a single
scalar that falls both when the artist has not got there yet and when the overlay
is misaligned. Separating them uses the *spatial distribution* of disagreement:

- Progress is **spatially coherent** — artists fill regions. Un-corroborated cells
  cluster in areas not yet worked.
- Pose error is **spatially uniform with structure** — a translation shifts
  corroboration off by a constant offset; a rotation produces disagreement growing
  with radius from the anchor.

So: fit a rigid 2D offset to the corroborated-cell displacement field. A
significant fit is a *direct pose-error estimate in metres on the wall*, which is
both a correction signal and the honest diagnostic that has been missing —
"the overlay is 4 cm left of where the paint is" rather than "matched 38%".

Spatially-resolved progress is a free by-product: the app can show which sections
are done.

### 5.7 The algorithm

```
CAPTURE (once, when the target is created)
  1  detect features on the capture frame
  2  back-project onto Π                                   → F
  3  partition by Φ with margin μ                           → F_out, F_in
  4  weight F_out up, F_in down when building the fingerprint
  5  store Φ (M, h_w, h_h) alongside the fingerprint

RELOCALIZE (background thread, ~16 Hz hunting / 5 Hz locked)
  6  detect on the live frame
  7  match against F_out ∪ F_in, Lowe ratio r_global
  8  PnP RANSAC → pose, inliers
  9  publish; PoseFusion folds it in at α = α₀ · ρ · c

CORROBORATE (same thread, only when step 8 produced a lock)
 10  for each live feature f at pixel p:
 11      x ← back-project(p, Π, pose)
 12      if ‖Φ(x)‖∞ ≤ 1:                                    # inside the artwork
 13          match f against A restricted to radius ρ of Φ(x), ratio r_local
 14          record corroborated / not, and the displacement if corroborated
 15  p ← corroborated cells / expected cells                 # progress
 16  fit rigid 2D offset to displacements                    → pose-error estimate
 17  c ← f(p)                                                # fusion confidence

GROW
 18  for candidates outside the footprint:  promote on geometric stability (§5.4)
 19  for candidates inside the footprint:   promote on corroboration (step 13)
 20  cap, dedupe, and require a fresh confident relock before any promotion
```

Steps 1–9 exist. Steps 3–5, 10–19 are new. Step 20 exists and is retained.

---

## 6. Predictions

Stated so they can be falsified. Each names the experiment that would falsify it;
protocols are in `EVALUATION.md`. A prediction with no experiment is an opinion,
so the mapping is given here rather than left to be reconstructed.

- **P1** *(→ E12)*. Relocalization availability at ≥50% coverage is higher with the
  partition than without, on identical replayed sessions. *(The backbone is drawn
  from wall that was never painted.)*
- **P2** *(→ E7)*. For fixed precision, the local test (§5.5) admits a strictly larger
  Lowe threshold than the global test. *(Direct consequence of candidate-set size; if
  this fails, the search-space argument is wrong.)*
- **P3** *(→ E6)*. Corroboration false-positive rate under the local test is at least
  an order of magnitude below the global test at matched recall.
- **P4** *(→ E5)*. Geometric promotion (§5.4) yields a lower fingerprint-corruption
  rate than appearance promotion, measured as PnP inlier ratio over session time.
- **P5** *(→ E13)*. The rigid-offset fit (§5.6) correlates with measured overlay error
  (`errMm`) at $r > 0.7$ once coverage exceeds ~30%.
- **P6** *(→ E10)*. End-to-end overlay error at 100% coverage does not exceed error at
  0% coverage. *This is the whole thesis.* Failing P6 while passing P1–P5 would mean
  the mechanism works and the premise does not.

---

## 7. Threats to validity and expected failure modes

**The anchor defines the footprint.** $\Phi$ depends on $T$. If the anchor is
wrong, the partition is wrong — protected features get treated as doomed and vice
versa. Mitigated by the margin $\mu$ and by the fact that errors are smooth, but
it is a genuine coupling: a *systematically* wrong anchor produces a
systematically wrong partition, and §5.6's offset fit would then be fitting the
anchor error rather than the pose error. Detect via P5.

**The artist does not follow the design.** Freehand deviation, colour changes,
overspray. Corroboration degrades gracefully — those cells simply do not
corroborate, and the backbone is untouched. It becomes a problem only if
deviation is large enough that the progress signal is dominated by it, which
would show up as P5 failing while P1 passes.

**Small artwork on a large wall.** The footprint is a small fraction of the frame,
so $F_{\text{out}}$ dominates and Approach B reduces to conventional
relocalization plus geometric promotion. This is fine — correct behaviour, no
teleological contribution needed. The mechanism should not be expected to help
here, and evaluating it on such a case would be a category error.

**Artwork covering the entire visible wall.** The complement: $F_{\text{out}}$ is
nearly empty and the backbone has nothing durable to hold. This is the genuinely
hard case and the partition does not solve it. Options are external fiducials
outside the work area, or accepting VIO-only tracking with periodic manual
re-registration. **We should be explicit that Approach B has a domain of
applicability and this sits outside it.**

**Circularity in §5.5.** The local test needs a pose; the pose comes from
matching. The partition resolves it *provided* $F_{\text{out}}$ can carry the lock
alone. If it cannot — see the previous point — the corroboration channel silently
stops contributing rather than failing loudly. Instrument this: corroboration
attempts should be counted separately from corroboration successes.

**Compute.** The local match adds a spatial index over $A$ and a per-feature
lookup. Both are small, but this runs on a background thread already doing
detection and PnP. Budget in `EVALUATION.md` E9.

---

## 8. Unresolved prerequisite: the display-rotation convention

Phase 3 of the implementation is gated on a question we have deliberately not
answered by guessing.

At capture, the bitmap and intrinsics are rotated to display orientation; the view
matrix is not. `PlaneMarks.backProject` therefore builds rays in a frame rotated
about the optical axis relative to the plane it intersects them with, so
$\mathbf{d}' = R_z \mathbf{d}$ with $R_z$ the 90° image rotation. Depths come out
skewed and $F$ is not the true wall geometry.

Two properties make it fit the observed behaviour: the error **vanishes for a
head-on wall** — a plane normal $(0,0,\pm1)$ is invariant under rotation about $z$
— and grows with obliquity; and the onboarding path shares the convention, so it
fails identically.

### 8.1 Magnitude

This was asserted from a source comment for several development cycles before
anyone computed it. The comment was passed forward, cited as established, and
used to justify a behavioural change — with no test covering it
(`PlaneMarksTest` has no oblique case) and no measurement. What follows is the
calculation that should have accompanied the claim.

Substituting the mismatched frames into `backProject`'s own expression
$t = (n \cdot p)/(n \cdot d)$: rotation preserves the dot product, so the
numerator $n \cdot p$ is unchanged and the entire error lives in the
denominator, $n_s \cdot d$ where $n_d \cdot d$ was meant. Each recovered point
therefore lies on the *correct ray* at the *wrong depth*, scaled by a factor
that varies across the image — a **non-rigid** distortion of the point cloud,
which matters because a rigid one would be absorbed by PnP and this is not.

For 1080×1920 display-oriented intrinsics ($f = 1400$, principal point centred)
and a wall at 2 m, on a 40×40 grid over the central 80% of the frame, with
`backProject`'s own 0.1–10 m trust range applied and $p95$ taken as
$\text{sorted}[\lfloor 0.95N \rfloor]$:

| obliquity | mean error | p95 | marks surviving |
|---|---|---|---|
| 0° | **0.0 mm** | 0.0 mm | 1600/1600 |
| 10° | 121 mm | 282 mm | 1600/1600 |
| 20° | 267 mm | 630 mm | 1600/1600 |
| 30° | 475 mm | 1154 mm | 1600/1600 |
| 40° | 834 mm | 2213 mm | 1600/1600 |
| 50° | 1650 mm | 5260 mm | 1600/1600 |
| 60° | 2107 mm | 5635 mm | 1280/1600 |

Exactly zero at 0°, which is why it survived: anyone testing square-on to a wall
sees nothing wrong.

**The sampling parameters above are load-bearing and were omitted from an earlier
version of this table.** The mean depends on the grid density, and the 60° row
depended on something worse: without the depth filter it reads 6914 mm mean /
37386 mm p95, because the mis-scaled rays land as far out as 93 m. Those points
do not exist — `backProject` discards anything past 10 m, which is the very
mechanism described two paragraphs below. Quoting the unfiltered figure claimed
an error 3.3× larger than the code can produce. Rows 0°–50° are unaffected;
nothing is dropped there, so filtered and unfiltered agree exactly.

**Reproduced independently**, from the geometry rather than from `backProject`'s
code path, and every cell above matches — including the 1280/1600 survival at 60°
and the 6913 mm unfiltered mean. Two conventions had to be recovered by trial to
get there, and neither is stated above, so they are stated here: *"a wall at 2 m"*
means the **perpendicular** distance from the camera to the plane, not the
distance along the optical axis (reading it the other way scales every row by
$\cos\theta$ — 251 mm rather than 267 mm at 20°); and the wall is tilted about
the camera's **Y** axis.

That second one is not cosmetic. At $\text{rotationNeeded} = 90$ the tilt axis
does not matter, but at **180 it does**: tilting about Y gives 265 mm at 20°,
tilting about X gives **486 mm**. The 60° row is likewise tilt-dependent once the
depth filter engages — 2107 mm about Y, 6913 mm about X, with nothing dropped in
the latter. So the table is a slice through a parameter the table does not name,
and the 180° figure in §8.2 is the Y-tilt value specifically. Any device
measurement compared against these numbers has to match the tilt axis too, or the
comparison is between two different quantities.

### 8.2 A device test that does not require the fix

`rotationNeeded = (sensorOrientation - displayDegrees + 360) \bmod 360`, and the
app is `screenOrientation="fullUser"`. On a phone with the usual
$\text{sensorOrientation} = 90$, the *device's physical orientation* selects
whether the bug is active at all:

| held | `rotationNeeded` | error at 20° obliquity |
|---|---|---|
| landscape | 0 | **0.0 mm — no mismatch** |
| portrait | 90 | 267 mm |
| landscape, other way | 180 | 265 mm (Y-tilt; 486 mm about X — see §8.1) |

So the hypothesis is falsifiable on the current build with no code change:
**capture a target in landscape, then in portrait, same wall, same angle.** If
landscape relocalizes and portrait does not, the convention is the cause. If they
behave identically, this section is wrong and Phase 0 should be dropped.

Two secondary predictions, useful because they are already observable in the
diagnostics overlay: healthy match counts paired with a **low inlier ratio**
(PnP cannot fit a non-rigidly distorted cloud), and "found $N$ features but only
$M$ landed on the wall surface" refusals, since mis-scaled depths fall outside
`backProject`'s 0.1–10 m trust range and are silently dropped.

**The assumption this all rests on** was that ARCore's `camera.pose` is in the
physical camera frame rather than a display-oriented one — i.e. that ARCore
handles display rotation through `setDisplayGeometry`/`transformCoordinates2d`
and not by rotating the pose. If that were false there would be no mismatch, and
it was recorded here as the first thing to check if the device test came back
negative.

**It is no longer an assumption. ARCore's reference documentation settles it, and
settles it in favour of the diagnosis.** `Camera.getPose()` is specified as the
pose of the *physical* camera, with "right" and "up" *"relative to the image
readout in the usual left-to-right, top-to-bottom order"* — the raw sensor frame.
`Camera.getDisplayOrientedPose()` is a separate method returning the virtual
camera pose with "right" and "up" *"relative to current logical display
orientation"*, and the docs state the two *"differ by a local rotation about the Z
axis by a multiple of 90°"* — which is precisely the $R_z$ posited above, named by
Google in the same terms. `Camera.getImageIntrinsics()`, the source of the
intrinsics the capture path then rotates by hand, is likewise documented as
*"the **unrotated** camera intrinsics for the CPU image."*

The codebase demonstrates it knows the difference: `ArRenderer` reads
`camera.displayOrientedPose` for motion estimation, and `camera.pose.inverse()`
for the mapping view matrix that reaches `backProject`. So the two frames are
distinguished deliberately elsewhere and conflated here.

Two consequences for E0b. First, its prior should be much stronger than "plausible
mechanism" — the frames provably differ by exactly the rotation the model uses.
Second, and more usefully, **a negative E0b result can no longer be explained by
this assumption failing.** The paper's designated escape hatch is closed. If the
device shows identical behaviour in both orientations, the explanation has to be
found somewhere else — that the depth error is real but dominated by other error
sources, that the reloc path never reaches `backProject`'s output in the way
assumed, or that `rotationNeeded` is not what the device actually computes. Those
are the hypotheses to carry into a negative result, and they are not
interchangeable with the one that was written here.

### 8.3 Why the correction is not local

Rotating the capture view moves $F$ into the rotated frame, while `PoseFusion`
composes $V_{\text{cur}}^{-1} \cdot \text{pnp} \cdot T$ with $V_{\text{cur}}$
unrotated — so the composition needs the matching change, and every rotation
sign must be right or the overlay lands worse than today. This is a system-wide
convention change.

**It is a prerequisite, not a parallel task.** Every 3D quantity in Approach B —
$\Phi$, the partition, geometric stability, the offset fit — is built on
$F$ being the true wall geometry. Building on a skewed $F$ would produce a
mechanism that cannot be evaluated: failures would be unattributable between the
new design and the old convention bug. Resolve it first (`EVALUATION.md` E0).

---

## 9. Summary

The teleological observation is sound and, as far as we can tell, novel. The
deployed realization used the goal state's weakest channel — global cross-modal
appearance matching — and was in any case broken at all four stages examined
(three disconnected, one gated shut), so it has never been tested.

The proposed realization partitions the goal state. The spatial channel is exact
and available immediately; it protects the durable reference and constrains the
appearance search. The appearance channel then operates locally, where it is
tractable, and its disagreement with the spatial prediction yields a pose-error
estimate the system currently lacks.

The teleological claim survives intact: the goal state still makes the system
better as work progresses. It is simply wired to a channel that can carry the
load.

---

*Companion documents: [`IMPLEMENTATION.md`](IMPLEMENTATION.md),
[`EVALUATION.md`](EVALUATION.md), [`PARAMETERS.md`](PARAMETERS.md).*
