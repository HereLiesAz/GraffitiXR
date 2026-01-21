# UI/UX Patterns & Gesture Control

GraffitiXR is designed for one-handed use while holding a spray can or a ladder. We do not use standard Android navigation (BottomNav, Drawers). Everything is driven by the **Rail**.

## 1. The Rail (AzNavRail)

* **Position:** Vertical strip on the right side (configurable for left-handed use in Settings).
* **Philosophy:** "Thumb Range Only." If you have to reach for the top of the screen, the UI failed.
* **Hierarchy:**
    * **Primary Modes:** (Scan, Project, Trace) are top-level Rail Items.
    * **Context Actions:** (Opacity, Grid Toggle, Save) appear as expanding "Flyouts" from the active Rail Item.

## 2. The Viewport & Gestures

The screen is divided into two layers: The **AR World** (Camera) and the **Overlay** (Image).

### AR Mode (Scan & Project)
* **Move Device:** Translates the camera in 3D space.
* **Tap Wall:** Places the "Anchor" (Origin point) for the confidence map. If no Grid (Target) has been created yet, tapping a surface will automatically initiate the Grid Creation flow.
* **Long Press (Rail Item):** Locks the specific tool (e.g., locks the opacity slider so accidental touches don't change it).

### Edit Mode (Image Manipulation)
When an image is selected:
* **1-Finger Drag:** Panning is **DISABLED** by default to prevent accidental shifts. You must select the "Move" tool on the Rail first.
* **2-Finger Pinch:** Scale the image.
* **2-Finger Twist:** Rotate the image.
* **3-Finger Swipe:** "Wipe" the confidence map (Reset SLAM) in case of drift.

## 3. Visual Feedback

### The Confidence Cloud
Instead of showing raw point clouds, we render **Heat Voxel Cubes**:
* **Red (Alpha 0.2):** Low confidence. Transient. Ignored by the persistence engine.
* **Yellow (Alpha 0.5):** Growing confidence.
* **Green (Alpha 1.0):** Locked. These points are saved to the `.map` file.

### The Lazy Grid
A projected grid line overlay that snaps to the dominant plane found in the confidence map.
* **Purpose:** Helps the artist judge perspective distortion visually.
* **Behavior:** It is "Lazy"—it smooths out jitter. If tracking glitches, the grid floats gently rather than snapping violently.