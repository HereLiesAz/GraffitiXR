# SYSTEM RELATIONSHIPS & USER FLOW

## 1. THE DESIGN IMAGE (The Content Core)
**Definition:** `EditorUiState.design: Layer?` is a single, nullable design image — not a list.
**Persistence Rule:** Changes here are atomic and instant across all modes; the design and its
per-mode placement/adjustments are shared across AR/Overlay/Mockup/Trace/Design.

### A. Structure & Data Relationships
* **Singular by design, not a stack:** there is exactly one design image. It used to be a
  `List<Layer>` with an `activeLayerId` — that cost every consumer a lookup and a null-or-empty
  distinction for a collection that in practice held one element, and has been removed.
  Compositing several images into one design is the companion design app's job, not this app's.
* **`Layer` fields (the surviving single design record):**
    * **`Bitmap`**: the raw pixel data (decoded from the persisted `uri` on load).
    * **`Matrix transform`**: translation, scale, rotation — driven entirely by gesture
      (`onTransformGesture`/`onModeTransformGesture`/`onCycleRotationAxis`), not per-field setters.
      There is no `warpMesh`/Liquify field — that pipeline was removed; no implementing code remains.
    * **`ColorAdjustment` fields**: opacity, brightness, contrast, saturation, colour balance.
    * **Effect flags**: `isInverted`, `isSketch` (outline extraction), `isSubjectIsolated` (MLKit
      subject isolation) — booleans, not a separate blend-mode-stack concept.

### B. Implementation Logic
* **In AR Mode:** the design is rendered as a wall-anchored quad, positioned by the fused
  ARCore-consensus/relocalization pose (see §2).
* **In Overlay Mode:** the design is rendered to a 2D `Canvas` over the live camera feed.
* **In Mockup Mode:** the design is rendered on top of a user-selected static background photo.

---

## 2. RELOCALIZATION (The Spatial Memory)
**Definition:** The system that keeps the design anchored to the physical wall, including recovery
after tracking loss or a screen-off event.
**Components:** `MobileGS` (native C++17 relocalization engine), the wall fingerprint (ORB/SuperPoint
descriptors + a handful of triangulated 3D points — not a dense map), `relocThreadFunc` (background
relocalization thread).

**Correction:** `MobileGS` is **not** a mapping engine — there is no persistent voxel or splat layer,
no scene reconstruction, and no `draw()` method. The engine's own code comments say so at the call
sites that used to feed such a layer (`setMappingPaused`, `getSplatCount` — both log "no
gaussian-splat mapper in this engine" and return inert values). There is no `VoxelMap`,
`ConfidenceMap`, or `PersistentVoxelMemory` in the current code.

### A. The Dependency Chain
1.  **Baseline fingerprint:** when the artist registers the wall, the engine stores ORB/SuperPoint
    descriptors of the clean surface plus triangulated 3D points (from stereo depth when available,
    or the artist's natural step-in/step-back baseline otherwise).
2.  **Relocalization (`relocThreadFunc`):** a background thread continuously matches the live camera
    against that fingerprint and solves the pose via `solvePnPRansac`.
3.  **Snap-back:** on a high-confidence match, the corrected pose can realign the overlay to the wall
    after tracking loss or a screen-off event. This drift-correction consumer, and the related
    self-growing-fingerprint mechanism, ship opt-in and off by default — see
    [`TELEOLOGICAL_SLAM.md`](TELEOLOGICAL_SLAM.md).

---

## 3. TARGET CREATION (The Grid Ritual)
**Definition:** The workflow to establish the initial wall anchor.

### A. The Workflow Logic
1.  **Capture Phase:**
    * **User Action:** arm "Target" on the rail, then tap the wall.
    * **Data:** captures a bitmap plus depth (when hardware stereo is available) or relies on the
      artist's step-in/step-back baseline for metric triangulation otherwise.
2.  **Rectification Phase:**
    * Plane-guided rectification straightens the captured region using the detected plane, not a
      manual 4-corner drag.
3.  **Feature Extraction Phase:**
    * ORB/SuperPoint descriptors are extracted from the rectified region and become the wall
      fingerprint.
4.  **Anchor Established:**
    * The engine treats this position as the world anchor going forward.

---

## 4. THE AZNAVRAIL (The Nervous System)
**Definition:** The master controller managed by `AzHostActivityLayout`. See
[`UI_UX.md`](../UI_UX.md) for the authoritative current rail hierarchy — three top-level accordion
hosts (**Modes**, **Adjust**, **Project**) plus the plain **Open** and **Help** items. The table
below is a purpose summary, not a literal hierarchy diagram:

| Group | Item | Action / Logic |
| :--- | :--- | :--- |
| **Modes** | `AR` | Switch to the AR viewport, tracked via the fingerprint relocalizer. Dual-lens hardware depth is used when the device exposes it, but is **not mandatory** — devices without it get metric scale from step-in/step-back triangulation, not a lesser fallback. |
| | `Overlay` | Switch to non-AR image tracing over the live camera (CameraX, or a planar homography tracker on the small number of devices without ARCore). |
| | `Mockup` | Switch to compositing over a static reference photo. |
| | `Trace` | Switch to lightbox mode (full brightness, touch-locked). |
| **Modes ▸ AR** | `Target` | Arms tap-to-place for wall-anchor/fingerprint capture. |
| **Adjust** | — | Whole-design toggles: Adjust, Balance, Invert, Outline, Isolate — not general layer tools (there is only one design). |
| **Open** | — | Picks the design image. Stages a replace-confirmation if a design is already placed. |
| **Project** | `Save/Load` | Project manifest (JSON) plus the persisted wall fingerprint — no voxel/map binary. |

---

*Documentation updated on 2026-09-04: removed the fictional `UniversalPlane`/`List<Layer>` multi-
layer stack and `warpMesh`/Liquify field (none of this exists — the design is a single nullable
`Layer`), removed the fictional dense-voxel-cloud/`VoxelMap`/`mSplatData` relocalization description
(no such layer exists — see `NATIVE_ENGINE.md`), corrected "Mandatory Dual Lens HW Stereo" (not
mandatory), removed the `SURVEY` voxel-visualizer row (no such feature), and pointed the rail section
at `UI_UX.md`'s corrected hierarchy. Prior update: 2026-04-24, Persistent Voxel Memory and
Pocket-Ready recovery implementation — the subsystem that update described was later deleted.*
