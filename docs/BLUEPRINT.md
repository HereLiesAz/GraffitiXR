# Project Blueprint

## The Vision
GraffitiXR is the "Photoshop for Reality" for street artists. It is not a game. It is not a social network. It is a precision instrument for **anchoring digital concepts to physical decay**.

We are building a tool that respects the "flow" of painting. It must be:
1.  **Offline First:** Walls are often in dead zones. The app must never require a signal.
2.  **Thumb-Driven:** The user is holding a spray can in one hand. The UI (`AzNavRail`) must be 100% usable with the other thumb.
3.  **Pocket-Ready:** Artists frequently stick their phones in their pockets. The app must use its world map to **snap back** and relocalize instantly upon resume.
4.  **Volumetrically Aware:** Simple planar tracking is insufficient for corners, pillars, and rubble. We coat the world in a digital primer using dense, opaque 3D surface elements.

## Core Pillars

### 1. The Persistent Voxel Memory (SLAM & Relocalization)
* **Goal:** Create a "sticky" spatial memory that allows the app to recognize the environment instantly, even after tracking loss or screen-off events.
* **Architecture:** We use a **Zero-Allocation Spatial Hash** for O(1) discovery speed. This ensures the map grows spatially without performance stutters.
* **MANDATED PIVOT:** We explicitly reject soft, alpha-blended "Gaussian Splatting" due to mobile processing overhead. Instead, we use **Dense Opaque Surfels**. Points are perfectly scaled, surface-aligned, and drawn with 100% opacity using hardware Z-buffering (`glDepthMask(GL_TRUE)`). This provides a watertight surface model at a rock-solid 60fps.
* **Efficiency:** **Stochastic Integration** processes a random 2048-pixel subset of each depth frame, reducing CPU load by 90% while maintaining high-density tracking.
* **Metric:** A "Confidence Score" per 20mm voxel. Dual-lens hardware depth is mandatory and rewarded with a 0.9 confidence level, ensuring nearly immutable spatial memory from the first frame.

### 2. The Rail (Navigation)
* **Goal:** Eliminate menu diving.
* **Tech:** `AzNavRail`.
* **Pattern:** Contextual expansion. The "Project" button expands to show opacity/scale controls right under the thumb. No full-screen modals.

### 3. The Time Capsule (Persistence)
* **Goal:** A digital sketch should remain on the wall for weeks.
* **Tech:** Local feature descriptors (ORB/SuperPoint) matched against the Voxel Map via PnP.
* **UX:** When the artist returns, the app recognizes the wall texture and "snaps" the mural back into place instantly.

## Anti-Goals (What we are NOT building)
* **No Cloud / No Scaniverse:** We do not use commercial spatial SDKs (like Niantic Lightship/Scaniverse). We do not map the world for Big Tech.
* **No Social:** There is no "Share to Feed." Take a screenshot if you want to share.
* **No Gamification:** No points, no leaderboards, no avatars.

---
*Documentation updated on 2026-04-24 during Persistent Voxel Memory and Pocket-Ready recovery implementation.*
