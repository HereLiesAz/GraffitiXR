# Teleological SLAM — the self-growing fingerprint (component D)

**Status:** design (unbuilt). Depends on component **B** (`DISTORTION_HEAD.md`) and **A**
(SuperPoint + LightGlue). Fills the "stages 2–4" of the relocalization-first build.

> **Teleological** = goal-directed. We *know* what the finished mural is supposed to look like
> (the reference design), so the target doubles as a **validator**: the further along the painting
> is, the more the wall *becomes* the target, and the more material there is to relocalize against.
> Other tracing apps degrade as you paint (you cover the marks you were tracking); this gets
> *tighter* as you go.

---

## 1. The problem D solves

The fingerprint today is **single-viewpoint and frozen**: one descriptor set + one set of 3D points,
captured once (`MobileGS::generateFingerprint`). A close/oblique view must match descriptors computed
from the original capture, and as paint covers the reference marks the fingerprint goes stale. So
relock gets *harder* over a session, exactly backwards.

D makes the fingerprint **multi-view and self-growing**: it accumulates verified viewpoints and
promotes newly-painted marks (validated against the reference design) into the representation.

---

## 2. Mechanism

### 2.1 Verified-relock harvesting (the gate)

Every relock attempt that clears a **strict** gate produces a growth triple
`(frame_patch, viewpoint H, metric 3D)`. The gate — deliberately conservative to prevent
self-poisoning:

- B's `matchability` high **and** B's `coverage` plausible, **and**
- A (LightGlue) inlier count high, **and**
- `findHomography`/IPPE reprojection error low, **and**
- pose agrees with the VIO-propagated prior (temporal consistency — rejects one-frame flukes).

Only triples passing all four are harvested. Everything else is used for relock but never grows the
fingerprint.

### 2.2 What grows — multi-view bank first, adapter later

- **(a) Non-parametric multi-view bank (build first).** Append the verified view's SuperPoint
  keypoints + descriptors, **tagged with its viewpoint** (decomposed `H`: tilt/roll/scale) and a
  timestamp, to a fingerprint *bank*. Relock then matches against the nearest stored view (by B's
  predicted viewpoint) instead of one canonical view — so a close side-view matches a previously
  stored close side-view rather than fighting the frontal capture. Near-zero risk, no on-device
  backprop. Cap by diversity + LRU (reuse the existing `VoxelHash` LRU / `MAX_SPLATS` discipline);
  **never evict the original capture** (immutable seed anchor).
- **(b) Parametric per-target adapter (only if (a) is insufficient).** A small learned projection on
  descriptors (or a few FiLM params on the head) updated by lightweight on-device gradient steps from
  verified triples, regularized hard toward the frozen prior to bound drift. This is the literal
  "the model grows per target." Heavier, driftier — gate it behind evidence that (a) isn't enough.

### 2.3 Teleological promotion of painted marks (stages 2–4)

As the artist paints, new stable structure appears on the wall. Promote it **only if it's consistent
with the reference design** (the validator):

1. **Detect on a clean frame** — SuperPoint features in regions the design says should now have paint.
2. **Validate vs target** — the new feature's appearance/location must agree with the reference
   image warped by the current relock pose. (Reject scaffolding, shadows, graffiti-over-graffiti
   that isn't *our* design.)
3. **Promote** — add the validated mark to the multi-view bank with metric 3D from two-view
   triangulation (see §3), and **write `mPaintingProgress`** (driven by B's `coverage` + the
   fraction of design area now validated as painted).
4. **Decay** — views/marks that stop matching over time are evicted; the seed capture never is.

---

## 3. Metric grounding (wire the triangulation core)

Promoted marks need 3D to restore PnP when ML depth is off (`generateFingerprint` currently returns
no `points3d` without depth → PnP can't run). The **two-view triangulation core** (built, tested,
unwired) supplies metric 3D for each promoted mark from two verified relock views with known relative
pose. This is the "wire triangulation → metric marks into the fingerprint" item; D is where it
connects.

---

## 4. Anti-poisoning invariants

- Immutable seed: the original capture view is never evicted or overwritten.
- Strict promotion gate (§2.1) — verified-only growth.
- Bounded divergence: adapter (2.2b) regularized to the frozen prior; bank capped by diversity.
- Reference design as validator: nothing enters the fingerprint that the goal doesn't endorse.
- Temporal consistency: single-frame matches never promote.

---

## 5. Dependencies & order

1. **B on device** — coverage + viewpoint prior + matchability gate (`DISTORTION_HEAD.md` §5–6).
2. **A** — LightGlue correspondences + confidence (forces the ONNX Runtime migration).
3. **Triangulation core wired** — metric 3D for promoted marks.
4. **Reference-design validator** — warps the target by current pose to endorse promotions.

Then D: multi-view bank (2.2a) + teleological promotion (2.3) → optional adapter (2.2b).

Build-a-stage → test-on-device, per stage — this is novel SLAM that only validates on the device.
</content>
