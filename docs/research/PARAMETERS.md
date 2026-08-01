# Parameter reference

Every tunable constant in the relocalization path: where it lives, what it is
now, why it is that, and which experiment in [`EVALUATION.md`](EVALUATION.md)
sets it.

**Read the "Basis" column carefully.** It distinguishes constants that were
measured from constants that were guessed. Most of them were guessed. That is not
a criticism of the code — you have to start somewhere — but it means the priors
below carry no evidential weight, and a sweep result should overrule any of them
without argument.

Basis legend:

| Tag | Meaning |
|---|---|
| **derived** | Follows from geometry or a contract; not free to tune |
| **measured** | Set from an observation on this project |
| **conventional** | Standard value from the literature or OpenCV defaults |
| **guessed** | Chosen to be plausible. No evidence. |
| **proposed** | Does not exist yet; introduced by `IMPLEMENTATION.md` |

---

## 1. Relocalization — existing

| Parameter | Location | Current | Basis | Sweep? | Set by |
|---|---|---|---|---|---|
| Reloc Lowe ratio | `MobileGS.cpp:310,403,697,774` | `0.75` | conventional | fixed | — |
| ORB feature count (query) | `MobileGS.cpp:86` | `1500` | derived — symmetry with the fingerprint side; the comment at `:80` is an argument, not an observation | screen | E4 → E11 |
| ORB feature count (artwork) | `MobileGS.cpp:1094` | `1500` | derived — must match the query side for the type-compatible match | fixed | — |
| CLAHE clip limit | `MobileGS.cpp:25` | `2.0` | conventional | screen | E4 |
| CLAHE tile grid | `MobileGS.cpp:25` | `8×8` | conventional | fixed | — |
| PnP RANSAC iterations | `MobileGS.cpp:476` | `100` | conventional | screen | E4 |
| PnP reprojection threshold | `MobileGS.cpp:476` | `8.0` px | guessed | **sweep** | E4 → **E11** |
| PnP RANSAC confidence | `MobileGS.cpp:476` | `0.99` | conventional | fixed | — |
| Min correspondences for PnP | `MobileGS.cpp:455,462` | `8` | derived — PnP needs ≥6, plus margin | fixed | — |
| Min inliers to accept | `MobileGS.cpp` (`inliers.size() < 6`) | `6` | derived — minimum for a determined pose | fixed | — |
| Reloc thread sleep (locked) | `MobileGS.cpp:547` | `200` ms | guessed | screen | E4 → **E11** (cost-constrained) |
| Reloc thread sleep (searching) | `MobileGS.cpp:547` | `60` ms | guessed | screen | E4 → **E11** (cost-constrained) |

**Note on the 8.0 px reprojection threshold.** This is the highest-leverage
existing constant that nobody has measured. It trades inlier count against pose
quality directly, it interacts with the ORB feature count, and at 8 px on a 1080p
frame it is loose. It should be in the screening set.

---

## 2. Pose fusion — existing

All in `feature/ar/.../anchor/PoseFusion.kt`, companion object.

| Parameter | Current | Basis | Sweep? | Set by |
|---|---|---|---|---|
| `MIN_INLIER_RATIO` | `0.5` | guessed | **sweep** | E4 → **E11** |
| `BASE_ALPHA` | `0.25` | guessed | **sweep** | E4 → **E11** |
| `CONF_FLOOR` | `0.5` | **rationale withdrawn in 5b.2, value retained** — the old "exactly twice as hard" reasoning assumed an input that can reach 1.0, and the new denominator cannot; see below | **sweep** | **E11**, which must measure the achievable corroboration maximum FIRST |
| `COLD_SNAP_INLIER_RATIO` | `0.7` | guessed | screen | E4 → E11 |
| `COLD_SNAP_MIN_INLIERS` | `20` | guessed | screen | E4 → E11 |
| `COLD_SNAP_DIST_M` | `0.20` m | guessed | screen | E4 → E11 |
| `COLD_SNAP_ANGLE_DEG` | `15°` | guessed | screen | E4 → E11 |

`CONF_FLOOR` changed meaning under Phase 5b, and **5b.2 has now re-derived it.
The rationale did not survive; the number did.**

The old justification was that at 0.5 a fully corroborated wall pulls exactly
twice as hard as a bare one. That was true of the old input — painting progress
over the whole design, whose 1.0 was at least conceptually reachable by painting
the whole mural. The input is now `matched / predicted`, and **its ceiling is not
reachable on any real wall**: descriptor repeatability across a repaint, the
lighting difference between registration and painting, and Phase 4's
lone-candidate skip each hold it below 1. So `effConf` spans
`[0.5, 0.5 + 0.5·m]` for some achievable maximum `m`, and the 2x is arithmetic at
an input the system cannot produce rather than a property of the system. At
`m = 0.6` a well-painted wall pulls 1.6x a bare one.

The value stays 0.5 because `m` has never been measured, and choosing a floor to
compensate for an unknown ceiling is guessing dressed as derivation. The two
available arguments also point in opposite directions: the new signal moves per
*frame* in both directions where progress moved over hours, which argues for a
higher floor so a momentary dip does not slash correction — while the whole point
of splitting confidence from progress was to let correction scale by something
trustworthy, which argues for a lower one and a wider range.

**E11 must measure `m` before sweeping the floor.** The two jointly determine the
real dynamic range, so a sweep of the floor alone reports the wrong contour. E3
tests that the *contract* still holds, and `PoseFusionTest`'s
`the floor bounds correction from below at any achievable corroboration` states
that contract in a form that survives the number changing — bare walls still
correct, correction is monotone in corroboration, and nothing goes below the
floor. Mutation-verified: removing the floor term fails 4 tests, inverting the
corroboration sense fails 2.

**"E4 → E11" is not a typo.** E4 is the screening stage and sets nothing but the
shortlist; it decides *which* parameters are worth sweeping. E11 is the sweep that
assigns them values. An earlier draft of this table routed eleven parameters
straight to E4, E5, E7 or E9 — experiments whose own "Sets." line says they set
nothing, or set something else — which meant a third of the table had no
experiment that closed it.

---

## 3. Self-grow / promotion — existing and proposed

| Parameter | Location | Current | Basis | Sweep? | Set by |
|---|---|---|---|---|---|
| `growTrusted` absolute inlier pass | `MobileGS.cpp:~805` | `≥20` | guessed | **sweep** | E5 |
| `growTrusted` hard reject | same | `<10` | guessed | **sweep** | E5 |
| `growTrusted` ratio floor | same | `0.6` | guessed | **sweep** | E5 |
| Wall mark count floor | `MobileGS.cpp:810` | `12` | guessed | screen | E4 → E5 |
| `kMaxWallMarks` (ceiling) | `MobileGS.h` | `5000` | guessed — a memory guard, not a quality one | fixed | — |
| `PROMOTION_MIN_SPREAD` | *proposed*, Phase 3b | — | proposed | **sweep**, incl. an off level | E5 |

The off level for `PROMOTION_MIN_SPREAD` is there so E5 can delete the term if it
earns nothing.

**`mSelfGrowEnabled` defaults to `false`** (`MobileGS.h`), and so does the eval
overlay's toggle. It shipped as `true` for months while the function's own comment,
this plan, and the evaluation plan all said otherwise — meaning the one mechanism
that permanently mutates the reloc map ran unsupervised in every release build with
no user-facing switch. If any future change touches promotion, re-check **both** the
native initializer and `MainActivity`'s `selfGrowOn`; they are two places that have
to agree and nothing enforces it.

---

## 4. Footprint partition — landed

Phases 1–2 have landed, so these now have real locations and real values. Every
one is still a **prior**, not a measurement: E8 is the experiment that sets them.

| Parameter | Location | Current | Prior it shipped as | Basis | Set by |
|---|---|---|---|---|---|
| `DEFAULT_INNER_MARGIN` | `FingerprintPartition.kt` | `0.04` | `0.05` | guessed — a few per cent of the design's half-extent, of the order of a brush width at mural scale | E8 |
| `DEFAULT_OUTER_MARGIN` | `FingerprintPartition.kt` | `0.10` | `0.15` | guessed — wider than inner, because overspray extends past the design and a doomed feature in `F_out` is worse than a discarded one | E8 |
| `MIN_BACKBONE` | `FingerprintPartition.kt` | `40` | `40` | guessed — 5× the native PnP correspondence floor of 8, leaving room for the match rate | E8 |

**The margins shipped at values this table did not predict, and the reason is not
flattering.** They came in at `0.04` / `0.10` against a pre-registered `0.05` /
`0.15`. There is no history to appeal to here: `Footprint.classify` takes its
margins as required parameters with no defaults, so Phase 1 landed no values at
all — `DEFAULT_INNER_MARGIN` / `DEFAULT_OUTER_MARGIN` were introduced by Phase 2
itself, in `6965cef`, and simply did not match the priors sitting in this file.
Nobody noticed until an audit checked. Both columns are kept so E8 reports
against the pre-registered numbers rather than the ones that happened to be
typed. Note the ratio moved too: 3.0× inner as pre-registered, 2.5× as shipped.

`MIN_BACKBONE` raises `ArUiState.backboneTooSmall` ("the artwork leaves too
little bare wall to lock onto"). Set too high it cries wolf; too low it stays
silent on a target that cannot work. E8 measures both sides.

It is checked **when the artwork is placed or resized, not at capture**. Target
creation is what establishes the anchor the artwork sits on, so a capture has no
footprint to measure and cannot answer the question at all — an earlier cut of
Phase 2 put the check there and it could never fire. For the same reason it is
independent of `MetricFingerprintBuilder`'s `minPoints` floor of 20: "did enough
features land on the wall" and "is enough of what landed outside the artwork" are
different questions asked at different moments.

---

## 5. Spatially-constrained corroboration — landed, unmeasured

From Phase 4. These are the most important numbers in the plan. Phase 4 has
landed whole (4.1–4.8): the Kotlin references, the C++ transliterations, the
gated match, the diagnostics. Every value below is still a pre-registered prior —
landing the mechanism is not measuring it, and none of E6/E7/E11 has run.

Each number exists twice, in a tested Kotlin reference and in the C++ that
actually runs. `CorroborationTranslitTest` pins the two together by reading the
headers as text; see the note at the end of this section for what that does and
does not buy.

| Parameter | Location | Current | Prior | Basis | Set by |
|---|---|---|---|---|---|
| `SearchRadius.RHO` / `kRho` | `SearchRadius.kt`, `SearchRadius.h` | `0.05` | `0.05` | guessed — the paper's §5.5 worked example: at ρ=0.05 the candidate set is ~0.2% of the design | E6, refined by E7 |
| `kCorrobLoweRatio` | `MobileGS.h` | `0.85` | `0.85` | **a prediction, not a setting** — see below | E7 |
| `kRelocLoweRatio` | `MobileGS.h` | `0.75` | `0.75` | the value that shipped; split out in 4.7 so E7 can move the one above without moving this | E7 (as the control) |
| `SearchRadius.MIN_PX` / `kMinPx` | `SearchRadius.kt`, `SearchRadius.h` | `4` | `4` | guessed — below a few pixels the radius is inside the keypoint localization noise | **E11** |
| `SearchRadius.MAX_PX` / `kMaxPx` | `SearchRadius.kt`, `SearchRadius.h` | `120` | `120` | guessed — above this the "local" search is not local and the argument collapses | **E11** |
| `SearchRadius.ERR_GAIN` / `kErrGain` | `SearchRadius.kt`, `SearchRadius.h` | `1.0` | `1.0` | guessed — radius scales linearly with measured `errMm`; 1.0 is the neutral prior | **E11** |
| `SearchRadius.REPROJ_GAIN` / `kReprojGain` | `SearchRadius.kt`, `SearchRadius.h` | `2.0` | *added in 4.5* | guessed — deliberately not neutral; see below | E7 |
| `kCorrobConfirmations` | `MobileGS.h` | `2` | *added in 4.5* | reasoned, not guessed — the smallest value that bounds a monotone counter; see below | E6 |

All of them landed at their pre-registered priors, which is worth stating
explicitly given §4's history: the Phase-2 margins shipped at values this file did
not predict and nobody noticed until an audit checked. `REPROJ_GAIN` is the one
row with no prior, because the parameter did not exist when this file was
written — it is registered here now, before any experiment has looked at it.

**Why there are two drift terms, and why only one of them runs.** The plan
nominates `DriftCostProbe.errMm` as the signal that widens the radius when the
pose is known to be drifting. That was checked against the code rather than
assumed, and it does not exist on the shipping path: `errMm` is
`EvalMetrics.poseError(candidate, truth)` and returns its own -1 sentinel
whenever `truthPose` is null, which is every run where the artist is not holding
a fiducial. Left at that, Phase 4's stated risk mitigation would have been dead
code outside an eval session.

So `pixels()` takes a second measurement: the reloc PnP's **mean inlier
reprojection error**, computed on every lock, already in pixels, and published as
`relocReprojPx`. It is the weaker signal — a residual over the points the pose
was *fitted* to understates the error at points it was not, and
`solvePnPRansac`'s 8 px inlier threshold caps what it can report at all — which
is exactly why its gain is 2.0 rather than the neutral 1.0, and why it is a
separate parameter instead of being quietly converted into millimetres and
folded into the first. Both default to not-measured and both contribute nothing
when they are.

**Three asymmetries in `SearchRadius` are deliberate and are not tuning.**
Degenerate geometry (no distance, no focal length, no design extent) returns the
**ceiling**, not the floor: a too-wide search costs precision that the ratio test
still filters, while a too-tight one returns no candidates and reads downstream
as "the wall does not corroborate the design" — a confident wrong answer
indistinguishable from an unpainted wall. A negative reading on **either** drift
input means *not measured* and contributes nothing, rather than being read as a
confident zero. And design anisotropy enters as the geometric mean, not the max:
the radius bounds pose error, which is isotropic in the image whatever the
artwork's aspect ratio.

`kCorrobLoweRatio` at 0.85 is a *prediction*, not a setting. P2 says the local
test admits a larger threshold than the global one; 0.85 is where we expect to
land if P2 holds. If E7's iso-precision contour is flat, this reverts to 0.75 and
P2 is falsified. It is now genuinely separable from `kRelocLoweRatio`, which is
what makes that a measurement rather than an argument.

**Why painting progress needs a confirmation threshold.** Phase 4 made progress
cumulative, because a gated match only ever looks at the part of the design in
frame and an instantaneous ratio would have become a statement about where the
artist is standing. But a counter that only ever goes up, with no false-positive
bound, saturates on noise given enough ticks: the reloc loop runs at 5 Hz locked,
so one spurious match per attempt walks a 1500-feature design to "100% painted"
in minutes on a bare wall. `kCorrobConfirmations` requires a design feature to be
corroborated on two *separate* gated attempts before it counts. Two is the
smallest value that does anything — one is the unbounded case — and it costs a
genuinely painted feature nothing, since it corroborates on every attempt it is
in view for. It does **not** defend against a *persistent* false positive; that
is a limit of descriptor corroboration, not of this constant, and it is why E6
measures progress against ground truth rather than trusting it.

Two related decisions are recorded here because they are not obvious from the
code. Accumulation runs on the **gated path only** — the global fallback is an
unconstrained descriptor match with no geometric agreement behind it, and a
never-decaying signal must not be fed from one. And both progress and confidence
switch definition on whether a design **placement** exists, not on whether this
particular frame produced a lock: a placement is stable across frames while a
lock is not, so keying on the lock would alternate two different measurements in
one readout every time the artist looked away and back.

**The lone-candidate skip rate is published, not logged.** The gated match
refuses to test a predicted feature whose neighbourhood holds a single candidate,
because Lowe's ratio has nothing to divide by. Those skips deflate `matched`
without touching `predicted`, so a radius too tight to find two candidates
produces the same signature as a wall that has stopped corroborating — and the
two call for opposite responses. At the `MIN_PX` floor of 4 px a sparse frame can
skip most of what it predicted. `corrobLoneSkips` therefore rides the diagnostics
channel, the overlay and the eval CSV, so E6 sets ρ against a measurement instead
of an assumption. Note the deflation is not harmless in the meantime: lower
confidence means a smaller correction blend in `PoseFusion`, so a too-tight radius
trades false snapping for accumulated drift.

**What the transliteration test does not cover.** Every assertion in
`SearchRadiusTest` and `KeypointGridTest` exercises Kotlin that never runs on a
device. `CorroborationTranslitTest` pins the constants and the handful of
structural choices where a plausible C++ rewrite silently changes behaviour — the
unclamped out-of-range test, `floor` over truncation, the cell *range* sweep, the
reprojection term not being scaled by `pxPerMeter`. It cannot prove the two
compute the same function; there is no native test harness in this project, and
adding one is a larger change than the phase it would serve. This is a known gap,
recorded rather than papered over: a native smoke assertion against the same
fixtures remains the right fix and is not done.

---

## 6. Rendering and interaction — set, not swept

Three of these gate the Phase-2 repartition, which costs a classify pass, a
native fingerprint replace and a project write each time it fires. They are
guesses with stated reasoning, not measurements, and no experiment sets them —
if repartition cost or missed repartitions ever show up in a run, start here.

| Parameter | Location | Current | Basis |
|---|---|---|---|
| `DEFAULT_EXTENT_EPS_M` | `DesignMoveDetector.kt` | `1e-3` m | guessed — a millimetre of effective half-extent; below this a "resize" is float noise in the compose chain |
| `DEFAULT_PAN_EPS_M` | `DesignMoveDetector.kt` | `1e-3` m | guessed — likewise for in-plane pan |
| `DEFAULT_ROT_EPS_DEG` | `DesignMoveDetector.kt` | `0.1`° | guessed — a tenth of a degree of in-plane spin |
| `ANCHOR_WAIT_MS` | `SlamManager.kt` | `2000` ms | derived — the anchor is established on the GL frame after confirm (under 35 ms at 30 fps); this is a stall ceiling, not an expected duration, and timing out REFUSES rather than falling back |

**The repartition trigger's *thresholded* inputs are the artist's gestures only**,
and that is a correctness requirement rather than a tuning choice. (It also reads
the anchor generation, which is discrete and compared exactly — see below.) `anchorMatrix` is the
*fused* pose, re-corrected every frame, and `anchorMatrix⁻¹ · overlayRigid`
carries `R_anchorᵀ` — so any threshold on that product fires on drift with the
phone sitting still. Two successive cuts got this wrong: the first keyed on the
composed matrix (~0.057° at `1e-3` per element), the second added the
marks-centering offset, which is `X_world · (R_anchor · markCenterLocal)` and is
drift-coupled at `DEFAULT_PAN_EPS_M / |markCenterLocal|` — the same 0.057° at a
one-metre lever arm.

`overlayPanX/Y` and `overlayRotationDeg` are gestures, so they are immune. The
extents are `extentHalfW * overlayScale`, and only the scale is a gesture —
`extentHalfW` comes from a screen fit. They are safe for a different reason,
worth stating precisely in a parameter set whose history is a drift-immunity
argument being wrong twice: the fit is **one-shot per arming**, so the extents
are a step function rather than a per-frame signal.

Removing the marks offset also removed the only input that moved when a
**re-capture** replaced the anchor, which silently stopped the footprint being
republished on exactly the path that most needs it. The anchor generation — a
discrete counter, compared exactly with no threshold — carries that signal now.

**The thresholds compare against the last PUBLISH, not the last frame.** Advancing
the remembered values on a frame that reported no movement makes a slow drag
invisible: 15 cm over 3 s at 60 fps is 0.83 mm per frame, under the 1 mm
threshold on every frame, and the delta never accumulates. That cut shipped too, and it is the more dangerous
failure of the two — a trigger that fires too often wastes work, one that never
fires leaves Φ answering against a design that has left.



Listed for completeness. These are UX constants with no bearing on
relocalization accuracy; they are here so a future session does not mistake them
for tuning targets.

| Parameter | Location | Current | Basis |
|---|---|---|---|
| `HOLD_MS` | `PlaneRenderer.kt:367` | `5000` ms | user-specified |
| `DISSOLVE_MS` | `PlaneRenderer.kt:370` | `10000` ms | user-specified |
| `NORMAL_DOT_EPS` | `PlaneRenderer.kt:389` | `0.97` | guessed (~14° normal tolerance) |
| `COPLANAR_DIST_M` | `PlaneRenderer.kt:396` | `0.10` m | guessed |
| `PLANE_PICK_MIN_DOT` | `ArRenderer.kt:2028` | `0.4` | guessed |
| `PLANE_PICK_DOT_TIE` | `ArRenderer.kt:2031` | `0.05` | guessed |
| `DOODLE_MIN_RELOC_INLIERS` | `ArRenderer.kt:2018` | `20` | guessed |
| `PERCEPTION_LAG_MS` | `ArRenderer.kt:2036` | `16` ms | derived (one frame at 60 Hz) |
| `IDLE_ENTER_DEBOUNCE_MS` | `ArRenderer.kt:2045` | `700` ms | guessed |
| `RESUME_HOLD_MS` | `ArRenderer.kt:2048` | `500` ms | guessed |
| `MIN_UV_SPAN` | `BackgroundRenderer.kt` | `0.1` | derived — the sanity floor that catches the collapsed-texcoord bug from PR #1785 |
| `FEW_FEATURES_IN_FRAME` | `MainActivity.kt` | `100` | guessed — the overlay's "not much texture here" threshold |
| Reloc throttle (anchored) | `ArRenderer.kt` | every `10` frames | guessed |
| Reloc throttle (searching) | `ArRenderer.kt` | every `3` frames | guessed |

The two throttle rates *do* affect relocalization — they set how often the reloc
thread is offered a frame — and they are in E9's cost budget even though they are
not accuracy parameters.

---

## 7. Evaluation thresholds

| Parameter | Location | Current | Basis |
|---|---|---|---|
| `GOOD_ERR_MM` | `EvalDecision.kt` | `10` mm | measured-ish — "≤1 cm is effective at mural scale", a judgement about the application |
| Jitter window | `DriftCostProbe.kt` | `30` samples | guessed (~0.5 s at 60 Hz) |
| Repetitions per condition | `EVALUATION.md` §3.3 | `3` | derived — median of three rejects a thermally-throttled run |
| RNG seed | *proposed*, eval-only | fixed constant | derived — determinism requirement |

---

## 8. Maintenance

Add a row here **in the same commit** that introduces a constant, not in a
cleanup pass afterwards — that is item X.1 in `IMPLEMENTATION.md`'s todo list.
A constant with no row is a constant nobody can justify six months from now, and
this table's only real job is to prevent the next person from having to re-derive
what "0.75" was doing there.

When a sweep sets a value, replace the Basis tag with **measured** and cite the
experiment and its run identity (the sidecar JSON hash from `EVALUATION.md` §3.2).
A value that says "measured" without a citation is a guess wearing a better tag.
