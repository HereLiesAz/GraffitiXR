# UI/UX Patterns & Gesture Control

GraffitiXR is designed for one-handed use while holding a spray can or a ladder. We do not use standard Android navigation (BottomNav, Drawers). Everything is driven by the **Rail**.

## 1. The Rail (AzNavRail)

* **Position:** Vertical strip **left-docked by default** (`isRightHanded = true` maps to a left-side dock; toggle handedness in Settings to move it to the right).
* **Philosophy:** "Thumb Range Only." If you have to reach for the top of the screen, the UI failed.
* **Hierarchy:** three top-level accordion hosts — **Modes** (AR, Overlay, Mockup, Trace), **Adjust** (a small set of whole-design toggles — Adjust, Balance, Invert, Outline, Isolate — not general layer tools), and **Project** (new, save, load, export, settings) — plus two plain top-level items: **Open** (add a layer, switching into Design mode as a side effect; there is no separate "Design" accordion host) and **Help**.
    * **Context Actions:** per-mode options appear as expanding sub-items / nested rails from the active Modes host.
* **Reactive guide:** an in-app onboarding guide (AzNavRail 10.18's status-driven guidance) can point a callout at the next thing to do based on your current mode and state, and suppresses itself during gestures. Per-mode goals are registered with `autoStartWhen = "az.screen.<MODE>"` in `GuidanceDefinitions.kt`, so the guide **does auto-start on mode entry** (see [`docs/DSL.md`](DSL.md)). The **Help** rail item no longer replays it — Help instead opens AzNavRail's built-in help overlay.

## 2. The Viewport & Gestures

The screen is divided into two layers: The **AR World** (Camera) and the **Overlay** (Image).

### AR Mode (Scan & Project)
* **Move Device:** Translates the camera in 3D space.
* **Tap Wall:** Places the "Anchor" (Origin point). A plain tap on its own does **not** start target creation anymore — first select **Target** on the Rail (under the AR mode host) to arm tap-to-place, then tap the surface to capture and lock the anchor.
* **Long Press (Rail Item):** Locks the specific tool (e.g., locks the opacity slider so accidental touches don't change it).

### Edit Mode (Image Manipulation)
When an image is selected:
* **1-Finger Drag:** Pans the active layer. Always live via gesture detection — there is no "Move" tool to select first; any transform gesture (`BeginGesture`) also auto-dismisses an open panel. Use **Lock** on the Rail to freeze pan/zoom/rotate for a mode when you need to protect a reference from accidental drags.
* **2-Finger Pinch:** Scale the image.
* **2-Finger Twist:** Rotate the image.
* **3-Finger Swipe:** Not implemented — no such gesture exists in the current code. (Removed from this doc; previously described as wiping the confidence map / resetting SLAM.)

## 3. Visual Feedback

### The Confidence Cloud (removed)
This section previously described a **Heat Voxel Cubes** visualization (red/yellow/green
confidence-tinted voxels). That subsystem has been deleted — the code's own comment on the relevant
confidence computation now says "voxel/splat map deleted," and it returns a hardcoded `0.0` always.
There is currently no voxel/confidence-tinted visualization in the app.

### The Lazy Grid (removed)
This section previously described a projected grid-line overlay that snapped to the dominant plane
found in the confidence map. It was deleted along with the confidence map it depended on (see
`docs/AUDIT.md`); there is currently no grid overlay in the app.

---
*Documentation updated on 2026-08-07 to correct rail hierarchy, guidance, target-creation, gesture, and
visualization claims against current source.*

*Documentation updated on 2026-09-22: corrected the rail's default docking side (left, not right —
`isRightHanded = true` maps to `AzDockingSide.LEFT`); corrected the reactive-guide TODO, which claimed
the auto-start trigger was dormant — `GuidanceDefinitions.kt` registers `autoStartWhen` per mode and it
does auto-start on mode entry; and marked "The Lazy Grid" as a removed feature, consistent with
`docs/en/screens.md`.*

## Project dialogs (2026-09-06)

Settings uses a modal window so its covered rail cannot open another action. Project creation
navigates only after persistence succeeds. Save waits for the wall map and editable manifest,
keeps its dialog open on failure, and does not export an image or archive. Export saves an image;
Share Wall produces a portable `.gxr`. Importing an existing id creates a separate library copy.
