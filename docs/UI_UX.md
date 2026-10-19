# UI/UX Patterns & Gesture Control

GraffitiXR is designed for one-handed use while holding a spray can or a ladder. We do not use standard Android navigation (BottomNav, Drawers). Everything is driven by the **Rail**.

## 1. The Rail (AzNavRail)

* **Position:** Vertical strip **left-docked by default** (`isRightHanded = true` maps to a left-side dock; toggle handedness in Settings to move it to the right).
* **Philosophy:** "Thumb Range Only." If you have to reach for the top of the screen, the UI failed.
    * Controls that take a tap and are not on the rail (Recapture, the Link Leave banner, the glasses banner, the library's New / Import / Settings) sit at the bottom on the rail's side; the adjustment knobs are anchored to that side too. Badges that are only read (lock state, distance, the CV legend) stay at the top.
* **Hierarchy:** three top-level accordion hosts — **Modes** (AR, Overlay, Mockup, Trace), **Edit** (a small set of whole-design toggles — Adjust, Balance, Invert, Outline, Isolate — not general layer tools), and **Project** (new, save, load, export, settings) — plus two plain top-level items: **Open** (add a layer, switching into Design mode as a side effect; there is no separate "Design" accordion host) and **Help**.
    * **Context Actions:** per-mode options appear as expanding sub-items / nested rails from the active Modes host.
* **Reactive guide:** an in-app onboarding guide (AzNavRail 10.18's status-driven guidance) can point a callout at the next thing to do based on your current mode and state, and suppresses itself during gestures. Per-mode goals are registered with `autoStartWhen = "az.screen.<MODE>"` in `GuidanceDefinitions.kt`, so the guide **does auto-start on mode entry** (see [`docs/DSL.md`](DSL.md)). The **Help** rail item no longer replays it — Help instead opens AzNavRail's built-in help overlay.

## 2. The Viewport & Gestures

The screen is divided into two layers: The **AR World** (Camera) and the **Overlay** (Image).

### AR Mode (Scan & Project)
* **Move Device:** Translates the camera in 3D space.
* **Terminology:** **Doodle** is the painted reference marks and what GraffitiXR's own engine captures from them (formerly "target" / "fingerprint"). **Target** and **fingerprint** now belong to SphereSLAM, where they mean different things; see [SphereSLAM terminology](SPHERESLAM_ARCORE_SIDECAR.md#terminology).
* **Tap Wall:** Places the "Anchor" (Origin point). A plain tap on its own does **not** start Doodle capture — first select **Doodle** on the Rail (under the AR mode host) to arm tap-to-place, then tap the marks to capture and lock the anchor.
* **Align (Modes ▸ AR):** matches the design against paint already on the wall, in the Doodle's capture photo (ignoring the wall's own texture when a clean-wall photo was taken) and in the Doodle itself once paint covers it, using the same design features the Doodle builder makes (the artwork's own, every design filter's target set, and the inverted design), so paint that only resembles a stencilled, posterized or sketched version of the design still aligns. It then moves, turns and scales the design onto that paint (`MobileGS::autoFitDesign`, also run once automatically after a new Doodle or design). Asked for, it takes any fit that is possible: three or more matches agreeing on one move, with no limit on how far it moves, turns or scales the design (the automatic fit, which nobody asked for, still waits for a confident one). With no possible match it moves nothing and says so; with nothing to match yet (no design, Doodle photo or placement) it says that instead. A tap while it is matching is ignored, a design still being registered is waited for, and a fit is dropped if the design, Doodle, capture photo or placement changed meanwhile (a tone edit does not count). Align and the automatic fit never run at once, and Align waits for the design's filter sets to finish building. A pinned placement is never moved (pinned during the match shows the locked notice), and Align never changes the design's tone. Align is off in standalone (its Doodle is a page frame the paint match cannot fit) and while no design is visible.
* **Doodle grid card:** "If you haven't created your Doodle grid, then take a picture of the wall by pressing the capture button. Otherwise press Cancel." The capture button is a round, unlabelled shutter at the bottom of the screen (filled cyan once a bare-wall photo is kept; pressing again retakes it). Cancel only puts the card and shutter away; tapping the marks works throughout. Leave Doodle capture with the rail item or Back.
* **Standalone (no ARCore):** **Doodle** on the Rail opens the shared capture flow, the same one ARCore uses; the current wall target keeps tracking until the new one is saved, so backing out changes nothing. **New Target** (while a loaded target is not yet tracking) and **Recapture** do the same. A co-op guest keeps the session's wall frame and gets none of these. The tracking status and its actions (Copy Diagnostics, Recapture / New Target, the sweep hint) render in the rail's onscreen layer — inside its safe zones — never in the camera background.
* **Target width (standalone):** after rectifying, the width prompt follows Settings → Units: metres, or feet and inches (`12' 6"`, `12 ft 6 in`, or decimal feet). The accepted range is shown in the active unit and the value is stored in metres. "Continue Without Physical Scale" skips it (normalized units, not metres).
* **No ARCore:** on a device without ARCore (with *Use ARCore* on) a one-shot tap-to-advance explainer appears the first time you are in the editor with no dialog open, explaining that AR uses standalone wall tracking. It is announced as a button ("Next"/"Done") and each line is read once; it never shows on ARCore devices.
* **Photosphere:** the standalone photosphere covers the whole sphere (24 × 6 tiles of 15° × 30°), ceiling and floor included.
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

### Saved-target search status
In AR with a saved target that is not matched yet, the top-right pill says "Looking for your target...";
after 6 s it adds a turn/tilt hint from the compass attitude saved with the target (coarse prior only,
cannot place the overlay; omitted when the heading is unreliable), and after 20 s a "Recapture target"
button. See FEATURE_REFERENCE.md, "Saved-target search status and direction hint".

## Project dialogs (2026-09-06)

Settings uses a modal window so its covered rail cannot open another action. Project creation
navigates only after persistence succeeds. Save waits for the wall map and editable manifest,
keeps its dialog open on failure, and does not export an image or archive. Export saves an image;
Share Wall produces a portable `.gxr`. Importing an existing id creates a separate library copy.

## Settings accessibility and language (2026-10)

The Language picker is a radio group: the current language shows a radio button and is announced as
selected, not just tinted. Selected canvas-colour/glow swatches and the Developer section's
expanded/collapsed state are announced in the active language, and every Settings string (including
the crash-report token dialog) is localized — 15 `values*` directories, now including Russian
(`Русский`) in the picker. The Anticipation sliders are labelled by geography: **Longitude** is the
number of meridians (the stored `latitude` field), **Latitude** the number of circles (the stored
`longitude` field); the stored names are unchanged. A Knob adjusted through an accessibility action
(TalkBack set-progress) is recorded for undo and saved exactly like a drag or double-tap.
