# SYSTEM RELATIONSHIPS & USER FLOW

## 1. THE UNIVERSAL 2D PLANE (The Content Core)
**Definition:** The `UniversalPlane` is a Global Singleton State. It serves as the immutable "Truth" of the user's artwork.
**Persistence Rule:** Changes here are atomic and instant across ALL modes.

### A. Structure & Data Relationships
* **The Stack:** `List<Layer>`. Order is Z-index (0 is bottom).
* **The Layer:**
    * **`Bitmap source`**: The raw pixel data.
    * **`Matrix transform`**: Stores `Translation(x,y)`, `Scale(sx,sy)`, `Rotation(degrees)`.
        * *Constraint:* Transforms are always stored relative to the layer's center.
    * **`ColorAdjustment adjustments`**: `HSBC` values (0.0-1.0).
    * **`BlendMode blend`**: `Compose.BlendMode` (e.g., SCREEN, MULTIPLY).
    * **`Mesh warpMesh`**: 64x64 geometric mesh for GPU-accelerated Liquify.

### B. Implementation Logic
* **In AR Mode:** The `UniversalPlane` is rendered onto a 3D Quad.
    * *Relation:* `Quad.matrix = AnchorPose * Plane.transform`.
* **In Overlay Mode:** The `UniversalPlane` is rendered to a 2D Canvas.
    * *Relation:* `Canvas.matrix = ScreenSpace * Plane.transform`.
* **In Mockup Mode:** The `UniversalPlane` is rendered on top of the `MockupBackground`.

---

## 2. AR WORLD PERSISTENCE (The Spatial Memory)
**Definition:** The system that locks the `UniversalPlane` to physical reality, even after tracking loss.
**Components:** `MobileGS` (Engine), `VoxelMap` (Spatial Data), `Snap-Back Thread` (Relocalization).

### A. The Dependency Chain
1.  **The Engine (`MobileGS`):**
    * **Input:** Stochastic Camera Frame (Random subset) + IMU Data.
    * **Process:** Generates a dense Voxel Cloud + Camera Pose.
    * **Output:** `PersistentVoxelMemory` (Zero-allocation Spatial Hash).
2.  **The Map (`VoxelMap`):**
    * **Data:** Binary serialization of the `mSplatData` vector.
    * **Function:** Acts as the app's "Spatial Memory".
3.  **The Snap-Back:**
    * **Process:** Background thread matching current frame against stored wall fingerprints via PnP.
    * **Result:** Instantly realigns the coordinate system upon resume from pocket or tracking loss.

---

## 3. TARGET CREATION (The Grid Ritual)
**Definition:** The workflow to establish the initial `Anchor` (Coordinate 0,0,0).

### A. The Workflow Logic
1.  **Capture Phase:**
    * **User Action:** Tap "Shutter" or Screen Tap.
    * **Data:** Captures `Bitmap tempTarget` + DEPTH16 buffer.
2.  **Rectification Phase (Unwarp):**
    * **User Action:** Drag 4 corners to define the plane.
    * **Output:** `Bitmap flatTarget` (The rectified, flat texture of the wall).
3.  **Feature Extraction Phase:**
    * **Process:** Pass `flatTarget` to ORB Feature Detector.
    * **Result:** Wall Fingerprint created and injected into `MobileGS`.
4.  **Injection Phase:**
    * **Action:** `MobileGS.updateAnchorTransform()`.
    * **Result:** The engine now treats this position as (0,0,0) in World Space.

---

## 4. THE AZNAVRAIL (The Nervous System)
**Definition:** The master controller managed by `AzHostActivityLayout`.

| Rail Group | Item ID | Action / Logic |
| :--- | :--- | :--- |
| **MODES** | `AR` | **Switch Viewport:** `ArView` + Mandatory Dual Lens HW Stereo. |
| | `OVERLAY` | **Switch Viewport:** `OverlayScreen` (CameraX). |
| | `MOCKUP` | **Switch Viewport:** `MockupScreen` (Photo Background). |
| | `TRACE` | **Switch Viewport:** `TraceScreen` (Lightbox Mode). |
| **GRID** | `TARGET` | **Trigger:** `TargetCreationFlow`. |
| | `SURVEY` | **Trigger:** Enable `MobileGS` voxel memory visualizer. |
| **DESIGN** | `ADD` | **Intent:** Pick image or add text layer. |
| | `LAYERS` | **UI:** Layer management panel. |
| | `ISOLATE` | **Process:** MLKit background removal. |
| | `OUTLINE` | **Process:** OpenCV Canny edge detection. |
| **PROJECT**| `SAVE/LOAD` | **Process:** Binary serialization of Voxel Memory + JSON manifest. |

---

*Documentation updated on 2026-04-24 during Persistent Voxel Memory and Pocket-Ready recovery implementation.*
