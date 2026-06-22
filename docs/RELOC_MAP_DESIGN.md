# Relocalization Robustness — Persistent Spatial Map (Design for Review)

> Status: **proposal for your reconsideration**. Nothing built. This lays out the
> honest options + trade-offs so you can choose the direction before I touch the
> relocalizer (the crown jewel).

## 1. The goal (your concern, restated)
Relocalization that is **thorough and smooth over a whole mural** — locks back on from
many viewpoints, survives tracking loss / pocket / repaint. Your intuition: the marks
**fingerprint alone isn't enough**, and we should persist a **point cloud with a
confidence system** to back it up.

You're right about the limitation. This doc is about *what* to persist and *how it
feeds reloc*, because that detail decides everything.

## 2. What exists today (after the right-size)
| Thing | State | Feeds reloc? |
|------|-------|--------------|
| **Marks fingerprint** (descriptors + 3D points + PnP) | active — *the* relocalizer | ✅ yes |
| **ARCore point cloud** `(x,y,z,confidence)`, persisted to `cloudPointsPath` | **intact** (never removed) | ❌ no — only *drawn* |
| ARCore VIO pose (live tracking between relocks) | active | indirectly |
| Voxel/splat/mesh dense map | **deleted** | ❌ never did |

**Key fact:** the point cloud is *already persisted with per-point confidence* — that
part of your instinct is in place. But those points are **geometric only (no visual
descriptors)**, so they **cannot relocalize**. Reloc works by matching *what the camera
sees* (feature descriptors) → known 3D points → solve pose (PnP). Bare xyz+confidence
can't do that; it can only do ICP-style geometric alignment, which needs a good pose
prior and is fragile.

So: the marks fingerprint locks **precisely but only when the marks are in frame**. Look
away to a far corner and there's nothing to lock onto. That's the gap.

## 3. Options

### Option A — Persistent confidence-weighted **feature** map  ⭐ recommended
A purposeful **scan** accumulates **SuperPoint/ORB descriptors at triangulated 3D
points** across the wall, each with a **confidence** (observation count + reprojection
consistency); low-confidence points get pruned. Persist it in the `.gxr`. `relocThreadFunc`
matches the live frame against the **whole map** → wide-area reloc.
- **Pros:** genuinely robust/smooth wide-area reloc; *is* a confidence system; keeps a
  *purposeful* scan; **subsumes B2** (multi-region is just a coarse version of this).
- **Cons:** biggest build; touches the relocalizer; descriptor memory (~32–256 B/point);
  needs tuning.
- Effort **High** · Risk **High**.

### Option B — Geometric point-cloud assist (ICP)
Reuse the existing `(x,y,z,conf)` cloud: align live ARCore points to the stored cloud for
a pose correction, gated by confidence.
- **Pros:** reuses what's already persisted; no descriptor storage; lighter.
- **Cons:** ICP needs a decent pose prior — **won't recover from full tracking loss alone**;
  sensitive to ARCore point noise/drift/density; materially less robust than feature matching.
- Effort **Medium** · Risk **Medium**.

### Option C — Multi-region fingerprints (B2 as first scoped)
Capture several discrete mark-fingerprints across the wall; reloc tries each.
- **Pros:** smaller; reuses fingerprint machinery; user-controlled.
- **Cons:** only covers *where you placed marks*; gaps between regions; manual capture.
- Effort **Medium** · Risk **Medium**.

### Option D — Hybrid: marks fingerprint (precision) + feature map (coverage)
Keep the marks fingerprint as the high-precision anchor; add Option A for coverage; fuse
(fingerprint wins when visible, map fills the gaps).
- **Best quality, most work.** In practice A should be built *to keep* the marks
  fingerprint, so A naturally becomes D.

## 4. Recommendation
**Option A, built to keep the marks fingerprint as the precision anchor (→ effectively D).**
Confidence persistence + a purposeful scan + wide-area reloc — exactly your intuition,
delivered leanly (sparse features, *not* the dense splat/mesh we removed).

This is **not a reversal** of the right-size: we deleted the dense map that carried no
descriptors and never fed reloc (dead weight). This adds the **lean feature map that
actually serves reloc**. The cleanup cleared the deck; this builds the correct thing on it.

## 5. Architecture sketch (Option A)
- **Data:** `WallFeatureMap { points3d[N], descriptors[N×D], confidence[N], obsCount[N] }`,
  in a fingerprint-relative frame with a fixed anchor + intrinsics (like the fingerprint).
- **Build (during scan):** per keyframe → SuperPoint detect → triangulate via VIO
  baseline (or depth when available) → associate to existing map points (descriptor +
  reprojection); bump `confidence`/`obsCount`, or add new. Prune `confidence < τ` or long-unobserved.
- **Persist:** extend `.gxr` (new `featuremap.bin`), parallel to the fingerprint.
- **Native:** `mWallMap` (cv::Mat descriptors + `vector<Point3f>` + conf); cap size (≈5–20k
  points) by confidence. `relocThreadFunc` merges map correspondences into the PnP set (or
  runs map-PnP + fingerprint-PnP and takes the higher inlier count).
- **Scan UX:** a sweep that shows **coverage genuinely growing** (can reuse the sector mask —
  now tied to *real* map growth, replacing the dead `splatCount >= 30000` gate).
- **Self-grow (B1):** extends naturally — promote validated live features into the map.

## 6. Sequencing (each phase device-gated, behind an A/B flag)
1. Data model + `.gxr` persistence + **round-trip test** (no reloc risk).
2. Native map storage + reloc matching **behind a flag** (compare vs fingerprint-only).
3. Scan build + coverage UX (replaces the vestigial AMBIENT/WALL phases).
4. Confidence/pruning + matcher tuning **on device**.

## 7. Open questions for you
1. **Scan effort:** how much sweeping is acceptable before painting? (sets the density target)
2. **Budget:** memory/storage ceiling for the map? (descriptors dominate)
3. **Keep the marks fingerprint** as the precision anchor alongside the map? (recommend **yes**)
4. **SuperPoint asset**: ship the `.onnx` so the map uses learned descriptors (best), or ORB?
5. Is **Option A** the right ambition, or do you want to start with **C** (multi-region) as a
   stepping stone and grow into A later?

---
*If A/D looks right, I'll turn §5–6 into a phased implementation plan and start with Phase 1
(data model + persistence + tests) — zero relocalizer risk — for your review before going near
`relocThreadFunc`.*
