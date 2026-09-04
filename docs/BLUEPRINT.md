# Project Blueprint

## The Vision
GraffitiXR is the "Photoshop for Reality" for street artists. It is not a game. It is not a social network. It is a precision instrument for **anchoring digital concepts to physical decay**.

We are building a tool that respects the "flow" of painting. It must be:
1.  **Offline First:** Walls are often in dead zones. The app must never require a signal.
2.  **Thumb-Driven:** The user is holding a spray can in one hand. The UI (`AzNavRail`) must be 100% usable with the other thumb.
3.  **Pocket-Ready:** Artists frequently stick their phones in their pockets. The app must use its world map to **snap back** and relocalize instantly upon resume.
4.  **Volumetrically Aware:** Simple planar tracking is insufficient for corners, pillars, and rubble. We coat the world in a digital primer using dense, opaque 3D surface elements.

## Core Pillars

### 1. Fingerprint Relocalization (not a persistent 3D map)

An earlier design pivot considered a dense, always-on voxel/surfel map of the wall (a "Persistent
Voxel Memory," rejecting Gaussian Splatting in favour of opaque surfels with hardware Z-buffering).
**That was never built**, and the codebase now explicitly documents its absence — see
[`NATIVE_ENGINE.md`](NATIVE_ENGINE.md): "It is not a mapping engine — there is no persistent-voxel or
splat layer, no scene reconstruction, and no `draw()`." What shipped instead:

* **Goal:** Recognize the wall instantly after tracking loss or a screen-off event, without a room
  pre-scan and without the cloud.
* **Architecture:** A C++17 engine (`MobileGS`) fingerprints the marks the artist draws on the wall —
  ORB/SuperPoint descriptors plus a handful of triangulated 3D points — rather than densely mapping
  the whole surface.
* **Relocalization:** A background thread continuously matches the live camera against that
  fingerprint and solves the pose with `solvePnPRansac`.
* **Dual-lens hardware depth is used when available, not mandatory** — it improves the metric scale
  prior on devices that expose stereo depth; devices without it get metric scale from the artist's
  natural step-in/step-back baseline (two-keyframe triangulation), not a lower-confidence fallback of
  the same mechanism.

### 2. The Rail (Navigation)
* **Goal:** Eliminate menu diving.
* **Tech:** `AzNavRail`.
* **Pattern:** Contextual expansion. Rail hosts expand in place to show controls right under the
  thumb. No full-screen modals. See [`UI_UX.md`](UI_UX.md) for the current hierarchy.

### 3. The Time Capsule (Persistence)
* **Goal:** A digital sketch should remain on the wall for weeks.
* **Tech:** Local feature descriptors (ORB/SuperPoint) matched against the saved wall fingerprint via
  PnP — not a voxel map (see Pillar 1).
* **UX:** When the artist returns, the app recognizes the wall texture and "snaps" the mural back
  into place. Two mechanisms that extend this — drift correction and a self-growing fingerprint —
  ship opt-in and off by default; see [`TELEOLOGICAL_SLAM.md`](TELEOLOGICAL_SLAM.md).

## Anti-Goals (What we are NOT building)
* **No Cloud / No Scaniverse:** We do not use commercial spatial SDKs (like Niantic Lightship/Scaniverse). We do not map the world for Big Tech.
* **No Social:** There is no "Share to Feed." Take a screenshot if you want to share.
* **No Gamification:** No points, no leaderboards, no avatars.

---
*Documentation updated on 2026-09-04: replaced the "Persistent Voxel Memory" pillar — never built,
and explicitly documented as absent in `NATIVE_ENGINE.md` — with the fingerprint-relocalization
architecture that actually shipped; corrected the "dual-lens mandatory" claim; pointed Pillar 3 at
the wall fingerprint instead of the nonexistent voxel map. Prior update: 2026-06-22, SLAM right-size
and documentation-accuracy pass.*
