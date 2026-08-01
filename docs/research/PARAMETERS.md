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
| `CONF_FLOOR` | `0.5` | guessed, but with a stated rationale: at 0.5 a fully corroborated wall pulls exactly twice as hard as a bare one | **sweep** | E4 → **E11**; re-derived against the new denominator in Phase 5b |
| `COLD_SNAP_INLIER_RATIO` | `0.7` | guessed | screen | E4 → E11 |
| `COLD_SNAP_MIN_INLIERS` | `20` | guessed | screen | E4 → E11 |
| `COLD_SNAP_DIST_M` | `0.20` m | guessed | screen | E4 → E11 |
| `COLD_SNAP_ANGLE_DEG` | `15°` | guessed | screen | E4 → E11 |

`CONF_FLOOR` changes meaning under Phase 5b — its input becomes
`corroborationConfidence` with a predicted-visible denominator instead of
`paintingProgress` over all design features. The current value's rationale does
not survive that change intact, so it must be re-derived (todo 5b.2) rather than
assumed to transfer. E3 tests that the *contract* still holds; **E11** sets the
number.

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

## 5. Spatially-constrained corroboration — proposed

From Phase 4. These two are the most important numbers in the plan.

| Parameter | Prior | Reasoning for the prior | Set by |
|---|---|---|---|
| `CORROB_SEARCH_RHO` | `0.05` | The paper's §5.5 worked example: at ρ=0.05 the candidate set is ~0.2% of the design | E6, refined by E7 |
| `CORROB_LOWE_RATIO` | `0.85` | Looser than the reloc path's 0.75, which is the entire point — a smaller candidate set should admit a looser threshold at the same precision | E7 |
| `SEARCH_RADIUS_MIN_PX` | `4` | Below a few pixels the radius is inside the keypoint localization noise | **E11** |
| `SEARCH_RADIUS_MAX_PX` | `120` | Above this the "local" search is not local and the argument collapses | **E11** |
| `SEARCH_RADIUS_ERR_GAIN` | `1.0` | Radius scales linearly with measured `errMm`; gain 1.0 is the neutral prior | **E11** |

`CORROB_LOWE_RATIO` at 0.85 is a *prediction*, not a setting. P2 says the local
test admits a larger threshold than the global one; 0.85 is where we expect to
land if P2 holds. If E7's iso-precision contour is flat, this reverts to 0.75 and
P2 is falsified.

---

## 6. Rendering and interaction — set, not swept

Three of these gate the Phase-2 repartition, which costs a classify pass, a
native fingerprint replace and a project write each time it fires. They are
guesses with stated reasoning, not measurements, and no experiment sets them —
if repartition cost or missed repartitions ever show up in a run, start here.

| Parameter | Location | Current | Basis |
|---|---|---|---|
| `DESIGN_EXTENT_EPS` | `ArRenderer.kt` | `1e-3` m | guessed — a millimetre of effective half-extent; below this a "resize" is float noise in the compose chain |
| `DESIGN_PAN_EPS_M` | `ArRenderer.kt` | `1e-3` m | guessed — likewise for in-plane pan |
| `DESIGN_ROT_EPS_DEG` | `ArRenderer.kt` | `0.1`° | guessed — a tenth of a degree of in-plane spin |
| `ANCHOR_WAIT_MS` | `SlamManager.kt` | `2000` ms | derived — the anchor is established on the GL frame after confirm (under 35 ms at 30 fps); this is a stall ceiling, not an expected duration, and timing out REFUSES rather than falling back |

**The repartition trigger reads the artist's gesture inputs only**, and that is a
correctness requirement rather than a tuning choice. `anchorMatrix` is the
*fused* pose, re-corrected every frame, and `anchorMatrix⁻¹ · overlayRigid`
carries `R_anchorᵀ` — so any threshold on that product fires on drift with the
phone sitting still. Two successive cuts got this wrong: the first keyed on the
composed matrix (~0.057° at `1e-3` per element), the second added the
marks-centering offset, which is `X_world · (R_anchor · markCenterLocal)` and is
drift-coupled at `DESIGN_PAN_EPS_M / |markCenterLocal|` — the same 0.057° at a
one-metre lever arm. Only `overlayPanX/Y`, `overlayRotationDeg` and the extents
are genuinely immune, because they are set from gestures and nothing else.

**The thresholds compare against the last PUBLISH, not the last frame.** Advancing
the remembered values on a frame that reported no movement makes a slow drag
invisible: 15 cm over 3 s is 0.08 mm per frame, under threshold every frame, and
the delta never accumulates. That cut shipped too, and it is the more dangerous
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
