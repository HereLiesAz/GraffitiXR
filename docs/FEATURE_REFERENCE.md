# GraffitiXR — Complete Feature & Options Reference

> The exhaustive reference for every mode, tool, setting, and option in GraffitiXR.
> Companion to the conceptual docs (`ARCHITECTURE.md`, `NATIVE_ENGINE.md`, `TELEOLOGICAL_SLAM.md`,
> `RELOC_MAP_DESIGN.md`, `SELF_GROWING_FINGERPRINT.md`, `STENCILS.md`). Those explain *how*;
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
lens only). AR mode adds spatial anchoring: an offline **fingerprint** of your marks lets the
overlay **snap back** to the wall after tracking loss, and a **teleological** loop re-grows that
fingerprint from your progress so the anchor survives the reference being painted over. Nothing
touches the network unless you explicitly start a **co-op** session.

---

## 1. Modes (`EditorMode`)

Five operational modes. The mode is a lens; switching modes never mutates the design, only which
adjustment/anchoring lens is active.

| Mode | Purpose | Anchoring | Primary output |
|---|---|---|---|
| **AR** | Anchor the design to a real wall for painting at scale | Full — fingerprint + SLAM snap-back | On-wall overlay; composited PNG via `glReadPixels` |
| **MOCKUP** | Compose the design onto a static wall photo | None (static image) | Flattened preview image |
| **OVERLAY** | Classic non-AR tracing — reference over live camera | None (screen-space) | Sensor still + composited layers (CameraX) |
| **TRACE** | Phone-as-lightbox for copying onto paper | Locked screen-space | Transparent-background PNG |
| **DESIGN** | Where the single design is chosen/replaced and edited | N/A (canvas) | The project itself |

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
— i.e. on devices exposing hardware stereo depth or a valid triangulated centre depth. (Renamed from
`isDualLensActive`: the old flag lit as soon as the software-stereo path's buffers allocated, advertising
depth on devices that had none; `StereoDepthProvider`/`StereoProcessor` were removed and the flag collapsed
into `isHardwareStereoActive`, the only one that was ever real.)

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
| `isSubjectIsolated` | `false` | Bool | **Subject isolation** — ML Kit segmentation drops everything but the subject to transparent (see §4) |

`warpMesh`, `stencilType`, `stencilSourceId`, and `textParams` do not exist on `Layer` — Liquify,
stencil generation, and rasterized text layers have no implementing code (see §3.3, §5, §2.4).

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
| `onToggleSubjectIsolation` | `isSubjectIsolated = false` | — | Drop everything but the ML Kit-segmented subject (§4) |

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

`SubjectIsolator` (`feature/editor/.../SubjectIsolator.kt`) wraps ML Kit's `SubjectSegmentation`
client to cut the design's subject out, making everything else transparent. There is no
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

## 5. Stencil generation — removed / no implementing code

**There is no stencil feature in the current app.** `StencilWizardStep`, `StencilLayerCount`,
`StencilLayerType`, `TonalPolarity`, `StencilOutputDimension`, `outputSizeMm`, `StencilProcessor`, and
every other symbol this section previously documented do not exist anywhere in the source
(`find . -iname "*stencil*" -not -path "*/build/*"` returns only `docs/STENCILS.md`, which describes
this as a design document rather than shipped code). See README.md's changelog: stencil generation was
removed as a documented feature because it has no implementing code. `docs/STENCILS.md` should not be
read as describing a live pipeline in this app.

---

## 6. Relocalization & Teleological SLAM (the differentiator)

This is what "pocket-ready" means in practice. Detailed math in `RELOC_MAP_DESIGN.md`,
`SELF_GROWING_FINGERPRINT.md`, `TELEOLOGICAL_SLAM.md`, `NATIVE_ENGINE.md`.

- **Fingerprint capture.** When you lock onto a wall, the native engine (`MobileGS`, C++17) captures an
  OpenCV feature **fingerprint** of your marks: ORB/SuperPoint descriptors plus a handful of
  triangulated 3D points (`WallFeatureMap` / `Fingerprint`). No cloud, no room pre-scan.
- **Snap-back.** After tracking loss or a screen-off (pocket) event, the engine matches the live camera
  against the fingerprint and solves the pose via **PnP/RANSAC** to realign the overlay in milliseconds.
- **Teleological self-grow.** Because the intended result is known, OpenCV can watch your progress and
  **extend the fingerprint from validated new marks** as you paint — so snap-back survives the original
  reference marks being painted over, tightening the lock instead of degrading it.
- **These are opt-in, unvalidated A/B switches, not always-on behaviour.** `ArViewModel.evalSetFusionEnabled`
  (drift correction / corrected snap-back fusion) and `evalSetSelfGrowEnabled` (teleological self-grow)
  both **default to `false`**, are persisted (`SettingsRepository.driftCorrectionEnabled` /
  `.selfGrowEnabled`), and are surfaced as "Drift correction: ON/OFF" and "Self-grow: ON/OFF" toggles in
  the AR diagnostics overlay — see §7.4a. A third switch, **feature map** (`featureMapEnabled`, also
  default `false`), gates the persistent wall feature map (build + match); before it existed the
  underlying native flags (`SlamManager.setMapRelocEnabled`/`setMapBuildEnabled`) had no caller at all,
  so the map — and the `.gxr` project field for it — could never do anything. Turn all three on
  deliberately if you want them; out of the box, relocalization runs on the raw ARCore anchor without
  drift correction, and the fingerprint does not self-grow.
- **No dual-lens depth fallback.** `useArCoreDepthApi` is hardcoded `false` in `ArViewModel` — **no**
  device, stereo-capable or not, gets a depth estimate from the ARCore Depth API. Metric scale for
  non-stereo devices instead comes from `MetricFingerprintBuilder`'s two-keyframe triangulation, not
  from a per-pixel motion-based depth map. Devices that expose hardware stereo use it
  (`isHardwareStereoActive`, tracked for the tap-to-distance gate — §1.2); devices that don't fall back
  to that triangulated centre depth, not to a "VIO-baseline depth" image.
- **Thread safety.** `mWallDescriptors` / `mWallKeypoints3D` are mutex-guarded against races between JNI
  updates and the background PnP thread. The JNI ABI is frozen via `Fingerprint.fromNative` and locked by
  `FingerprintJniContractTest`.

**JNI contract note (maintainers):** adding fields to `Fingerprint` without updating the frozen factory
descriptor will silently return null from `nativeSetWallFingerprint`. The contract test exists to catch this.

---

## 7. Settings (persisted — `core/data` DataStore)

Every persisted key, its default, and effect. Adaptive-throttle keys default **on**.

### 7.1 Performance / perception throttle

| Setting | Key | Default | Effect |
|---|---|---|---|
| Adaptive rate | `adaptive_rate_enabled` | on | Master switch for the adaptive perception throttle |
| Throttle on thermal | `throttle_on_thermal` | on | Floor perception to 15 fps when the device is thermally stressed |
| Throttle on power-save | `throttle_on_power_save` | on | Floor to 15 fps in power-save mode |
| Throttle on low battery | `throttle_on_low_battery` | on | Floor to 15 fps on low battery |
| Throttle on lag | `throttle_on_lag` | on | Floor to 15 fps when frame lag is detected |
| Camera target FPS | `camera_target_fps` | `60` | ARCore camera capture target |

**Perception model:** world-locked perception layers render into an offscreen FBO that refreshes only on
meaningful camera-pose change or SLAM-map growth, and is composited every frame while camera passthrough,
artwork, and gestures stay at full display rate. With no throttle condition active, perception runs 30 fps;
any active condition (per the toggles above) floors it to 15 fps.

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
| Drift correction | `drift_correction_enabled` | off | "Fusion" — pulls the overlay back onto each accepted relocalization instead of riding the raw ARCore anchor |
| Self-grow | `self_grow_enabled` | off | Promotes newly validated marks into the reloc fingerprint as you paint; writes the map, so is colour-coded amber rather than green |
| Feature map | `feature_map_enabled` | off | Builds and matches against the persistent wall feature map (`WallFeatureMap`); also writes, also amber |

### 7.3 Interface / locale / canvas

| Setting | Key | Default | Effect |
|---|---|---|---|
| Handedness | `is_right_handed` | — | Which side the AzNavRail sits (one-handed use). Also `ToggleHandedness` |
| Units | `is_imperial_units` | — | Imperial vs metric for sizes/distances |
| Canvas background | `background_color` | — | `SetCanvasBackground` — colour behind the design in Design/editor |
| Language | `language` | System | One of the 15 supported languages (§9) |
| Scan mode (removed/legacy) | `ar_scan_mode` | — | No longer a live setting; key is read once for legacy migration only (§1.2) |
| Mural method (removed/legacy) | `mural_method` | — | No longer a live setting; named engines no longer exist (§1.2) |

### 7.4 Diagnostic overlays (editor toggles)

Runtime visualization toggles for debugging tracking, each independently settable from Settings.
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
- **Transport security (protocol v2):** token-derived **AES-256-GCM per frame**, nonce/proof handshake,
  **no token ever on the wire**. HKDF key derivation; frame/op codec in `collab/.../wire`.
- **Robustness:** accept-loop survives bad handshakes; ops are lossless across reconnects (seq + encode +
  buffer at enqueue); 15 s socket read timeouts; guests re-sync on host `sessionId` change; bounded
  pre-handler spectator-op buffering. Import/spectator load is hardened against Zip-Slip.
- **Ops:** layer add/remove/transform/props changes and completed brush strokes stream as `Op`s and apply
  by id on the receiver with no active-layer side effects.

**Known/deferred (see `BACKLOG.md`):** a mid-bulk stall in `GuestSession` under investigation;
`LocalLoopTest.kt` real-socket timeouts are a latent CI-gate risk.

---

## 9. Languages (`AppLanguage`)

Persisted `language`. 15 options: **System Default**, English, Nederlands, Norsk, Français, Svenska,
Deutsch, Italiano, 日本語, Português, Magyar, Español, 简体中文, 繁體中文, Tagalog.

---

## 10. Wearables / AI glasses (WIP)

Provider-based abstraction (`GlassesSessionState`, `Xreal*Provider`) targeting **Meta Ray-Bans** and
**Xreal Air/Ultra**. ~640 LOC of overlays + calibration exist.

**Status (deferred):** `glassesWorldHitForTimestamp` currently hit-tests the same phone-screen point for
source and destination, so Procrustes alignment returns identity. A real fix needs a glasses-side world
lookup (substantial native/SDK integration). Treat glasses support as experimental until that lands.

---

## 11. Export

Mode-aware; the Export rail item dispatches per mode.

| Mode | Method | Result |
|---|---|---|
| AR | `glReadPixels` on the composited GL framebuffer | Camera + wall-anchored overlay (what you see, minus Compose UI) |
| OVERLAY | CameraX `ImageCapture.takePicture` + composite layers at screen positions | Sensor still with layers on top |
| MOCKUP | Standard composite | Flattened mockup |
| TRACE | Transparent-background PNG | Line art with no backdrop fill |
| Stencil | Tiled PDF with registration marks | Print-ready multi-sheet stencil |

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
| `:feature:ar` | ARCore session, `ArRenderer`, SLAM data processing, glasses session |
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
