# Native Engine (MobileGS) Documentation

## Overview
`MobileGS` (`core/nativebridge/src/main/cpp/MobileGS.cpp` / `include/MobileGS.h`) is a C++17 relocalizer: it fingerprints a wall the artist has marked, then matches that fingerprint against the live camera feed to snap the mural's anchor back into place after tracking loss or a pocketed phone. It is **not** a mapping engine — there is no persistent-voxel or splat layer, no scene reconstruction, and no `draw()`. Earlier drafts of this document described one; that layer was deleted from the codebase, and `MobileGS.cpp`'s own code says so at the two call sites that used to feed it (`setMappingPaused`, `getSplatCount` — both log "no gaussian-splat mapper in this engine" and return inert values).

## Key Components

### 1. The wall fingerprint
A fingerprint is ORB (`cv::ORB::create(1500)`) or SuperPoint descriptors paired with the triangulated 3D positions of the marks the artist drew, captured once when the target is confirmed (`restoreWallFingerprintMetric`). It optionally carries a per-point region tag (`Footprint::Region` — INSIDE/BAND/OUTSIDE) so relocalization can exclude points that sit under the artwork itself and will be painted over.

### 2. Relocalization ("snap-back")
A background thread (`relocThreadFunc`) continuously matches the live camera frame's descriptors against the stored fingerprint (Lowe ratio test, `kRelocLoweRatio = 0.75`) and solves the resulting 2D↔3D correspondences with `cv::solvePnPRansac` (100 iterations, 8px reprojection threshold, 0.99 confidence). A big lock (`kBigLockInliers = 20` inliers) is accepted outright; smaller locks go through additional consistency checks before the global anchor transform is corrected. This — not a mapping/rendering layer — is the actual mechanism behind the "Pocket-Ready" pitch: it needs no persistent map, just the one fingerprint.

### 3. Teleological corroboration and self-grow (both off by default)
Two optional, separately-gated mechanisms sit on top of relocalization:
- **Corroboration**: compares descriptors from the design composite against the live wall to produce a confidence signal (Lowe ratio `kCorrobLoweRatio = 0.85`) — see `docs/TELEOLOGICAL_SLAM.md` for what it does and does not measure.
- **Self-grow** (`mSelfGrowEnabled`, default **off**, `setSelfGrowEnabled`): lets validated new marks extend the fingerprint as the artist paints, so relocalization can survive the original reference being covered. Capped at `kMaxWallMarks = 5000` points.

Both are reachable only from the diagnostic overlay (Settings > diagnostic overlay), not the artist-facing UI — see that doc for why, and for the open questions around self-grow's promotion gate.

### 4. Optional persistent wall feature map
`WallFeatureMap` (`setMapRelocEnabled`/`setMapBuildEnabled`, `IMPLEMENTATION.md` phases 2b/3) is a second, longer-lived relocalization source that can be built and matched against across sessions. Off by default like the two mechanisms above.

## Memory Management
- The fingerprint itself is small (a few thousand descriptor+point pairs at most); there is no fixed-size point-cloud or voxel budget to speak of beyond `kMaxWallMarks`.

## JNI Interface (`GraffitiJNI.cpp` → `SlamManager.kt`)

A representative subset of the real, currently-exported surface (see `SlamManager.kt` for the complete list):

| Kotlin method | Description |
|---|---|
| `restoreWallFingerprintMetric(...)` | Ingests a captured fingerprint (descriptors + 3D points + capture pose/intrinsics). |
| `updateAnchorTransform(mat)` | Relocalization correction — snaps the global mural alignment. |
| `getRelocDiagnostics()` | Structured relocalization state (inliers, reprojection error, reject reason). |
| `getCorroborationConfidence()` | Teleological corroboration's confidence signal (see `TELEOLOGICAL_SLAM.md`). |
| `setSelfGrowEnabled(enabled)` | Toggles self-grow (default off). |
| `feedYuvFrame(...)` | Per-frame camera feed into the reloc thread. |
| `clearWallFingerprint()` | Drops the current fingerprint (new project / re-target). |

---
*Rewritten 2026-09-04 to describe the engine actually in the tree — the previous "Persistent Voxel Memory" description (spatial hash, splat struct, `MAX_SPLATS`, `feedArCoreDepth`, `saveModel`, `draw()`) had no corresponding code anywhere in `core/nativebridge`.*
