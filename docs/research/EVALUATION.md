# Evaluation and parameter-tuning plan

Companion to [`PAPER.md`](PAPER.md) and [`IMPLEMENTATION.md`](IMPLEMENTATION.md).
This document says how to obtain ground truth, how to make runs repeatable, which
experiments set which constants, and — for each one — *why that experiment and not
another*. The reasoning is the point; a sweep whose rationale is "we had to pick a
number" produces a number nobody can defend later.

`PARAMETERS.md` is the flat lookup table this document generates.

---

## 1. The measurement problem

### 1.1 What we actually want to know

The user-facing quantity is: **how far, in millimetres on the wall, is the
projected overlay from where it should be, while the wall is being painted.**
Everything else — inlier ratios, match counts, confidence scalars — is
instrumentation. It is easy to optimize the instrumentation and lose the
quantity, and this project has already been at risk of that: `paintingProgress`
looked like a health metric and was in fact two signals fighting.

So every experiment below terminates in one of four *outcome* metrics, and the
intermediate metrics are only ever used to *explain* an outcome, never to justify
a change on their own:

| Outcome | Column | Definition |
|---|---|---|
| Accuracy | `errMm` | Overlay position error at the wall, mm |
| Angular accuracy | `errDeg` | Overlay orientation error, degrees |
| Stability | `jitterMm` | Std-dev of position over a 30-sample window (`DriftCostProbe`'s existing window) |
| Availability | `availability` | Fraction of frames with a trusted pose |

Plus two secondary outcomes that gate shipping but never motivate a change:
`recoveryMs` (time to relock after tracking loss) and the cost channels
(`pnpRelocMs`, `batteryMa`, `tempC`).

### 1.2 Why this is hard here

Three specific obstacles, each of which shapes the design that follows.

**No external ground truth.** There is no motion capture rig, no fiducial ground
plane, no second tracked device. The only thing that knows where the wall is, is
the system under test. Measuring a tracker with itself is circular.

**The independent variable destroys the experiment.** The thing we are varying —
how much of the wall has been painted — is irreversible and takes hours. You
cannot run a parameter sweep on a real mural: by the time you have tried the
second value, the wall is different.

**Everything is coupled.** The relocalizer is a feedback loop. A parameter that
changes the match count changes the inlier ratio, which changes whether
`PoseFusion` hard-snaps, which changes the pose the next fingerprint update is
built from. One-factor-at-a-time sweeps on the live system measure the loop, not
the factor.

Sections 2, 3 and 4 answer these three in order.

---

## 2. Ground truth without an external rig

Three sources, in decreasing order of trustworthiness and increasing order of
availability. Use the strongest one that applies to the question being asked.

### 2.1 Synthetic — exact ground truth, no camera

For anything that is pure geometry, construct the scene analytically: place a
virtual wall plane at a known pose, a virtual camera at a known pose, project
known 3D points to pixels with known intrinsics, and run the code under test on
those pixels. Error is measured against numbers you wrote down.

**This is the only source with exact truth, and it is the right one for more of
the plan than is obvious.** Φ, the search-radius formula, the grid bucketing, the
rotation convention, and `PoseFusion`'s composition are all fully determined by
geometry. They do not need a camera and should never be evaluated with one — a
camera adds noise that hides sign errors.

Experiments E0 and E1 are synthetic. They run in the JVM, in CI, on every commit.

*Limit:* synthetic tests cannot tell you whether a threshold is right for real
imagery. They tell you whether the math is right. Do not use them for E4–E8.

### 2.2 Static-return — real imagery, self-consistent truth

Record a session in which the device leaves a known pose and returns to it,
without the wall changing. ARCore's Recording API captures the full sensor stream
(`ArRecordingController.startRecording`), and playback re-runs it
deterministically.

The truth signal is **return error**: the pose reported on the return visit,
compared to the pose reported on the first visit to the same physical spot. It is
self-consistent rather than absolute — it cannot tell you the anchor is 3 cm off
in world space — but it measures exactly the drift-and-relock behaviour the
system exists to fix, and it needs no external equipment.

Make the return unambiguous by marking a physical spot on the floor with tape and
returning the device to a tripod or a fixed rest. Precision of the return matters
more than accuracy of the mark: a repeatable 2 mm placement error is a constant
offset that cancels between conditions.

*Limit:* a static-return recording has no painting in it, so it cannot exercise
the decay hypothesis at all. That is §2.3's job.

### 2.3 Synthetic painting progression — the key trick

This is the mechanism that makes the whole evaluation possible, and it deserves
the explanation.

**The problem it solves:** we need `errMm` as a function of coverage, for many
parameter values, on identical camera motion. Painting a real wall gives one
coverage trajectory, once, non-reversibly, with camera motion that is different
every time. That is one sample of a function we need dozens of points on.

**The trick:** record the wall *unpainted*, once, with rich camera motion —
approach, retreat, oblique passes, a tracking-loss event (cover the lens), a
relock. Then synthesize the painting by compositing the design onto the recorded
frames at increasing coverage fractions, using the *recorded* pose to place the
composite correctly in each frame.

That yields a family of datasets `{D_0, D_10, …, D_100}` — same camera motion,
same lighting, same wall, differing only in how much of the design has been
"painted" onto it. Coverage becomes a controlled variable instead of an
uncontrollable one.

**Why this is legitimate.** The hypothesis under test is about *appearance decay*
— that features inside the footprint stop matching as paint covers them. Compositing
reproduces exactly that: the pixels inside the covered region change to the
design's pixels, and the features there stop matching the original wall. The
causal chain being tested is intact.

**Where it is not legitimate, stated plainly.** Compositing is not photorealistic
painting. Four differences matter, and each one biases in a knowable direction:

| Difference | Effect on results | Direction of bias |
|---|---|---|
| No specular response from wet paint | Real paint is glossier; real decay is worse | Optimistic |
| No brush texture / bristle noise | Composited region is smoother than real paint | Optimistic |
| Sharp compositing edge | Real coverage boundaries are ragged; sharp edges give the corroboration matcher an unearned strong feature | Optimistic |
| Overspray absent | Real painting degrades a margin outside the design | Optimistic for `F_out` |

**Every bias runs the same way: synthetic decay is gentler than real decay.**
That means a parameter tuned on synthetic data is tuned optimistically, and a
*failure* on synthetic data is a hard failure. Use this asymmetry: treat synthetic
results as an **upper bound on performance**, and require a real-mural
confirmation run (§4.3) before shipping any parameter it sets.

Mitigate the sharp-edge bias specifically by dilating the coverage mask with a
random-amplitude boundary before compositing, and by adding a small overspray
margin outside it. This is cheap and removes the one bias that could produce a
qualitatively wrong conclusion rather than a merely optimistic one.

### 2.4 Real mural — confirmation only

One or two full real sessions, recorded end to end. Used for confirmation, never
for tuning: `n=1`, no repeats, no controlled variable. Its role is to catch
anything the synthetic progression's biases hid.

---

## 3. Repeatability

### 3.1 Deterministic replay

`ArRecordingController.startPlayback(session, file)` re-feeds a recorded session.
The same recording plus the same build produces the same frames in the same
order. That makes A/B comparison of two parameter values a genuinely controlled
comparison — same input, one variable changed.

**Three sources of non-determinism will break this if not handled:**

1. **RANSAC.** `cv::solvePnPRansac` with 100 iterations draws random samples. Two
   runs on identical input can differ. **Fix:** seed OpenCV's RNG
   (`cv::theRNG().state = <constant>`) at the start of each eval run, and log the
   seed. Do not do this in production builds — the fixed seed is an evaluation
   affordance, not a behaviour change.
2. **Thread interleaving.** The reloc thread runs asynchronously and its sleep is
   `locked ? 200 : 60` ms. Which frames it gets depends on scheduling. **Fix:**
   add an eval-only synchronous mode that runs reloc inline on every Nth frame.
   Report both — the async numbers are what users get, the sync numbers are what
   is comparable.
3. **Wall-clock timestamps.** `DriftCostProbe` writes `tsMs`. Use the frame
   timestamp from the recording, not the system clock, so runs align frame-for-frame.

Without all three, apparent parameter effects will be scheduling noise. This is
the single most common way a tuning exercise produces confident nonsense.

### 3.2 Run identity

Every CSV emitted by `DriftCostProbe` must carry enough metadata to reconstruct
what produced it. Add a sidecar JSON next to the CSV: recording file hash, git
commit, every parameter value from `PARAMETERS.md`, RNG seed, sync/async mode,
device model. A CSV without a sidecar is not evidence.

### 3.3 Repetitions

Even with the fixes above, replay is not perfectly deterministic on a real device
(thermal throttling changes stage timings, which changes the async reloc's frame
cadence). **Three repetitions per condition, report the median.** Three is chosen
because the dominant residual noise is thermal drift, which is monotonic within a
run — the median of three rejects the run that throttled without needing a
variance estimate that three samples cannot support.

Run conditions in randomized order within a block, not grouped, so thermal drift
does not align with the independent variable. This matters more than it sounds:
running all of condition A then all of condition B on a phone guarantees B runs
hotter.

---

## 4. Experimental design

### 4.1 Why staged rather than a grid

There are roughly a dozen tunable constants. A full factorial at three levels
each is ~500k runs; even a fractional design is out of reach on a device that
needs to cool down. And most of the constants are not interesting — they have one
obviously correct order of magnitude and the question is only whether it is 0.05
or 0.15.

So: **screen, sweep, confirm.**

**Screening** asks "does this parameter matter at all?" — two levels per
parameter, far apart, using a resolution-IV fractional factorial so main effects
are not aliased with two-factor interactions. Cheap, and it typically eliminates
half the parameters from further work. Parameters whose effect on `errMm` is
below the run-to-run median absolute deviation get fixed at their prior and
documented as insensitive.

**Sweeping** takes the survivors and runs a fine one-dimensional sweep of each,
holding the others at their screened-best. Valid because screening also estimates
the two-factor interactions, so we only accept a 1-D sweep for parameters whose
interactions came out negligible. Any parameter pair with a real interaction gets
a small 2-D grid instead — and the pair most likely to interact is already
identifiable in advance: the corroboration search radius `ρ` and the corroboration
Lowe ratio, because the whole argument of §5.5 in the paper is that one changes
the correct value of the other. Plan the 2-D grid for that pair from the start.

**Confirmation** re-runs the chosen configuration on the real-mural recording and
on a held-out synthetic progression built from a *different* wall recording than
the one used for tuning. The held-out wall is essential — otherwise the tuning
has fitted the texture of one specific wall.

### 4.2 What "better" means

Comparisons use the existing rubric where it applies. `EvalDecision` already
encodes an accuracy-paramount principle: `GOOD_ERR_MM = 10f`, and a mechanism is
KEPT if it is effective *or* uniquely covering, DROPPED only if both redundant and
not more accurate. Cost never flips an accurate mechanism to DROP.

Extend the same principle to parameters. The objective, in priority order:

1. `errMm` at high coverage (≥70%) — this is the thesis
2. `availability` at high coverage
3. `jitterMm`
4. `errMm` at low coverage — must not regress
5. cost

A parameter value that improves (1) while regressing (4) is rejected. That
asymmetry is deliberate: the system already works at 0% coverage, and a change
that trades away the working case for the broken one has not made progress, it
has moved the failure.

### 4.3 Ship gate

No parameter ships on synthetic evidence alone. The gate is: **screened,
swept, confirmed on a held-out synthetic wall, and confirmed on a real mural
recording.** §2.3's bias analysis is why — every synthetic bias is optimistic, so
synthetic-only tuning ships numbers that are too aggressive.

---

## 5. The experiments

Each experiment states its **question**, its **method**, its **reasoning** (why
this design answers the question), what it **sets**, and what result would
**falsify** the assumption behind it.

---

### E0 — Rotation convention

**Question.** Is the display-rotation mismatch real, how large is it as a
function of obliquity, and does the Phase 0 fix eliminate it?

**Method.** Synthetic (§2.1). Virtual wall, virtual camera at obliquity
0/10/20/30/40/50/60°, analytically projected feature pixels, rotated intrinsics.
Run `PlaneMarks.backProject` under (a) current `main`, (b) convention A, (c)
convention B. Measure recovered-vs-true 3D point error in mm.

**Reasoning.** This is pure geometry with an exactly known answer, so a camera
would only add noise. The obliquity sweep is the diagnostic: the paper's claim is
that the error *vanishes at 0° and grows with obliquity*, because a `(0,0,±1)`
normal is invariant under a rotation about the optical axis. If the measured
error curve does not have that shape — flat, or largest at 0° — the diagnosis in
§8 of the paper is wrong and Phase 0 is chasing the wrong thing.

**Expected values.** Computed analytically in `PAPER.md` §8.1: 0.0 mm at 0°,
267 mm at 20°, 834 mm at 40°, 2107 mm at 60°, for a wall at 2 m with 1080×1920
display intrinsics on a 40×40 pixel-space grid. E0's job on device is to confirm
the *shape*, not to discover it. Writing the prediction down before running is
what makes a surprise informative — if the device disagrees, the model of the
defect is wrong, not merely the constant.

`PlaneMarksObliquityTest` asserts the same defect but **does not encode those
decimals**, and should not be read as doing so. It samples uniformly on the wall
rather than uniformly in pixel space, which weights oblique views differently and
gives ~328 mm at 20° and ~1148 mm at 40° for the identical bug. Its assertions
are therefore ranges and orderings. Two samplings, one defect; quoting either as
*the* number would be picking a convention and calling it a measurement.

**Sets.** Nothing. It is a correctness gate, not a tuning experiment.

**Falsifies.** If (a) shows <1 mm error at 60°, there is no rotation bug and
Phase 0 should be abandoned. If (b) and (c) both fail, the error is not a pure
`R_z` and the analysis is incomplete.

**Cost.** Minutes. Runs in CI forever after.

---

### E0b — the device test that needs no fix

**Question.** Does the defect appear and disappear with the phone's physical
orientation, as the convention analysis predicts?

**Method.** `rotationNeeded = (sensorOrientation - displayDegrees + 360) % 360`
and the app is `screenOrientation="fullUser"`, so on a typical phone the device's
physical orientation decides whether the mismatch exists at all: **landscape → 0
(no mismatch); portrait → 90; landscape the other way → 180.** Capture a target
in landscape, then in portrait, same wall, same obliquity; then attempt
relocalization from the same standing positions for each. Compare inlier ratio
and lock rate.

**Reasoning.** The cheapest experiment in the document, and the independent
variable is how you hold the phone. Do it *first*, before any of Phase 0's
engineering — a negative result cancels that engineering entirely.

**The independent variable is now recorded per CSV row**, as `rotationNeededDeg`
(0/90/180/270, `-1` not sampled). It was not, and that was a hole in this
protocol rather than a detail: the experiment's entire contrast lived in whoever
ran it remembering which way they had been holding the phone, with nothing in the
data to check that memory against. Worse, `screenOrientation="fullUser"` means the
device can rotate *mid-run*, so a single CSV can straddle both conditions with no
marker where it crossed over — the two populations would mix and the effect would
average toward nothing, which reads exactly like a negative result. Group rows by
`rotationNeededDeg` and compare within groups; do not compare file to file.

Note `0` is the control condition, not a missing value — landscape is the
orientation predicted to *work*, so those rows are the experiment's positive
half, and `-1` is the only "no data" marker.

This does mean E0b now needs a build, contrary to the cost line below. Running it
on a stock build is still possible and still informative — the overlay signals
below are unchanged — but the per-row grouping is what makes the result
attributable.

Watch two secondary signals in the diagnostics overlay, both predicted by the
model: healthy match counts paired with a **low inlier ratio** (PnP cannot fit a
non-rigidly distorted cloud), and "found N features but only M landed on the wall
surface" refusals, since mis-scaled depths fall outside `backProject`'s 0.1–10 m
trust range. The second is measurable directly — §8.1 predicts 1280 of 1600
points surviving at 60° versus all 1600 at 40°.

**Sets.** Nothing. It is the go/no-go on Phase 0.

**Falsifies §8 entirely.** Identical behaviour in both orientations means the
convention is not the cause and Phase 0 should be dropped.

The follow-up this line used to prescribe — "check whether `camera.pose` is
display-oriented" — **has been done, and it is not the answer.** ARCore documents
`getPose()` as the physical camera frame and `getDisplayOrientedPose()` as the
display-oriented one, differing "by a local rotation about the Z axis by a
multiple of 90°" (PAPER.md §8.2). The frames provably differ by the rotation the
model assumes, so a negative result cannot be explained away there. Carry these
instead: the depth error is real but swamped by other error sources; the reloc
path does not consume `backProject`'s output the way §8 assumes; or the device's
actual `rotationNeeded` is not what the formula predicts — which the new
`rotationNeededDeg` column now lets you check directly rather than infer.

**Cost.** One session, plus a build (see above).

---

### E1 — Footprint operator correctness

**Question.** Does Φ map world points to design coordinates correctly under
translation, rotation, and non-square extents?

**Method.** Synthetic. The six cases in `IMPLEMENTATION.md` Phase 1.

**Reasoning.** Φ is consumed by every later phase; an error here is
indistinguishable downstream from a bad threshold. The rotated-anchor case
specifically catches using the forward transform where the inverse was needed,
which is the highest-probability bug in the whole plan because both compile and
both produce plausible-looking numbers.

**Sets.** Nothing. Correctness gate.

---

### E2 — Baseline characterization

**Question.** What are `errMm`, `jitterMm`, and `availability` on `main`, as a
function of coverage?

**Method.** Synthetic progression (§2.3) at coverage 0/25/50/75/100%, current
`main`, three repetitions, randomized order. Also run the static-return recording
(§2.2) for an absolute-ish accuracy anchor.

**Reasoning.** Every later experiment is a comparison, and without this there is
nothing to compare to. Run it *before* any phase lands. Its second job is to
quantify run-to-run noise, which sets the detection threshold for the screening
experiment — a parameter effect smaller than baseline noise is not measurable and
must not be reported as an effect.

**Sets.** The noise floor used by E4.

**Expected shape.** `errMm` rising with coverage. If `errMm` is *flat* with
coverage on `main`, the decay premise is wrong for this dataset and the whole
programme needs re-examining before Phase 2 — that would be P6 passing trivially,
for the wrong reason.

---

### E3 — Progress/confidence split

**Question.** Does separating `paintingProgress` from `corroborationConfidence`
(Phase 5) improve `errMm` or `jitterMm`, and does it change the `CONF_FLOOR`
behaviour?

**Method.** A/B on the synthetic progression, `main` vs Phase 5. Additionally, an
injected-hiccup condition: mask three consecutive frames mid-run to simulate a
momentary tracking failure, and measure how long the confidence channel takes to
recover under each.

**Reasoning.** The hiccup condition is the whole point. The defect is that a
momentary failure decays a *progress* signal by ×0.9 per tick, so a 3-frame
glitch suppresses correction strength for seconds afterwards. A steady-state
comparison would not reveal that; only a transient does. This is the pattern to
copy for any signal-conflation bug — the damage is in the transient response, not
the mean.

**Sets.** Nothing directly, but it validates that `CONF_FLOOR = 0.5` still holds
its contract against the new denominator (predicted-visible rather than
all-design-features).

**Falsifies.** If the recovery time is identical under both, the ×0.9 decay is not
reaching `PoseFusion` by the path assumed and the analysis is wrong somewhere.

---

### E4 — Screening

**Question.** Which parameters measurably affect the outcomes?

**Method.** Resolution-IV fractional factorial, two levels per parameter, on the
synthetic progression at 50% and 100% coverage. Parameters and levels are in
`PARAMETERS.md`.

**Reasoning.** Resolution IV specifically: it confounds two-factor interactions
with each other but not with main effects. We need clean main effects (to decide
what to sweep) and a *warning* about interactions (to decide what needs a 2-D
grid), and Resolution IV is the cheapest design that gives both. Resolution III
would alias main effects with two-factor interactions and could tell us a
parameter matters when its partner is the one doing the work.

Levels are set far apart deliberately — screening asks "does it matter", and wide
levels maximize the signal against E2's noise floor. Fine resolution is E5–E8's
job.

**Sets.** The *shortlist*, not any values. E4 decides which parameters are worth
sweeping; **E11** assigns them numbers. Everything E4 rejects is fixed at its prior
and marked insensitive in `PARAMETERS.md`, *with the screening evidence recorded*,
so a future session does not re-litigate it.

---

### E5 — Promotion gate

**Question.** What values of the promotion gate (`growTrusted`'s inlier floor,
ratio floor, and the Phase 3b spread term) maximize fingerprint quality over
session time?

**Method.** Synthetic progression with `setSelfGrowEnabled(true)`. Sweep the
inlier floor over {10, 15, 20, 30}, the ratio floor over {0.5, 0.6, 0.7, 0.8},
and the spread threshold over {0 (off), 0.15, 0.3, 0.5} of frame area. Primary
readout: PnP inlier ratio as a function of session time (prediction P4). Secondary:
`errMm` at 100% coverage.

**Reasoning.** Promotion is the only mechanism that can corrupt the map
permanently, so it is measured by *trend over time*, not by an endpoint. A gate
that is too loose does not fail immediately — it degrades. A run that ends at an
acceptable `errMm` can still have a downward-sloping inlier ratio, and that is a
failing configuration even though its endpoint looks fine. Fit a slope, not a
mean.

The spread term's `0 (off)` level is included so the experiment can say whether
Phase 3b earned its complexity. If the spread term shows no effect, delete it.

**Sets.** `PROMOTION_MIN_INLIERS`, `PROMOTION_MIN_RATIO`, `PROMOTION_MIN_SPREAD`.

**Falsifies P4.** If geometric promotion's inlier-ratio slope is no better than
appearance promotion's, the partition is not buying anything at promotion time
and Phase 3 should ship disabled permanently.

**Safety.** This is the experiment most likely to produce a configuration that
destroys the map. Run it last among the sweeps, and keep `setSelfGrowEnabled`
defaulted off regardless of outcome until E9's confirmation passes.

---

### E6 — Corroboration search radius `ρ`

**Question.** What normalized search radius maximizes corroboration recall at
acceptable precision?

**Method.** Sweep `ρ` over {0.01, 0.02, 0.05, 0.1, 0.2, 0.5, ∞} where ∞ is the
global search (today's behaviour). Measure `corrobMatched / corrobPredicted`
(recall proxy) and a false-positive estimate obtained by running the same match
against a *deliberately wrong pose* — offset the pose by 20 cm and count matches
that still pass. Those are false by construction.

**Reasoning.** The wrong-pose control is the load-bearing part of this design and
is worth stating clearly: there is no labelled ground truth for "was this match
correct", so precision cannot be measured directly. But a match that survives
when the pose is deliberately wrong cannot be a true match. That gives an
*upper bound* on precision, computed from data we already have, with no labelling.
It is a control condition, not a measurement — and it is the standard way out of
exactly this bind.

The ∞ level is the baseline the paper's argument is against; including it in the
same sweep, on the same data, is what makes P3 testable rather than asserted.

**Sets.** `CORROB_SEARCH_RHO`.

**Falsifies P3.** If the false-positive rate at `ρ=0.05` is not at least 10×
below `ρ=∞` at matched recall, the candidate-set argument (§5.5) does not
survive contact with real descriptors.

---

### E7 — Corroboration Lowe ratio, jointly with `ρ`

**Question.** Does the local test admit a larger Lowe ratio than the global one at
the same precision?

**Method.** 2-D grid: `ρ ∈ {0.02, 0.05, 0.1, ∞}` × ratio `∈ {0.7, 0.75, 0.8, 0.85,
0.9}`. Same recall/precision readouts as E6.

**Reasoning.** This is the one pair identified in §4.1 as certain to interact, so
it gets a grid rather than two 1-D sweeps. The prediction P2 is specifically a
statement about the *shape* of this surface: the iso-precision contour should
bend toward higher ratio as `ρ` shrinks. A 1-D sweep of either parameter cannot
observe a contour.

This is also the experiment that could most improve the system, because it is the
only place where adding a constraint *buys back* recall rather than costing it. If
the contour bends as predicted, the corroboration matcher gets both a tighter
search and a looser threshold, and both help.

**Sets.** `CORROB_LOWE_RATIO`, and refines `CORROB_SEARCH_RHO` from E6.

**Falsifies P2.** A flat iso-precision contour means the search-space argument is
wrong, and the corroboration set should keep the reloc path's 0.75.

---

### E8 — Partition margins

**Question.** How wide should the discard band between `F_out` and `F_in` be?

**Method.** Sweep `innerMargin ∈ {0, 0.02, 0.05, 0.1}` and `outerMargin ∈ {0.05,
0.1, 0.15, 0.25}` on the synthetic progression, with the overspray-margin
augmentation from §2.3 *enabled* — this experiment is meaningless without it,
because a sharp composited edge makes any margin look adequate.

**Reasoning.** The band exists because features near the design's boundary flip
classification as the artist paints to the edge, and a misclassified feature is
worse than a discarded one: a doomed feature in `F_out` corrupts the backbone. So
the objective here is asymmetric — err toward a wider band. Measure the cost of
that (backbone size shrinks, availability may drop) so the trade is explicit
rather than assumed.

**Sets.** `FOOTPRINT_INNER_MARGIN`, `FOOTPRINT_OUTER_MARGIN`, and the
`F_out` minimum-size floor that drives the capture refusal.

---

### E9 — Cost and thermal budget

**Question.** Does the hybrid fit in the frame budget, and what does it cost in
battery and heat?

**Method.** Full configuration on the real-mural recording and a 20-minute live
session. Readouts: `pnpRelocMs`, `drawMs`, `batteryMa`, `tempC`,
`nativeHeapKb`, and the frame-time distribution's 95th percentile.

**Reasoning.** The 95th percentile, not the mean. The original complaint that
started this work was "it constantly feels like there is drift because there's
lag in the lineup" — that is a *tail* symptom. A mean frame time inside budget
with a fat tail feels exactly like drift to a user, because the overlay stalls and
then jumps. Reporting means here would reproduce the original bug's invisibility.

The 20-minute live session is required because thermal throttling does not appear
in a 2-minute replay, and a mural session is hours.

**Sets.** Nothing. It is a ship gate. A configuration that wins E5–E8 and fails E9
is not shippable and the sweeps must be re-run with a cost constraint.

---

### E10 — End-to-end, the thesis

**Question.** Does overlay error at 100% coverage exceed error at 0%?

**Method.** Final configuration, on the held-out synthetic wall and the real
mural. Compare `errMm` at 0% and 100%.

**Reasoning.** This is P6 and it is the only experiment whose result matters to a
user. It is stated as "does not exceed" rather than "improves" deliberately: the
system's job at 0% coverage is already adequate, and the thesis is that the
teleological mechanism *preserves* that as the wall changes. An improvement at
100% would be a bonus; parity is success.

**Falsifies P6.** If `errMm` at 100% is materially worse while P1–P5 all pass, the
mechanism works and the premise is wrong — something other than appearance decay
is driving the error. In that case, stop tuning and go look for it; the most
likely candidate is the anchor itself drifting, which §7 of the paper flags and
which would show up as P5's correlation being with anchor error rather than pose
error.

---

### E11 — Sweeping the screened survivors

**Question.** For each parameter E4 flagged as mattering, what value minimizes the
objective in §4.2?

**Method.** One-dimensional sweep of each survivor, holding the others at their
screened-best, on the synthetic progression at 0/50/100% coverage. Any pair whose
E4 interaction estimate is non-negligible gets a small 2-D grid instead. Levels
come from `PARAMETERS.md`; the candidate set is expected to include the PnP
reprojection threshold, `MIN_INLIER_RATIO`, `BASE_ALPHA`, `CONF_FLOOR`, the two
reloc-thread sleeps (under the E9 cost constraint), and the three
`SEARCH_RADIUS_*` bounds.

**Reasoning.** §4.1 describes screen → sweep → confirm, and this is the sweep. It
is a separate experiment rather than a clause inside E4 because E4's job is
*selection* and E11's is *assignment*, and conflating them is how a table ends up
routing a dozen parameters to an experiment that sets nothing. The 1-D sweep is
only legitimate for parameters E4 showed to be interaction-free — that
qualification is the reason E4 has to run first and has to be Resolution IV.

**Sets.** Every parameter in `PARAMETERS.md` whose "Set by" cell names E11.

**Falsifies.** Nothing on its own. If a swept parameter's response curve is flat
across its whole range, E4's screening produced a false positive and the parameter
returns to its prior with that recorded as the evidence.

---

### E12 — Does the partition earn its place?

**Question.** Is relocalization availability at ≥50% coverage higher with the
footprint partition than without it?

**Method.** A/B on identical replayed synthetic-progression datasets: Phase 2
enabled versus Phase 2 forced to "everything is backbone" (the rollback constant
from `IMPLEMENTATION.md`, which reproduces `main`'s behaviour exactly). Measure
`availability` and `errMm` at coverage 0/25/50/75/100%.

**Reasoning.** This is **P1**, and P1 is the prediction that justifies the
partition — the single largest structural change in the plan. Every other
experiment from E5 onward tunes *within* the new design and takes the partition as
given; none of them compares it to not having it. Without E12 the partition would
ship on the strength of its argument alone, which is precisely the failure mode
the paper spends §4 documenting.

The rollback constant makes this a genuinely controlled A/B: same code path, same
build, one boolean. Nothing else differs, so a difference in availability is
attributable to the partition and not to anything that landed alongside it.

The ≥50% threshold is where the prediction lives — below that, too little of
`F_in` has decayed for the two conditions to diverge, and a null result at 25%
would be uninformative rather than falsifying.

**Sets.** Nothing. It is the go/no-go on Phase 2.

**Falsifies P1.** If availability is equal or worse with the partition at every
coverage level, the backbone is not more durable than the undifferentiated set,
and Phase 2 should be reverted rather than tuned. Check `backboneFeatures` before
concluding that — an empty or tiny `F_out` (the wall-filling-design case) makes
P1 untestable rather than false.

---

### E13 — Does disagreement predict pose error?

**Question.** Does the rigid-offset fit over corroborated `F_in` features correlate
with measured overlay error?

**Method.** On the synthetic progression, at each frame with ≥8 corroborated
features, fit the rigid 2-D offset between predicted and observed footprint
positions (§5.6 of the paper). Log the fitted offset magnitude alongside `errMm`.
Compute the correlation over frames, bucketed by coverage: 0–30%, 30–60%, 60–100%.

**Reasoning.** This is **P5**, which had no experiment. It matters for two
reasons beyond completeness. First, if the correlation holds, the offset fit
becomes a *free error estimate* — a signal the system can act on in real time
without ground truth, which is otherwise unavailable on a real wall. Second, it is
the designated detector for the anchor-drift threat in §7 of the paper: if the fit
correlates with anchor error rather than pose error, P5 passes while P1 fails, and
that specific pattern is what tells you to stop tuning the relocalizer.

The ≥8-feature floor and the coverage bucketing are both necessary. Below 8
features the fit is dominated by noise; and the prediction is explicitly that the
correlation appears *once coverage exceeds ~30%*, so pooling all coverages would
average a real effect against a regime where none is expected.

**Sets.** Nothing directly. If P5 holds, it justifies a follow-on: feeding the
fitted offset into the Phase 4 search radius as a better uncertainty estimate than
`errMm`, which is only available in evaluation.

**Falsifies P5.** Correlation at `r ≤ 0.7` above 30% coverage. Combined with a
passing P1, that points at anchor drift rather than relocalization error.

---

## 6. Analysis conventions

**Report medians and interquartile ranges, not means and standard deviations.**
The outcome distributions are bounded below (error cannot be negative) and have
occasional catastrophic outliers (a full tracking loss). A mean is dominated by
the outliers; a median describes the typical frame, and the outlier rate is
reported separately as `availability`.

**Report the outlier rate as a first-class result.** A configuration with a lower
median error and a higher tracking-loss rate is usually worse for a user, and an
error-only summary hides it.

**Never compare across recordings.** Different walls have different texture
density, so absolute numbers are not portable. Every comparison is within one
recording, between conditions.

**Log the reject histogram.** `RelocDiagnostics.reject` turns a null result into
a diagnosis. A configuration whose failures are `NO_FEATURES` needs different
work than one whose failures are `FEW_INLIERS`, and the aggregate error number
cannot distinguish them.

**Write down the prediction before running the experiment.** Each experiment above
states an expected shape. Recording the prediction first is what makes a
surprising result informative rather than something to rationalize.

---

## 7. Minimum viable evaluation

The full programme is large. If time is short, this subset gives the most
information per hour:

1. **E0 and E1** — synthetic, cheap, run in CI, catch sign errors that would
   invalidate everything else. Non-negotiable.
2. **E2** — the baseline. Without it there is no comparison.
3. **E3** — the progress/confidence split. Cheapest real improvement in the plan.
4. **E12** — does the partition earn its place? One boolean, two runs, and it is
   the go/no-go on the plan's largest structural change.
5. **E7** — the `ρ` × ratio grid. Tests the paper's central mechanism directly and
   sets the two parameters that matter most.
6. **E10** — the thesis.

E4/E11 can be collapsed by fixing the insensitive parameters at their priors and
sweeping only the two or three you most distrust. E5 can be skipped entirely by
shipping promotion disabled, which is the default. E6 is subsumed by E7's grid.
E8's margins can take their priors. E13 is skippable — P5 is a nice-to-have, not
load-bearing. E9 must not be skipped before shipping, but can be deferred until
after the mechanism is shown to work.

**E12 is not skippable.** It is the only experiment that asks whether the
partition helps at all, and it is cheap: one boolean, two runs.
