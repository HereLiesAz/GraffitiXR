# Teleological SLAM — research and implementation dossier

Four documents, written together, covering the redesign of the wall-relocalization
mechanism that keeps a projected mural locked to a wall that is being repainted.

| Document | What it is for |
|---|---|
| [`PAPER.md`](PAPER.md) | The science. Formal statement of the problem, the approach as built, why it could not work, the proposed approach, and falsifiable predictions. |
| [`IMPLEMENTATION.md`](IMPLEMENTATION.md) | The engineering. Phase-by-phase wiring plan with file paths, signatures, data flow, exit criteria, risk and rollback. |
| [`EVALUATION.md`](EVALUATION.md) | The testing. How to obtain ground truth, how to make runs repeatable, which constants to tune, and the reasoning behind each experiment. |
| [`PARAMETERS.md`](PARAMETERS.md) | The reference table. Every tunable constant, where it lives, its current value, its prior, and the experiment that sets it. |

## The auditor found things

Glee's first pass audited these documents against the code and found real defects
in both. The corrections are folded in; recording them here because the pattern
matters more than the individual fixes.

**In the code** — two shipping bugs the documents had asserted were fine:
`mSelfGrowEnabled` defaulted to `true`, so the only mechanism that permanently
mutates the reloc map ran unsupervised in every release build while five separate
places said it defaulted off; and `clearWallFingerprint()` never cleared the
artwork validator, so a project switch left the previous project's target driving
this project's painting progress and correction strength.

**In these documents** — the plan prescribed `PoseMath.rigidInverse` for a matrix
that carries the user's overlay scale (which would have inverted the partition it
exists to build), ordered phases against its own dependency graph, routed a third
of `PARAMETERS.md` to experiments whose own "Sets" line says they set nothing, and
left two of six predictions with no experiment.

None of that was found by writing more carefully. It was found by an agent whose
job was to want it to be wrong.

## The auditor

`.claude/agents/glee.md` defines **Glee**, an adversarial auditor whose sole
purpose is to take glee in Claude's failures. It audits everything these
documents claim, not just the code — every "verified", every number with no
provenance, every gap between what was asked for and what was delivered. Run it
before trusting anything in this dossier.

## Reading order

Read `PAPER.md` first — the implementation and evaluation plans both assume its
vocabulary (the *footprint operator*, the *backbone set* and the *corroboration
set*). `PARAMETERS.md` is a lookup table, not prose.

## Status

The defects `IMPLEMENTATION.md` lists as "already fixed" were repaired in PRs
#1785–#1788 and are on `main`. Since then **Phase 1** (the footprint operator Φ,
`anchor/Footprint.kt`) and **Phase 5a** (splitting painting progress from
corroboration confidence) have landed, and **Phase 6a is three of its four todos
in** — 6a.4 (the eval-only fixed RNG seed and synchronous-reloc mode) is still
open, so replay A/Bs continue to carry un-quantified RANSAC variance. Phases 2,
3, 4 and 5b are still proposed.

**Phase 0 is next in the landing order and blocks all four.** It is gated on
experiment **E0b**, which has not been run — it needs a physical ARCore device.
What has been settled without one:

- The source chain is confirmed. Capture rotates the bitmap
  (`ArViewModel.onTargetCaptured`) and the intrinsics (`ArRenderer`'s
  `rotationNeeded` branches) into display orientation, and pairs them with a view
  matrix built from `camera.pose.inverse()`, which is not rotated.
- **The assumption §8.2 flagged as load-bearing is closed by ARCore's own
  reference documentation**, in favour of the diagnosis. `Camera.getPose()` is the
  physical/image-readout frame; `getDisplayOrientedPose()` is the display one; the
  docs state they differ "by a local rotation about the Z axis by a multiple of
  90°". A negative E0b can no longer be explained by that assumption failing.
- `PAPER.md` §8.1's error table reproduces exactly on recomputation, once two
  unstated sampling conventions are supplied — both are now recorded in §8.1,
  along with the limit of that check: it confirms the sampling and statistics,
  not the geometry, because it evaluates `backProject`'s own expression.
- E0b's independent variable is now logged per CSV row, as
  `captureRotationNeededDeg`. It previously was not logged at all. Note it is the
  rotation at **capture** — the mismatch is baked into the fingerprint's 3D points
  there and does not change with how the device is held afterwards. A first
  attempt logged the live per-tick rotation, which files a portrait-captured,
  landscape-relocalized run under the control condition and turns a real effect
  into a null result; `liveRotationNeededDeg` is kept only as a secondary signal.

See §8 of the paper, and `../TELEOLOGICAL_SLAM.md` "Open question: the
display-rotation convention".
