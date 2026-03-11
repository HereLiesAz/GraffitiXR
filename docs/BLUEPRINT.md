~~~ FILE: ./docs/BLUEPRINT.md ~~~
# Project Blueprint

## The Vision
GraffitiXR is the "Photoshop for Reality" for street artists. It is not a game. It is not a social network. It is a precision instrument for **anchoring digital concepts to physical decay**.

We are building a tool that respects the "flow" of painting. It must be:
1.  **Offline First:** Walls are often in dead zones. The app must never require a signal.
2.  **Thumb-Driven:** The user is holding a spray can in one hand. The UI (`AzNavRail`) must be 100% usable with the other thumb.
3.  **Volumetrically Aware:** Simple planar tracking is insufficient for corners, pillars, and rubble. We coat the world in a digital primer using dense, opaque 3D surface elements.

## Core Pillars

### 1. The Confidence Engine (SLAM & Dense Surfels)
* **Goal:** Create a "sticky" world map that improves the longer you look at it, forming a solid, photorealistic physical shell.
* **MANDATED PIVOT:** We explicitly reject traditional soft, alpha-blended "Gaussian Splatting" because it produces blurry, "Monet-like" clouds on mobile. Instead, we use **Dense Opaque Surfels**. Points are perfectly scaled, hard-edged, and drawn with 100% opacity using hardware Z-buffering (`glDepthMask(GL_TRUE)`). They interlock like scales to form a watertight surface.
* **Metric:** A "Confidence Score" per 5mm voxel. Only voxels confirmed by multiple viewing angles are persisted. This naturally filters out pedestrians and cars.

### 2. The Rail (Navigation)
* **Goal:** Eliminate menu diving.
* **Tech:** `AzNavRail`.
* **Pattern:** Contextual expansion. The "Project" button expands to show opacity/scale controls right under the thumb. No full-screen modals.

### 3. The Time Capsule (Persistence)
* **Goal:** A digital sketch should remain on the wall for weeks.
* **Tech:** Local feature descriptors (ORB/SuperPoint) saved in `.gxr` zip containers.
* **UX:** When the artist returns, the app recognizes the wall texture and "snaps" the overlay back into place instantly.

## Anti-Goals (What we are NOT building)
* **No Cloud / No Scaniverse:** We do not use commercial spatial SDKs (like Niantic Lightship/Scaniverse) because they require uploading user scans to their VPS (Visual Positioning System) servers. We do not map the world for Big Tech.
* **No Social:** There is no "Share to Feed." Take a screenshot if you want to share.
* **No Gamification:** No points, no leaderboards, no avatars.
~~~