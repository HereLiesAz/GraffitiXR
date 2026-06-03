# Distortion Head — viewpoint/partial-aware relocalization

**Status:** spec + offline training pipeline (no device wiring yet).
**Owns:** the "does the relocalizer *know* it's seeing the target obliquely / up-close / partially?" problem.
**Slots into:** the relocalization-first build (replaces the "IPPE + flip-resolution +
distance/FOV-adaptive thresholds" sub-item with a learned version that *also* emits a
painting-progress signal).

---

## 0. Architecture context — A + B + D

Three chosen, composing components for learned, viewpoint-aware, *self-growing* relocalization.
(Path **C**, a from-scratch registration net, was explicitly rejected — reuse proven components.)

- **A — LightGlue matcher.** Replace brute-force `knnMatch` + Lowe ratio with SuperPoint +
  **LightGlue** (pretrained, ONNX): attention-based correspondences with per-match confidence,
  robust to viewpoint. The *precise* geometry (`findHomography`/IPPE on its matches) comes from here.
- **B — distortion head (this doc).** A cheap, always-on head emitting the planar distortion,
  matchability, and **coverage**. With A present it is *not* the primary geometry source; its unique
  value is (i) **coverage → painting progress**, (ii) a fast pre-gate, and (iii) a **viewpoint
  prior** to pre-rectify the patch so LightGlue survives extreme tilt. A and B overlap on
  *matchability* — B's is the cheap filter, A's is the precise one.
- **D — self-growing fingerprint (`SELF_GROWING_FINGERPRINT.md`).** Each *verified* relock contributes a
  new viewpoint to a multi-view fingerprint bank; the teleological reference validates and promotes
  new painted marks. B + A are what produce and gate the verified data D grows from.

**Division of labor:** head (B) gates + viewpoint prior + progress → LightGlue (A) precise
correspondences → `findHomography`/IPPE geometry → PnP verifies → verified triple feeds D.

**Dependency order:** B (offline train/validate) → A (LightGlue + ONNX Runtime) → D (needs B on
device emitting verified triples + the reference validator).

**Runtime:** A is the forcing function for **ONNX Runtime** — cv::dnn cannot import LightGlue's
attention ops. When A lands, migrate all NN inference (SuperPoint + enhancer + this head) to ORT
together; OpenCV keeps the classical CV (ORB fallback, PnP, homography, warp). Standard opset-12
exports load in both runtimes, so B trains runtime-agnostic today.

---

## 1. Why

The current relocalizer (`MobileGS::relocThreadFunc`) is viewpoint-blind:

1. SuperPoint (or ORB) detects features on the current frame.
2. `knnMatch` + Lowe ratio (fixed `0.75`) against **one** stored descriptor set.
3. `solvePnPRansac` (generic iterative), fixed gates: `≥15` matches, `≥12` inliers, `8px` reproj.

Nothing measures *how* the current view is distorted relative to the captured fingerprint, and
nothing reports it back. Partials ("I see half the mark") and distortions ("I see the whole mark
but sheared 50°") are indistinguishable to the system — both just fall below threshold and the
attempt is silently dropped.

The fingerprint lives on a **wall = a plane**, so the relationship between the capture view and any
current view is *exactly* a homography `H`. The distortion head learns to estimate that `H` (plus a
confidence and a coverage fraction) from SuperPoint features, giving the system an explicit,
numeric perception of the viewpoint — which it can then *act on*.

> **Decision (recorded):** keep the already-validated SuperPoint, **freeze it**, and bolt on a
> small trainable head — additive, cannot regress the current matcher, trains in hours on one GPU.
> This head is component **B** of three chosen for learned relocalization; see §0 for how it composes
> with the LightGlue matcher (**A**) and the self-growing fingerprint (**D**).

---

## 2. The contract (recognize + inform)

One forward pass of the head, two views in, four things out:

| Output | Shape | Meaning | Consumed by |
|---|---|---|---|
| `corners` | 8 | 4-corner offsets of `H` (normalized by patch size; order TL,TR,BR,BL as x,y) — **the distortion** | guided matching + IPPE/PnP seed |
| `pose` | 3 | `[tilt°, log2(scale), roll°]` decoded — interpretable distortion | distance/FOV-adaptive thresholds |
| `matchability` | 1 | "is this the target, at this overlap?" (sigmoid) | cheap pre-reject + PnP-inlier fusion confidence |
| `coverage` | 1 | fraction of the target visible within its estimated footprint (sigmoid) | **`mPaintingProgress`** (teleological) |

Native-facing export packs these into one tensor `distortion[13]`:
`[0:8]=corners, [8:11]=pose, [11]=matchability(prob), [12]=coverage`.

**The synthesis that makes this fit GraffitiXR:** `coverage` *is* the painting-progress signal. The
same head that says "partial + 50° shear, but confident" is simultaneously the relock confidence
*and* the teleological progress — one model, one head, not two systems.

---

## 3. Where it attaches

SuperPoint already produces a dense, L2-normalized descriptor map `desc [1,256,H/8,W/8]`
(`scripts/convert_superpoint.py`). The head is **two-view** and consumes that map for both inputs:

- `desc_cur` — features of a crop of the current frame, centered on the coarse match centroid (we
  already compute candidate matches in `relocThreadFunc`), resized to a canonical `256×256`
  (→ `32×32×256`).
- `desc_fp` — features of the stored canonical fingerprint patch, same `256×256` (→ `32×32×256`).

SuperPoint stays **frozen**; only the head trains. The existing keypoint/descriptor/PnP path is
byte-identical — the head is purely additive.

### v1 architecture (HomographyNet-style on SuperPoint features)

Operating on a coarse-localized, roughly-aligned pair keeps this trainable (the head refines a warp
between two near-aligned views rather than localizing from scratch):

```
stack([desc_cur, desc_fp])            # [B, 512, 32, 32]
 → conv 512→256, relu, maxpool        # 16×16
 → conv 256→256, relu, maxpool        #  8×8
 → conv 256→128, relu, maxpool        #  4×4
 → flatten (2048) → fc 512, relu, dropout
 → heads: corners(8) | pose(3) | matchability(1) | coverage(1)
```

Plain conv/relu/maxpool/gemm only → exports cleanly to opset-12 ONNX for **OpenCV DNN**. <2 MB,
a few ms inference (the 200 ms reloc cadence is a generous budget).

> If we later want H-warp **rectification** inside the graph (`grid_sample`), that requires moving
> SuperPoint+head to ONNX Runtime. v1 deliberately avoids it: rectification is done in OpenCV on the
> native side using the regressed `corners`.

---

## 4. Training — zero mural data, frozen backbone

Self-supervised **homographic adaptation**; the head only ever sees frozen SuperPoint features.

- **Corpus:** a few thousand generic wall/texture/mural images. Domain barely matters — the backbone
  is frozen and the runtime target is unseen at training time anyway. (Pipeline includes a
  procedural-texture fallback so it runs end-to-end with no corpus, for smoke tests.)
- **Per sample:** take a canonical patch; synthesize a warped view via a plane-induced homography
  with **tilt ≤ 70°, scale 0.25–4×, full in-plane rotation**, plus translation jitter (mimics
  imperfect coarse localization), photometric aug (low-light, contrast, noise), and **random
  occlusion masks** (paint-over / occlusion → drives `coverage`).
- **Labels (free, from the synthetic `H`):**
  - `corners` ← the known corner offsets → smooth-L1 (masked to positives).
  - `pose` ← the construction parameters → smooth-L1 (masked to positives).
  - `coverage` ← visible fraction after occlusion → L1 (masked to positives).
  - `matchability` ← BCE; **positives** = warped-self, **negatives** = a *different* image's crop
    and/or beyond-range warps. Negatives are critical — without them the head produces
    false-positive snaps.

---

## 5. Integration into `relocThreadFunc` (later stage — not in this scaffold)

Insert between SuperPoint detection (`MobileGS.cpp:299`) and PnP (`:306+`):

1. `head(desc_cur, desc_fp)` → `corners, pose, matchability, coverage`.
2. **matchability low → `continue`** (kills wasted-PnP on non-matches).
3. **Use the warp to guide, never to decide:** rectify current keypoints toward the canonical frame
   so descriptor matching survives the tilt, and seed PnP (`SOLVEPNP_IPPE` with the planar
   correspondences, or `useExtrinsicGuess=true` from the `corners`-derived pose). Geometry stays the
   source of truth — a regressed `H` never writes the pose directly; PnP inliers verify it.
4. **`pose` → adaptive thresholds** (replace fixed `0.75` / `12` / `8px`).
5. **`coverage` → `mPaintingProgress`.**

### Why this is load-bearing, not decoration

With ML depth off, `generateFingerprint` returns **descriptors only, no `points3d`**
(`MobileGS.cpp:540–545`), so `solvePnPRansac` has no object points and **cannot run** — depth-off
currently means no PnP relock at all. The head's `corners`/`H` enable **2D-only planar relock**
(decompose `H` with intrinsics → pose, no per-point depth). Until the triangulation core restores
metric 3D, H-based planar relock from this head is the bridge that makes relock work with depth off;
once triangulation lands, the head's `H` becomes the prior that makes IPPE converge instead of the
only path.

---

## 6. Prerequisites / schema changes (device side, later)

- `Fingerprint` (`core/common/.../model/Fingerprint.kt`) must carry the **canonical patch** (small
  grayscale crop, already produced by `isolateMarkings`) so its features can be recomputed on load.
  Touches the model, `FingerprintSerializer`, and the JNI ctor keep-rule (`GraffitiJNI.cpp:541`).
- Real PnP intrinsics — the hardcoded `intr` at `MobileGS.cpp:322` must be fed real ARCore
  intrinsics, else "wrong calibration" and "distortion" are indistinguishable to the solver. This is
  a prerequisite for adaptive thresholds, not a deferred cleanup.

---

## 7. Offline pipeline (this scaffold)

See `scripts/distortion_head/`:

| File | Purpose |
|---|---|
| `superpoint_backbone.py` | loads frozen SuperPoint (reuses `convert_superpoint.py`), exposes `desc` extraction |
| `data.py` | synthetic-homography dataset (plane-induced `H`, occlusion, photometric aug, labels) |
| `model.py` | `DistortionHead` + export wrapper |
| `train.py` | training loop, masked multi-task loss, checkpointing |
| `export_onnx.py` | trained head → opset-12 ONNX (OpenCV-DNN compatible) |
| `README.md` | how to run |

**Validate offline first** (per the build-a-stage → test rhythm): corner-error (px), tilt/scale MAE,
and matchability AUC on held-out synthetic warps — before any Android wiring.
</invoke>
