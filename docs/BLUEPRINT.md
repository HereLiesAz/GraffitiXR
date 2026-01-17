# Project Blueprint

## The Vision
GraffitiXR is the "Photoshop for Reality" for street artists. It is not a game. It is not a social network. It is a precision instrument for **anchoring digital concepts to physical decay**.

We are building a tool that respects the "flow" of painting. It must be:
1.  **Offline First:** Walls are often in dead zones. The app must never require a signal.
2.  **Thumb-Driven:** The user is holding a spray can in one hand. The UI (`AzNavRail`) must be 100% usable with the other thumb.
3.  **Volumetrically Aware:** Simple planar tracking is insufficient for corners, pillars, and rubble. We use Gaussian Splatting (`MobileGS`) to "coat" the world in a digital primer.

## Core Pillars

### 1. The Confidence Engine (SLAM)
* **Goal:** Create a "sticky" world map that improves the longer you look at it.
* **Tech:** Custom C++ Native implementation of 3D Gaussian Splatting.
* **Metric:** A "Confidence Score" per voxel. Only voxels confirmed by multiple viewing angles are persisted. This naturally filters out pedestrians and cars.

### 2. The Rail (Navigation)
* **Goal:** Eliminate menu diving.
* **Tech:** `AzNavRail`.
* **Pattern:** Contextual expansion. The "Project" button expands to show opacity/scale controls right under the thumb. No full-screen modals.

### 3. The Time Capsule (Persistence)
* **Goal:** A digital sketch should remain on the wall for weeks.
* **Tech:** Local feature descriptors (ORB/FREAK) saved in `.gxr` zip containers.
* **UX:** When the artist returns, the app recognizes the wall texture and "snaps" the overlay back into place instantly.

## Anti-Goals (What we are NOT building)
* **No Cloud:** We do not store user data. We do not map the world for Big Tech.
* **No Social:** There is no "Share to Feed." Take a screenshot if you want to share.
* **No Gamification:** No points, no leaderboards, no avatars.