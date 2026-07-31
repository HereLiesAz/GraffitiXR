# Teleological SLAM — research and implementation dossier

Four documents, written together, covering the redesign of the wall-relocalization
mechanism that keeps a projected mural locked to a wall that is being repainted.

| Document | What it is for |
|---|---|
| [`PAPER.md`](PAPER.md) | The science. Formal statement of the problem, the approach as built, why it could not work, the proposed approach, and falsifiable predictions. |
| [`IMPLEMENTATION.md`](IMPLEMENTATION.md) | The engineering. Phase-by-phase wiring plan with file paths, signatures, data flow, exit criteria, risk and rollback. |
| [`EVALUATION.md`](EVALUATION.md) | The testing. How to obtain ground truth, how to make runs repeatable, which constants to tune, and the reasoning behind each experiment. |
| [`PARAMETERS.md`](PARAMETERS.md) | The reference table. Every tunable constant, where it lives, its current value, its prior, and the experiment that sets it. |

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

Nothing in `IMPLEMENTATION.md` is built yet. The defects it lists as "already
fixed" were repaired in PRs #1785–#1788 and are on `main`; everything under
Phase 1 onward is proposed.

One prerequisite is unresolved and gates Phase 3 — see
[`../TELEOLOGICAL_SLAM.md`](../TELEOLOGICAL_SLAM.md) "Open question: the
display-rotation convention", and §8 of the paper.
