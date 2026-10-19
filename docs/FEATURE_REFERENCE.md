# GraffitiXR — Complete Feature & Options Reference

> The exhaustive reference for every mode, tool, setting, and option in GraffitiXR.
> Companion to the conceptual docs (`ARCHITECTURE.md`, `NATIVE_ENGINE.md`, `TELEOLOGICAL_SLAM.md`,
> `RELOC_MAP_DESIGN.md`, `SELF_GROWING_DOODLE.md`, `STENCILS.md`). Those explain *how*;
> this explains *what every control does*.

Defaults, value ranges, and identity states below are drawn from the source models
(`core/common/.../model/*`) and the settings layer (`core/data` DataStore). Where a control
maps to a persisted key, the key is named so behaviour can be traced end to end.

---

## 0. Mental model in one paragraph

GraffitiXR is a single artifact (a **project**) viewed through five **modes**. The artwork itself
is exactly **one design** (the older multi-layer model — a stack with an active-layer id — was
removed; the design is still represented by the `Layer` type for its per-image properties, but the
editor holds at most one) chosen/replaced in **Design**; every other mode is a *lens* onto that same
design, carrying its own **mode adjustment** (position/tone applied to the whole design for that
lens only). AR mode has two backends: ARCore-supported devices keep the existing ARCore +
MobileGS/doodle path; ARCore-unavailable devices use CameraX + SphereSLAM/KPM to register the
design to a captured wall page. The standalone path uses the same centered KPM wall frame for
MobileGS, self-grow, the persistent feature map, and a bounded multi-page atlas; ARCore-only
depth/planes/anchors are replaced or explicitly unavailable rather than emulated.
ARCore and standalone AR adjustments remain separately persisted because they are different durable
backend placement slots. Hybrid KPM uses an explicit runtime page↔ARCore transform rather than
conflating those slots, and co-op protocol v3 normalizes only the isolated spectator copy through an
explicit host wall-frame descriptor. Nothing touches the network unless you explicitly start a
**co-op** session.

---

## 1. Modes (`EditorMode`)

Five operational modes. The mode is a lens; switching modes never mutates the design, only which
adjustment/anchoring lens is active.

| Mode | Purpose | Anchoring | Primary output |
|---|---|---|---|
| **AR** | Anchor the design to a real wall for painting at scale | ARCore: continuous ARCore anchor + MobileGS doodle, with optional metric KPM→PoseFusion correction; standalone: SphereSLAM/KPM canonical wall page/atlas + short IMU bridge | On-wall overlay; ARCore supports composited `glReadPixels` export, standalone preview export is currently disabled |
| **MOCKUP** | Compose the design onto a static wall photo | None (static image) | Flattened preview image |
| **OVERLAY** | Classic non-AR tracing — reference over live camera | None (screen-space) | Sensor still + composited layers (CameraX) |
| **TRACE** | Phone-as-lightbox for copying onto paper | Locked screen-space | Transparent-background PNG |
| **DESIGN** | Where the single design is chosen/replaced and edited | N/A (canvas) | The project itself |

### 1.0a AR backend capability split

| Capability | ARCore-supported AR | ARCore-unavailable standalone AR |
|---|---|---|
| Camera owner | ARCore `Session` / `ArRenderer` | CameraX |
| Camera view | full screen: ARCore's background quad is centre-cropped to the view by `Frame.transformCoordinates2d`, and its projection/hit tests share that display geometry | full screen: `PreviewView` `FILL_CENTER` (as in Overlay); the artwork projection, target-capture taps and coverage-glow lens apply the same centre crop (SphereSLAM `camera.CameraFillCenter`), while tracking keeps the full analysis frame |
| Continuous pose | ARCore | calibrated SphereSLAM/KPM page match |
| Short visual miss | ARCore tracking/fusion | rotation-only IMU bridge, max 400 ms |
| Persisted wall target | ARCore target/doodle data | versioned rectified SphereSLAM reference PNG + width/metric metadata |
| MobileGS doodle / self-grow | existing path | centered-page doodle + self-grow + separately persisted wall map |
| Wall extent | ARCore planes / hit tests / depth where available | canonical KPM page/atlas; screen hits use calibrated ray→z=0 wall intersection |
| Depth / distance | ARCore depth / proved hardware stereo / triangulated depth | unavailable; normalized KPM scale is never presented as depth |
| Anchor object | local ARCore `Anchor` + fusion | canonical centered wall frame + persisted generation/version |
| Perception debug | ARCore planes, feature points, accumulated scan cloud | KPM page/inliers/reprojection error/age/match-time/tracking-state diagnostics |
| Target rail action | ARCore tap-to-target flow | disabled; use the on-screen **Wall Target** capture |
| Co-op Host/Join | protocol-v3 host wall frame + peer doodle | protocol-v3 host wall frame; standalone↔standalone may use normalized page units, cross-backend requires metric scale + peer doodle |
| Drift correction | PoseFusion (on by default): accepted metric KPM observation first, MobileGS fallback, otherwise hold standing anchor-local correction | KPM is the primary wall pose while visible; MobileGS corroboration/self-grow use the same centered page frame, not ARCore PoseFusion |
| AR preview export | composited GL framebuffer | disabled until CameraX + transparent GL can be composited correctly |
| Pan/scale/rotation/tone/lock | `modeAdjustments[AR]` | `sphereSlamModeAdjustment`; the same controls/undo UI swap to the active backend's persisted adjustment, and standalone spatial placement is generation-bound to its canonical wall |

A normalized standalone target width gives self-consistent registration but is **not physical metres**.
Physical distance/size claims are valid only when the captured target width was explicitly measured.

The measured-width dialog (standalone, used when this overlay owns capture) follows Settings → Units:
metric asks for **metres**; imperial asks for **feet and inches** and accepts `12.5` (bare number =
decimal feet), `12'`, `12' 6"`, `12'6`, `12 ft 6 in`, `78"`. The entry is converted to metres before
the same 0.05 m – 100 m range check (shown as 2 in – 328 ft when imperial), and only metres are stored
or passed on (`StandaloneReferenceScale.parseMeters(raw, imperial)`). Its prompt, labels, error and
buttons are localized.

As of this writing, DESIGN does not have its own dedicated entry in the mode-switcher host on the
Rail; it's reached indirectly (e.g. importing an image via the top-level **Open** action switches
into DESIGN as a side effect). TODO: confirm whether a direct DESIGN rail entry is added by other
in-flight work, and update this note if so.

### 1.1 Per-mode "whole-design" adjustment (`ModeAdjustment`)

Each mode stores its own adjustment so you can, e.g., line the mural up on a wall in MOCKUP without
disturbing how it sits in AR. **Design edits stay global; mode adjustments are per-mode overlays.**

| Field | Default (identity) | Effect |
|---|---|---|
| `offsetX`, `offsetY` | `0`, `0` | Pan the whole design within this mode |
| `scale` | `1` | Uniform scale of the whole design |
| `rotation` | `0` | Spin about the design's normal (Z / in-plane) |
| `rotationX`, `rotationY` | `0`, `0` | Tilt about the design's own width (X) / height (Y) axes — used in AR so a double-tap can switch the live axis and the artwork tilts in true 3D |
| `brightness` | `0` | Additive brightness for the whole design |
| `contrast` | `1` | Contrast multiplier |
| `saturation` | `1` | Saturation multiplier |
| `opacity` | `1` | Whole-design opacity |
| `isInverted` | `false` | Invert the whole design |
| `isTransformLocked` | `false` | Freeze pan/zoom/rotate gestures for this mode (tone/opacity + lightbox lock stay independent) |

**Transform lock** is surfaced as a **Lock** rail sub-item under each non-Design mode folder
(AR / OVERLAY / MOCKUP / TRACE); it turns cyan when engaged. Use it to pin a reference that must not
drift (e.g. a Trace reference) while you still adjust tone.

### 1.2 AR mode specifics

**Scan Mode and Mural Method are removed / legacy.** `ArScanMode` (Canvas/Mural) and `MuralMethod`
(three named mapping engines) are no longer live, user-settable options — they are not exposed
anywhere in the current Settings surface. The persisted `ar_scan_mode` key is kept only as a
one-time legacy-migration read; the repository's own comment describes `MuralMethod` as naming "two
mapping engines … that no longer exist." Treat any reference elsewhere in this doc (or in the app)
to "Canvas vs Mural" scanning or "Mural v1/v2/v3" as describing a removed concept, not a current
setting. TODO: confirm what (if anything) replaced the scan-mode/mural-method distinction in the
current AR pipeline, and document that here instead.

**Scan phases** (`ScanPhase`): `AMBIENT` → `WALL` → `COMPLETE`. The guide surfaces only the actions
relevant to the current phase.

**Relocalization state** (`RelocState`, derived in UI from `isAnchorEstablished` + `paintingProgress`):
the further along the painting, the tighter the teleological lock — see §6.

**Rotation axis** (`RotationAxis`: `X` / `Y` / `Z`): double-tap in AR cycles the active axis; the
`CycleRotationAxis` intent advances it. A `RotationAxisFeedback` overlay shows the current axis.

**Tap-to-distance:** the reticle + distance chips light up when `(isHardwareStereoActive || currentCenterDepth > 0f)`
on the ARCore backend — i.e. on devices exposing proved hardware stereo depth or a valid
triangulated centre depth. Standalone SphereSLAM explicitly clears these flags and does not turn KPM
page scale into a distance. (Renamed from
`isDualLensActive`: the old flag lit as soon as the software-stereo path's buffers allocated, advertising
depth on devices that had none; `StereoDepthProvider`/`StereoProcessor` were removed and the flag collapsed
into `isHardwareStereoActive`, the only one that was ever real.)

**Measure (wall width):** AR rail ▸ Measure, shown once an anchor is established on the ARCore
backend. Two taps on the wall; each tap's camera ray is intersected with the plane the design is
drawn on (the overlay base frame's local z = 0, `anchor/WallMeasure.kt`). The first tap's plane is
frozen anchor-locally and reused for the second tap, so a fusion re-orientation between taps
cannot tilt it; the hit is then kept
relative to the unfused ARCore consensus anchor, so a fusion correction landing between the two taps
does not change the result. Offered only when the plane is the real wall: the anchor's normal came
from a VERTICAL ARCore plane (not a floor/tabletop, not a depth/feature-point or fallback anchor, whose camera-facing normal would
foreshorten an oblique wall) and the overlay rotation correction was applied
(`ArUiState.measureAvailable`). Taps while ARCore is not tracking are refused individually. Rays steeper than 75° to the wall, hits outside 0.1–10 m, and widths outside 0.05–100 m are
refused with a re-tap prompt. Save writes `GraffitiProject.wallWidthMeters` (null = never measured;
`anchor/WallWidthPersistence.kt`) and leaves Measure only once the write lands in the measured
project — otherwise the reading stays and an error asks to save again;
Redo starts over. Markers sit at the tapped screen positions. In co-op, Save works for host and
guest alike (the guest is the primary measurer): the saved width is published as `Op.WallWidth`
(protocol v5); a guest's goes to the host, which persists it to the authoritative project and
rebroadcasts it, and a host's goes to its guests. The width is metric, so it crosses between
backends and devices unconverted; a joining guest gets it in the bulk project archive. Not offered on standalone SphereSLAM
(no measurement path yet; it must be limited to physically metric targets when added).

### 1.2a OVERLAY ▸ Gyro (tripod stabilisation)

**Gyro** is a toggle sub-item under the Overlay rail folder (cyan while engaged). It is for a phone
mounted on a tripod: very small vibrations make the camera image shimmy under the screen-fixed
design, and Gyro moves the drawn design with the wall instead.

- **On:** captures the current device orientation (`TYPE_GAME_ROTATION_VECTOR`, via SphereSLAM's
  `GameRotationAttitudeSource`) as the reference. Every frame the orientation delta since then becomes
  the pure-rotation screen homography `H = K·R·K⁻¹` (SphereSLAM `GyroCompensationMath`), with K from
  `CameraIntrinsicsEstimator` scaled to the full-screen `FILL_CENTER` preview
  (SphereSLAM `CameraFillCenter.screenIntrinsics`, the fill counterpart of `ScreenIntrinsics`; a
  nominal 68° field of view if Camera2 reports nothing). `H` is drawn as an extra screen-space transform around Overlay's whole-design
  draw, so the design's edits and Overlay's `ModeAdjustment` are unchanged.
- **Release:** rotation beyond **3°** since the reference (tripod bumped, phone picked up)
  releases Gyro with a short toast; so does a device with no rotation-vector sensor. Turning it off,
  leaving Overlay, or opening the library releases the sensor listener; the design
  returns to its normal Overlay placement. A display-rotation change re-captures the reference.
- **No depth:** depth would feed only the plane-induced parallax term `t·nᵀ/d`, and **translation is
  not measured**, so the term is off. Gyro therefore takes no stills and runs no depth model (an
  earlier build ran MiDaS every 15 s and discarded the result). Pure rotation is exact without depth.
- Overlay **Export** composites the design at its uncompensated position (sub-degree difference).

### 1.3 MOCKUP wall-capture flow (`CaptureStep`)

`NONE → CAPTURE → RECTIFY → MASK → REVIEW` — grab a wall photo, rectify perspective, mask the paintable
region, review. The result becomes the static backdrop the design composes onto.

### 1.4 TRACE (lightbox) behaviour

Full-brightness surface for copying onto paper. Locks the image in place, **keeps the screen on with
brightness maxed**, **retracts the nav rail automatically**, and **blocks all touches until a
deliberate Volume Up-Down-Up-Down exit** — a physical-button sequence, not a touch gesture, so a
resting hand or the paper itself can't trigger it by accident. Exports a transparent-background PNG
(not a solid fill).

---

## 2. The design (`Layer`)

Per §0/§1, the editor now holds exactly **one** design, not an ordered list of layers with an
active-layer id — the older multi-layer model was removed (see the KDoc on `EditorUiState.design` in
`core/common/.../model/EditorModels.kt`: *"Singular by design... It was a `List<Layer>` with an
`activeLayerId`, which cost every consumer a lookup..."*). The `Layer` type keeps its name because it
still carries exactly the per-image state the adjustment knobs drive, but there is no layer list, no
active-layer id, and no per-layer add/remove/reorder/rename operations — those, and any compositing of
several images into one design, are the companion design app's job now, not this app's. `EditorActions.kt`
(the current UI-facing surface) documents this directly: *"Placement is driven entirely by gestures...
not per-field setters: the sliders that scale/offset/per-axis-rotation setters existed for were removed,
and the setters outlived them with no caller."*

Every layer carries the full property set below; all except the runtime `bitmap` are serializable
(wire-transferable for co-op and project save).

### 2.1 Full layer property table

| Property | Default | Range / type | Effect |
|---|---|---|---|
| `id` | — | String | Stable identity (co-op ops, undo) |
| `name` | — | String | Display name |
| `uri` | `null` | Uri | Source image location (serialized) |
| `bitmap` | `null` | Bitmap (`@Transient`) | Runtime pixels; never serialized |
| `isVisible` | `true` | Bool | Show/hide |
| `opacity` | `1.0` | `0.0–1.0` | Alpha |
| `brightness` | `0.0` | additive | Lighten (+) / darken (−) |
| `contrast` | `1.0` | multiplier | Contrast |
| `saturation` | `1.0` | multiplier | Colour intensity; `0` = greyscale |
| `colorBalanceR` | `1.0` | multiplier | Red channel gain |
| `colorBalanceG` | `1.0` | multiplier | Green channel gain |
| `colorBalanceB` | `1.0` | multiplier | Blue channel gain |
| `isImageLocked` | `false` | Bool | Lock transforms on the design |
| `isLinked` | `false` | Bool | Link with the active layer so gestures move them together |
| `blendMode` | `SrcOver` | `BlendMode` | Compositing mode (see §2.3) |
| `offset` | `(0,0)` | Offset | Pan within the canvas |
| `rotationX` | `0` | degrees | Tilt about width axis |
| `rotationY` | `0` | degrees | Tilt about height axis |
| `rotationZ` | `0` | degrees | In-plane spin |
| `scale` | `1.0` | multiplier | Uniform scale |
| `isInverted` | `false` | Bool | Colour invert |
| `isSketch` | `false` | Bool | **Outline** effect — render as a traceable pencil sketch (dark lines opaque, light areas transparent); re-derived from `uri` each time, not baked in |
| `isSubjectIsolated` | `false` | Bool | **Subject isolation** — the bundled U²-Net segmenter drops everything but the subject to transparent (see §4) |
| `isStencil` | `false` | Bool | **Stencil** — the design is split into three tonal sheets (black, grey, white); exclusive with `isSketch` (§5) |
| `stencilView` | `ALL` | `ALL`/`BASE`/`MIDTONE`/`SECONDARY` | Which sheets are shown while `isStencil` is on |
| `stencilDark` | `true` | Bool | The last run's dark-or-light call, which names the cycler's options |

`warpMesh`, `stencilType`, `stencilSourceId`, and `textParams` do not exist on `Layer` — Liquify and
rasterized text layers have no implementing code (see §3.3, §2.4). Stencil is the `isStencil` /
`stencilView` pair above (§5), not the old multi-layer pipeline.

### 2.2 Setting/replacing the design

There is no layer list to operate on, so there are no `AddLayer` / `RemoveLayer` / `ReorderLayers` /
`ActivateLayer` intents. The design is instead set or replaced wholesale (`EditorIntent`, in
`feature/editor/.../EditorIntent.kt`):

| Intent | Behaviour |
|---|---|
| `SetDesign(layer, resetActivePanel)` | Replace the design with `layer` |
| `SetPendingReplaceUri(uri)` | Stage a picker result that would replace an existing design, pending user confirmation (`confirmReplaceDesign` / `cancelReplaceDesign`); `onAddLayer(uri)` on `EditorActions` is the entry point despite the name |
| `SetDesignTransform(scale, offset, rx, ry, rz)` | Spectator/remote-op application — no panel or gesture side effects |
| `SetDesignProps(props)` | Property-only mutation (opacity, tone, blend mode, etc.), same no-side-effect path |
| `RestoreDesign(design)` | Replace the design outright (undo restore); `null` clears it |
| `LoadedProject(projectId, design)` | A project finished loading |
| `ClearProject` | Drop the current project |

Co-op propagates design mutations as `Op`s (`core/common/.../model/Op.kt`): `DesignReplace`,
`DesignTransform`, `ModeTransform`, `DesignProps`, `DesignBitmapReplace` (PNG-encoded, used for
undo/redo and any pixel effect). `Op.StrokeComplete` and `Op.TextContentChange` still exist in the
sealed class but are received as a no-op (`is Op.StrokeComplete -> Unit`) — nothing in the current UI
emits either, since there is no brush tool and no text-layer authoring (see §3.3, §2.4).

### 2.3 Blend modes (`BlendMode`)

Standard Compose/Skia blend modes are supported and serialized (`BlendModeSerializer`), default
`SrcOver`. The set includes the normal, darken (Multiply, Darken), lighten (Screen, Lighten, Plus),
contrast (Overlay, HardLight, SoftLight), and comparative (Difference, Exclusion) families, plus the
Porter-Duff compositing operators.

### 2.4 Text layers — removed

No text-layer feature exists in the current app. There is no `TextLayerParams`, no `TextRasterizer`,
and no `RenderTextLayer` intent; `Op.TextContentChange` exists on the wire protocol but nothing sends
it (see §2.2). Treat "text layers" as a removed/never-shipped concept, not a current feature.

---

## 3. Design controls (Adjust / Color balance panels)

These act on the design (contrast with §1.1, which acts on the whole design **per mode** via
`ModeAdjustment`; these act on the design layer itself, in DESIGN mode).

### 3.1 Visual adjustments (`ADJUST` panel)

| Control (intent) | Default | Range | Notes |
|---|---|---|---|
| `SetOpacity` | `1.0` | `0–1` | |
| `SetBrightness` | `0.0` | additive | |
| `SetContrast` | `1.0` | multiplier | |
| `SetSaturation` | `1.0` | multiplier | |
| `SetColorBalanceR/G/B` | `1.0` each | multiplier | Surfaced in the `COLOR` panel (`onColorBalance{R,G,B}Changed`) |
| `ToggleInvert` | `false` | — | |
| `onToggleOutline` | `isSketch = false` | — | The traceable-sketch effect (§2.1) |
| `onToggleSubjectIsolation` | `isSubjectIsolated = false` | — | Drop everything but the segmented subject (§4) |
| `onToggleStencil` | `isStencil = false` | — | Three tonal sheets; turns Outline off (§5) |
| `onCycleStencilView` | `stencilView = ALL` | `ALL` → `BASE` → `MIDTONE` → `SECONDARY` → `ALL` | Only while a stencil exists (§5) |

Colour maths run through `ColorMatrixUtils`.

There is no dedicated curves dialog (`CurvesAdjustment`/`CurvesDialog`/`CurvesUtil` do not exist) and
no `ColorBalanceDialog` class — colour balance is three sliders in the `COLOR` panel, not a dialog.

### 3.2 Transform — gesture-only

**There is no numeric transform panel.** `SetScale`, `AddOffset`, `SetRotationX/Y/Z`, and
`SetLayerTransform` do not exist as intents — placement is driven entirely by gestures
(`ApplyModeTransformGesture`, `SetDesignTransform`) and by `CycleRotationAxis` for the double-tap
rotation-axis switch (§1.2). `EditorActions.kt` states this directly: *"the sliders that
scale/offset/per-axis-rotation setters existed for were removed, and the setters outlived them with no
caller."* `ToggleImageLock` remains, to lock the design's transform against gestures.

### 3.3 Tools — removed

**There is no `Tool` enum, no `SetActiveTool` intent, and no raster paint tools in the current app.**
`BRUSH`, `ERASER`, `BLUR`, `HEAL`, `BURN`, `DODGE`, `LIQUIFY`, and `COLOR`-as-a-tool do not exist
anywhere in the source. `BrushStroke` and `Op.StrokeComplete` still exist as data types (co-op wire
protocol), but nothing in the current UI creates a `BrushStroke` or dispatches `SetActiveTool` — a
received `StrokeComplete` op is a no-op on the receiving end (see §2.2). Treat freehand painting,
erasing, blur/heal/burn/dodge, and Liquify as removed/never-shipped, not current features. What
remains, editing-wise, is the Outline and Subject-isolation toggles (§3.1) plus the legibility
adjustments — see `EditorActions.kt`'s own framing: *"Scoped to this app's job: getting one image into
place for tracing... Authoring belongs to the companion design app."*

### 3.4 Panels (`EditorPanel`)

The enum still declares `NONE`, `LAYERS`, `ADJUSTMENTS`, `TRANSFORM`, `COLOR`, `ADJUST`, but only
`ADJUST` and `COLOR` are ever set by the reducer or read by the UI — `LAYERS`, `ADJUSTMENTS`, and
`TRANSFORM` are vestigial values with no live path to or from them. Managed by `ToggleAdjustPanel`,
`DismissPanel`, `ToggleColorPanel`. Any transform gesture (`BeginGesture`) auto-dismisses an open panel.

---

## 4. Subject isolation & Outline effects

`SubjectIsolator` (`feature/editor/.../SubjectIsolator.kt`) runs U²-Net (small, `assets/u2netp.onnx`,
bundled; see `docs/MODELS.md`) through OpenCV's DNN module, on the device and offline, to cut the
design's subject out, making everything else transparent. A model that fails to load or run is
reported as unavailable, never as a fault of the image, and no failure is cached. There is no
`BackgroundRemover` class, and no stencil pipeline to feed (§5) — isolation exists solely as one of
the design's two toggleable rendering effects.

- Toggled via `isSubjectIsolated` on `Layer` (`onToggleSubjectIsolation` / rail id `design.isolate`),
  applied together with the Outline sketch effect (`isSketch`, `onToggleOutline` / `design.outline`) in
  `EditorViewModel.applyDesignEffects`: isolation runs first, Outline second, each falling back to its
  input on failure so one effect failing doesn't cost the whole image.
- Effects are re-derived from the untouched source bitmap on every toggle (not baked in), so turning
  one off restores the original exactly.
- The bitmap fed to the segmenter is always downsampled to ≤2048 px on its longest side, not the
  full-res original; confidence mask is thresholded at `0.5` with a `0.1` feather band.
- There is no `BeginSegmentation`/`EndSegmentation`/`SetSegmentationInfluence`/`SetSegmentationPreview`
  intent, and no per-effect influence slider — each effect is a boolean, on or off.
- `SetBackgroundBitmap(bitmap)` is a separate, real intent: it sets the active backdrop (the MOCKUP
  wall photo), unrelated to subject isolation.

---

## 5. Stencil

The design can be split into three tonal sheets: black, grey and white.

- **Rail**: `Stencil` (`design.stencil`) is a sub-item of the Design folder. Tap to generate, tap
  again to bring the original back. While a stencil exists, a cycler (`design.stencil.view`) appears
  directly beneath it and steps **All → base → midtone → secondary**: All, Black, Grey, White for a
  dark image; All, White, Grey, Black for a light one.
- **State**: `Layer.isStencil` and `Layer.stencilView`. Like Outline and Isolate they are flags;
  the shown bitmap is re-derived from the untouched import (`uri`), so turning it off is exact.
  Stencil and Outline are exclusive.
- **Pipeline** (`StencilProcessor`, `StencilTones`, `core/common`), restored from the old tool: it
  first tries to **remove the background** (the same segmenter as Isolate; if no subject is found it
  carries on with the whole image, and Isolate's own flag is not touched), and only then judges the image **dark or light**: the isolated subject's luminance (0.299R + 0.587G +
  0.114B) is turned down by 40 levels and its contrast doubled about mid-grey, which leaves each pixel
  clearly dark or light, and whichever covers more **area** wins (a tie is dark). That picks the **base sheet**: black for a dark image, white for
  a light one. The value channel (brightest of R, G, B) is median-blurred (9x9), split into three
  tones by a deterministic 1-D K-means, and the two sheets laid over the base are closed 5x5 so cut
  edges are smooth. Transparent pixels stay transparent, so Isolate then Stencil keeps the cut-out.
- **Sheets** are sequential, as paint is. Dark image: black = the whole subject, grey = grey and
  lighter, white = the lightest. Light image: white = the whole subject, grey = grey and darker,
  black = the darkest.
- **Invariants** (`StencilTones.ranks`, unit-tested): the base is always the complete silhouette of
  the isolated subject, holes in the cut-out filled in; if the background could not be removed, the
  silhouette is the whole frame. The midtone always covers at least the secondary: a secondary
  pixel is a midtone pixel, so the midtone's area is never smaller.
- **Views**: All stacks the sheets (every pixel in its own tone). Base, Midtone or Secondary shows that
  one sheet in its colour and leaves the rest transparent.
- The same method also drives the `stencil3` filter of the SLAM passes (`docs/PASSES.md`).

Not rebuilt: the old multi-sheet PDF export and tiling (`docs/STENCILS.md` still describes that as a
design document).

---

## 6. Relocalization & Teleological SLAM (ARCore/MobileGS path)

This section describes relocalization/corroboration around the wall target. On ARCore-capable
devices, MobileGS remains the durable doodle/return-visit path and optional PoseFusion fallback,
while a physically metric rectified KPM page can provide a timestamp-aligned correction through
`HybridKpmCorrection → PoseFusion`. On standalone devices, KPM supplies the canonical wall pose and
MobileGS doodle/self-grow/feature-map data live directly in that same centered page frame.
Detailed math in `RELOC_MAP_DESIGN.md`, `SELF_GROWING_DOODLE.md`,
`TELEOLOGICAL_SLAM.md`, `NATIVE_ENGINE.md`, and `SPHERESLAM_ARCORE_SIDECAR.md`.

- **Doodle capture.** When you lock onto a wall, the native engine (`MobileGS`, C++17) captures an
  OpenCV feature **doodle** of your marks: ORB/SuperPoint descriptors plus a handful of
  triangulated 3D points (`WallFeatureMap` / `Doodle`). No cloud, no room pre-scan.
- **Snap-back.** After tracking loss or a screen-off (pocket) event, the engine matches the live camera
  against the doodle and solves the pose via **PnP/RANSAC** to realign the overlay in milliseconds.
- **Teleological self-grow.** Because the intended result is known, OpenCV can watch your progress and
  **extend the doodle from validated new marks** as you paint — so snap-back survives the original
  reference marks being painted over, tightening the lock instead of degrading it.
- **These are A/B switches.** `ArViewModel.evalSetFusionEnabled`
  (drift correction, default **on**) and `evalSetSelfGrowEnabled` (teleological self-grow, default
  **on**) are both persisted (`SettingsRepository.driftCorrectionEnabled` /
  `.selfGrowEnabled`), and are surfaced as "Drift correction: ON/OFF" and "Self-grow: ON/OFF"
  toggles in the AR diagnostics overlay — see §7.4a. With drift correction enabled, a fresh accepted
  physically-metric KPM observation has first priority; MobileGS PnP is the fallback correction
  source; otherwise PoseFusion carries the existing anchor-local correction on the live ARCore
  consensus. KPM never replaces ARCore camera view/projection. A third switch, **feature map** (`featureMapEnabled`, also
  default `false`), gates the persistent wall feature map (build + match); before it existed the
  underlying native flags (`SlamManager.setMapRelocEnabled`/`setMapBuildEnabled`) had no caller at all,
  so the map — and the `.gxr` project field for it — could never do anything. All three now
  default on; switch any off from the diagnostics overlay.
- **Depth sources on the ARCore path.** The ARCore Depth API (`DepthMode.AUTOMATIC`) is the
  Settings **ARCore depth** switch (default on, dropped in camera safe mode, still gated by
  `isDepthModeSupported`). The hardware-stereo camera config is adopted only after the one-time
  `StereoProbeService` probe confirms the forced dual-lens config is actually in force
  (`stereoCameraUsage == REQUIRE_AND_USE`) and, under it, tracks and returns a populated depth map.
  ARCore exposes no per-frame depth-source signal, so the probe cannot prove the depth was
  triangulated from disparity rather than inferred; it proves the configuration works. The probe
  runs after the camera-free gate, and a probe that cannot open the camera persists nothing and
  retries on the next AR entry. Monocular (MiDaS) depth is never used on the ARCore path. Metric
  scale for the doodle comes from `MetricDoodleBuilder`'s two-keyframe triangulation.
- **Thread safety.** `mWallDescriptors` / `mWallKeypoints3D` are mutex-guarded against races between JNI
  updates and the background PnP thread. The JNI ABI is frozen via `Doodle.fromNative` and locked by
  `DoodleJniContractTest`.

**JNI contract note (maintainers):** adding fields to `Doodle` without updating the frozen factory
descriptor will silently return null from `nativeSetWallDoodle`. The contract test exists to catch this.

---

### Saved-target search status and direction hint

When the project already has a saved ARCore target (`project.doodle != null`) but the live view is
not matched yet (anchor established, `paintingProgress == 0`), the top-right pill reads **"Looking for
your target..."** instead of the generic "Scanning...". Once the target locks it becomes "Matched n%".

- **After 6 s** a second line gives a coarse direction hint, e.g. "Turn right 40", "Turn left 25 . Tilt up
  10", or "You're facing it - move slowly side to side".
- **After 20 s** a **Recapture target** button appears (same effect as the rail's Target item; disabled
  while a target is saving or a capture is in progress).
- The timer resets when the target locks or the project changes.

The hint compares the rear-camera look direction saved with the target
(`CaptureEnvironment.attitude.rotationVector`, a `TYPE_ROTATION_VECTOR` quaternion, body to
East-North-Up) with the live one (a rotation-vector listener registered only while this status is
visible, ~5 Hz, hint rounded to 5 degrees). Within 20 degrees on both axes it counts as "facing it".
If either the saved or live magnetometer accuracy is UNRELIABLE or LOW, or no attitude was saved, no
hint is shown (a wrong "turn right 90" is worse than none). It is a coarse compass/gravity prior
only: it cannot place the overlay and does not influence relocalization or tracking. ARCore's world
frame resets every session, so the ARCore poses saved at capture cannot be reused; only the
compass/attitude survives between sessions. Logic: `feature/ar/.../DoodleSearchHint.kt`.

## 7. Settings (persisted — `core/data` DataStore)

Every persisted key, its default, and effect. Adaptive-throttle keys default **on**.

**Settings screen layout:** General (language, hand, units, canvas background, reset tutorials) · AR
(ARCore, ARCore depth, monocular depth, ambient scan, anchor boundary, camera fps — "Device default" /
"Device max" are localized) · Coverage glow ·
Power (Power saving, Adaptive rate) · Privacy (crash reports, token) · App information (version,
releases page, camera permission) · Developer (the §7.4 overlays; starts collapsed). On/off settings
are switches.

### 7.1 Performance / perception throttle

| Setting | Key | Default | Effect |
|---|---|---|---|
| Adaptive rate | `adaptive_rate_enabled` | on | Master switch for the adaptive perception throttle; caps the camera to 30 fps at battery ≤ 30%; standalone tracking skips frames to stay under half duty once its work exceeds a camera frame |
| Throttle on thermal | `throttle_on_thermal` | on | When the device is thermally stressed: perception floor 30 fps, standalone analysis ≤ 15 fps, slower relocalizer and MiDaS |
| Throttle on power-save | `throttle_on_power_save` | on | The same in OS power-save mode |
| Throttle on low battery | `throttle_on_low_battery` | on | The same on the low-battery warning or at ≤ 15%; at ≤ 30%: analysis ≤ 20 fps, relocalizer and MiDaS at half rate |
| Throttle on lag | `throttle_on_lag` | on | Perception floor 30 fps when frame lag is detected |

The four `throttle_on_*` keys share one switch in Settings, **Power saving**: it reads on only while all
four are on, and toggling it sets all four.
| Camera target FPS | `camera_target_fps` | `60` | ARCore camera capture target; a change re-selects the live session's camera config immediately |

**Perception model:** world-locked perception layers render into an offscreen FBO that refreshes only on
meaningful camera-pose change or SLAM-map growth, and is composited every frame while camera passthrough,
artwork, and gestures stay at full display rate. With no throttle condition active, perception runs 60 fps;
any active condition (per the toggles above) floors it to 30 fps.

### 7.2 Depth / tracking

| Setting | Key | Default | Effect |
|---|---|---|---|
| Depth capability | `depth_triangulation_capability` | device-detected | Device's depth-triangulation tier |
| Force stereo (unstable) | `forced_stereo_unstable` | off | Force hardware stereo depth even when flagged unstable |
| Show anchor boundary | `show_anchor_boundary` | — | Draw the anchor's boundary in AR |

There is no `parallax_min_degrees` key — no code anywhere defines or reads a minimum-parallax setting.

### 7.2a Relocalization experiment switches (AR diagnostics overlay)

Three persisted, default-**off** switches gate the teleological-SLAM behaviour described in §6. All
three are surfaced as toggle rows in the AR diagnostics overlay (`MainActivity.kt`), not in the main
Settings screen:

| Setting | Key | Default | Effect |
|---|---|---|---|
| Drift correction | `drift_correction_enabled` | on | "Fusion" — pulls the overlay back onto each accepted relocalization instead of riding the raw ARCore anchor |
| Self-grow | `self_grow_enabled` | on | Promotes newly validated marks into the reloc doodle as you paint; writes the map, so is colour-coded amber rather than green |
| Feature map | `feature_map_enabled` | on | Builds and matches against the persistent wall feature map (`WallFeatureMap`); also writes, also amber |

### 7.3 Interface / locale / canvas

| Setting | Key | Default | Effect |
|---|---|---|---|
| Handedness | `is_right_handed` | — | Which side the AzNavRail sits (one-handed use). Also `ToggleHandedness` |
| Units | `is_imperial_units` | — | Imperial vs metric for sizes/distances, including the standalone target-width entry |
| Canvas background | `background_color` | — | `SetCanvasBackground` — colour behind the design in Design/editor |
| Language | `language` | System | One of the 16 options (§9). Applied only after the stored value has been read, so the locale AppCompat restored on a cold start is never overwritten by a placeholder |
| Scan mode (removed/legacy) | `ar_scan_mode` | — | No longer a live setting; key is read once for legacy migration only (§1.2) |
| Mural method (removed/legacy) | `mural_method` | — | No longer a live setting; named engines no longer exist (§1.2) |

### 7.3a `ARDIAG overlayBasis` (logcat)

While an anchor is established, `ArRenderer.logOverlayBasisDiag` writes one line a second (and one straight
after each new anchor). It changes nothing; it exists to answer "is the design mirrored, and why".

~~~
ARDIAG overlayBasis path=capturedPlane det=1.00 xRight=0.98 yUp=0.97 zToCam=0.99 nToCam=0.99 corrApplied=true corrPending=false
~~~

- `path`: which anchor branch placed it: `hit` (screen-centre hit), `capturedPlane` (the wall the target was
  created against), `reloc` (return visit), `fallback` (no surface).
- `det`: sign of the overlay axes' determinant. Negative means a reflection is in the basis.
- `xRight`, `yUp`: cosine of the overlay's +X with camera-right and +Y with camera-up. The AR drag handler
  passes the finger's x straight through as local +X, so a negative `xRight` makes horizontal drags run backwards.
- `zToCam`, `nToCam`: cosine of the overlay's +Z and of the stored wall normal with the direction to the camera.
  The renderer assumes both are positive.
- `corrApplied` / `corrPending`: whether the one-time orientation correction has been accepted yet.

### 7.3b `ARDIAG perf` (logcat)

Two log-only lines, once a minute, for tracing a battery or heat complaint. They change nothing. They print in release builds too, tagged `graffitixr` (Kotlin) and `graffitixr.MobileGS` (native), so a capture filtered on `graffitixr` includes them; release builds print no other Timber output.

~~~
ARDIAG perf reloc: passes=292 avgPassMs=41.3 avgMatchMs=6.2 busy=20% locked=1 backoffMs=200 corrobGate=0 predicted=212 matched=57 relocReject=0
ARDIAG perf anticipation: frames=1760 avgWorkMs=11.4 searchable=246 builtThisMinute=0 renderMs=0
~~~

- `reloc` (native, the relocalization thread): `passes` per minute, `avgPassMs` for a whole pass (detect, solve,
  match), `avgMatchMs` for the progress matching inside it (the passes' gated matching and the paint grid),
  `busy` the share of the minute the thread spent inside a pass, and whether it is locked (the longer back-off) with
  the back-off in use. `corrobGate`, `predicted`, `matched` and `relocReject` are the last pass's answer to "why is
  there no painting progress": `corrobGate` 0 = the gated match ran (then `predicted` design features were in view
  and `matched` of them were found), 1 = no design registered, 2 = no design placement pushed, 3 = relocalization did
  not lock, 4 = design 2D positions do not index the descriptors, 5 = the doodle has no intrinsics, 6 = no
  attempt reached the decision. `relocReject` 0 = a pose was published, 1 = no doodle yet, 2 = relocalization
  off, 3 = nothing detected in the live frame, 4 = under 8 matches, 5 = PnP found no pose, 6 = under 6 inliers.
- `anticipation` (Kotlin, the tracking thread): `frames` seen, `avgWorkMs` the tracking work per frame, `searchable`
  the anticipated views the tracker is searching, `builtThisMinute` and `renderMs` the views rendered this minute and
  the time that took (non-zero only while the build is running).

### 7.4 Diagnostic overlays (editor toggles)

Runtime visualization toggles for debugging tracking, each independently settable from Settings ▸ Developer.
`showFeaturePoints` defaults **off** — it is a raw perception-debug visualization with no onboarding
explanation. `showPlaneGrids` and `showPoints` default **on**, matching the onboarding copy that
promises "coloured shapes appear as the engine maps the surface" as user-facing scanning feedback —
the accumulated point cloud is the visible result of that scan.

The previous text here described "method-appropriate defaults" applied via an
`ApplyMethodLayerDefaults(activeMethod)` function tied to the now-removed Mural Method setting (§1.2);
no such function exists in the current reducer. `ToggleVoxels` and `ToggleMesh` — and the underlying
confidence-voxel/splat-map and surface-mesh subsystems they would show — do not exist anywhere in the
source either (see the removed "Heat Voxel Cubes" note in `UI_UX.md`); both rows are removed below.

| Toggle | Intent | Shows |
|---|---|---|
| Diagnostics | `ToggleDiagOverlay` | Master diagnostic overlay |
| Feature points | `ToggleFeaturePoints` | Tracked feature-point cloud |
| Plane grids | `TogglePlaneGrids` | Detected plane grids |
| Points | `TogglePoints` | Raw point cloud |

See §7.2a for the separate drift-correction / self-grow / feature-map experiment switches, which live
in the same AR diagnostics overlay but are not `Toggle*` diagnostic-visibility intents — they change
relocalization behaviour, not what's drawn.

---

## 8. Co-op (peer-to-peer collaboration)

Robust LAN peer-to-peer sync for collaborative painting — **no cloud, no accounts**. Module: `collab/`
(`CollaborationManager`, `HostSession`, `GuestSession`).

- **Pairing:** host advertises a LAN endpoint; guests join by scanning a **QR code** (ZXing scanner in
  `MainActivity`; the search button launches it). `LocalIp.discover()` picks the default-route source
  address (UDP-connect trick) so pairing advertises a LAN-reachable IP on multi-interface devices
  (cellular + Wi-Fi, VPN).
- **Transport security:** protocol v2 introduced token-derived **AES-256-GCM per frame**, nonce/proof
  handshake, **no token ever on the wire**, and HKDF key derivation. Protocol **v3** keeps that
  transport and adds an explicit host wall-frame/backend/scale descriptor so cross-backend peers
  cannot silently treat unrelated coordinates as equivalent.
- **Robustness:** accept-loop survives bad handshakes; ops are lossless across reconnects (seq + encode +
  buffer at enqueue); 15 s socket read timeouts; guests re-sync on host `sessionId` change; bounded
  pre-handler spectator-op buffering. Import/spectator load is hardened against Zip-Slip.
- **Ops:** design replace/transform/props/bitmap, per-mode adjustments and the measured wall width
  (`Op.WallWidth`, protocol v5) stream as `Op`s and apply with no active-layer side effects.
- **Tripod stream (protocol v7):** the host is the camera device (tripod) and keeps all tracking and
  progress detection. A guest can act as its display: Link ▸ view (rail cycler, guest only) switches
  between the guest's own AR view and the tripod's. Choosing the tripod sends `VIDEO_WANT(true)`; the
  host's renderer then reads its composited framebuffer back about five times a second
  (`ArRenderer.streamSink`), `ArViewModel` downscales it to 960 px and JPEG-encodes it (q70) on a worker
  (one frame in flight, latest wins) and `HostSession.offerVideo` sends it as a `VIDEO` frame. Nothing
  is read back, encoded or sent while no guest wants it, and a new connection forgets the request (the
  guest re-sends it on reconnect). Transport is the same encrypted LAN link as everything else.
- **Bidirectional (protocol v4):** a guest's edits go to the host as `GUEST_OP` frames (own
  sequence, queued in `GuestOutbox` until `GUEST_OP_ACK`, resent once after a reconnect, deduped by
  the host per GuestSession instance). The host applies each as an undoable, persisted edit and
  emits it at apply time on the main thread, alongside its own edits, so its DELTA order is its
  apply order — the single total order both peers converge on when they edit concurrently (last
  write in host order wins). Refused, on both ends (`GuestOpPolicy`): choosing/replacing the design
  image (a `Layer` from a guest names a guest-side file), an AR placement between peers on
  different tracking backends (each expresses it in its own wall frame), rendered pixels
  (`DesignBitmapReplace` — the host persists source + Outline/isolation flags, so a guest's Outline
  or isolation toggle stays local), an implausible wall width (non-finite or outside (0, 100] m),
  and the authoring ops this app ignores; the guest is told once
  that such a change stays on its device. After a guest replaces its design locally, all its design
  edits stay local until the host's design returns (a host `DesignReplace` or a bulk). Edits made
  before the host's snapshot is installed are refused, not queued. Target capture is host-only (a
  guest re-target would split the spatial frames). A guest AR placement on a standalone-hosted
  session is written to the standalone slot even while the host is in another mode. A new host
  session (host restarted) clears the guest's unsent edits — the fresh bulk is the baseline. v3
  peers are rejected at the handshake.
  **Known limit:** an incoming op (the echo of the guest's previous gesture, or a host edit)
  arriving mid-gesture overrides the guest's in-progress drag.
  **Size limit:** one bulk transfer is held in memory on both ends, so Link carries projects up to
  256 MiB (`Limits.MAX_BULK_BYTES`); export streams and takes up to 512 MiB. Hosting a larger
  project says so, with both sizes, and points to export.

**Known/deferred (see `BACKLOG.md`):** a mid-bulk stall in `GuestSession` under investigation;
`LocalLoopTest.kt` real-socket timeouts are a latent CI-gate risk.

---

## 9. Languages (`AppLanguage`)

Persisted `language`. 16 options: **System Default**, English, Nederlands, Norsk, Français, Svenska,
Deutsch, Italiano, 日本語, Português, Magyar, Español, 简体中文, 繁體中文, Русский, Tagalog.
The picker is a radio group (selected row = radio button + selected semantics). Every user-facing
string in Settings, the first-run flow, the standalone overlay and target creation is a string
resource present in all 15 `values*` directories.

**ARCore-unavailable explainer.** A device without ARCore (with *Use ARCore* left on) sees a one-shot,
tap-to-advance explainer the first time it is in the editor with no modal open: AR mode runs on
standalone wall tracking (capture a textured wall patch and mark four corners), and Trace / Mockup /
Overlay are unchanged. It persists the `ar_unavailable_explainer` tutorial key on dismissal and is
never shown on ARCore devices, whose first-run walkthrough is unaffected.

---

## 10. Wearables / AI glasses (WIP)

Provider-based abstraction (`GlassesSessionState`, `Xreal*Provider`) targeting **Meta Ray-Bans** and
**Xreal Air/Ultra**. ~640 LOC of overlays + calibration exist.

**Status (deferred):** on ARCore, `glassesWorldHitForTimestamp` currently hit-tests the same
phone-screen point for source and destination, so Procrustes alignment returns identity. A real fix
needs a glasses-side world lookup (substantial native/SDK integration). Standalone refuses wearable
calibration before entering the ARCore hit-test path. Phone↔phone ARCore/SphereSLAM wall calibration
now exists in co-op protocol v3, but that does **not** provide a glasses-side world lookup or make a
KPM page frame equivalent to a glasses coordinate frame.

---

## 11. Export

Mode-aware; the Export rail item dispatches per mode.

| Mode | Method | Result |
|---|---|---|
| AR (ARCore) | `glReadPixels` on the composited GL framebuffer | Camera + wall-anchored overlay (what you see, minus Compose UI). With no AR renderer attached (e.g. no camera permission) nothing is exported and a localized message says so; it never falls back to a layers-only export, whose AR-space placement would be misapplied |
| AR (standalone SphereSLAM) | Not available yet | CameraX + transparent-GL compositing is not implemented yet. Export stays on the rail; tapping it shows a localized explanation (`ArRailBackendPolicy.exportDisabledReason`) rather than exporting layers-only |
| OVERLAY | CameraX `ImageCapture.takePicture` + composite layers at screen positions | Sensor still with layers on top |
| MOCKUP | Standard composite | Flattened mockup |
| TRACE | Transparent-background PNG | Line art with no backdrop fill |
| Stencil | Tiled PDF with registration marks | Print-ready multi-sheet stencil |

Every mode saves through MediaStore to Pictures/GraffitiXR (`saveBitmapToGallery`). On API 26-28 that
needs `WRITE_EXTERNAL_STORAGE` (declared with `maxSdkVersion="28"`), which is requested right before
the export starts; from API 29 nothing is asked. A refused write reports a permission-specific
message, any other failure a generic one, and a gallery row whose image could not be written is
deleted again.

YUV→RGBA conversion for AR capture uses the native `nativebridge.YuvConverter` (OpenCV
`cvtColor(COLOR_YUV2RGBA_NV21)` on ARM NEON, written into a caller-owned bitmap); the JNI descriptor is
locked by a contract test.

---

## 12. Projects & data

- **Model:** `GraffitiProject` / `LoadedProject`; managed by `ProjectManager` (`core/data`).
- **Persistence:** layers serialize with all wire-transferable fields (bitmaps excluded); custom
  serializers for `Uri`, `Offset`, `BlendMode`.
- **Import safety:** archive import and spectator load are hardened against Zip-Slip.
- **Privacy:** offline-first — 100% local processing, zero data collection, zero cloud dependency
  (network only exists inside an explicit co-op session).

---

## 13. Architecture map (for maintainers)

Strictly decoupled multi-module Clean Architecture (`settings.gradle.kts`):

| Module | Responsibility |
|---|---|
| `:app` | Navigation, camera orchestration, Hilt DI, `MainActivity` |
| `:feature:ar` | ARCore session/`ArRenderer` plus standalone CameraX + SphereSLAM/KPM AR orchestration, tracking HUD/diagnostics, and glasses session |
| `:sphereslam` | Shared calibrated artoolkitX KPM engine: asynchronous ARCore sidecar and synchronous standalone wall-page session |
| `:feature:editor` | Multi-layer manipulation, tools, GPU Liquify, stencil UI, export |
| `:feature:dashboard` | Project library, onboarding, settings screens |
| `:core:nativebridge` | Native C++ engine (`MobileGS`), JNI bridge, relocalization threads, `YuvConverter` |
| `:core:common` | Shared models (this doc's source of truth), `Op`, events |
| `:core:domain` | Use cases / domain layer |
| `:core:data` | `ProjectManager`, DataStore settings, unified data layer |
| `:core:design` | Shared Compose design system (reusable controls, overlays, `AzNavRail`) |
| `:android_collaboration_module` (`collab/`) | Peer-to-peer networking, encrypted transport, project sync |
| `:opencv` | Static OpenCV SDK |

---

## Appendix A — Every user-adjustable option at a glance

**Per active layer:** opacity · brightness · contrast · saturation · colour balance R/G/B · invert ·
image-lock · blend mode · scale · offset (pan) · rotation X/Y/Z · warp mesh · visibility · name · link.

**Per mode (whole design):** offset X/Y · scale · rotation · rotation X/Y · brightness · contrast ·
saturation · opacity · invert · transform-lock.

**Tools:** brush (size, feathering, colour, blend) · eraser · blur · heal · burn · dodge · liquify ·
colour · sketch thickness.

**Stencil:** layer count (1–3) · layer roles · tonal polarity · output size (mm) · locked dimension ·
tiled PDF.

**AR:** rotation axis · anchor boundary · tap-to-distance. (Scan mode / mural method removed — see
§1.2.)

**Settings:** adaptive rate · throttle on thermal / power-save / low-battery / lag · camera target FPS ·
depth capability · force stereo · min parallax · handedness · units · canvas background · language.

**Diagnostics:** diag overlay · feature points · plane grids · voxels · points · mesh.

**Co-op:** host/join (QR) · encrypted transport · spectator load.

---

*Source of truth for defaults/ranges: `core/common/src/main/java/com/hereliesaz/graffitixr/common/model/`
and the `core/data` DataStore. When a control's behaviour and this table disagree, the code wins — file an
issue against this doc.*
