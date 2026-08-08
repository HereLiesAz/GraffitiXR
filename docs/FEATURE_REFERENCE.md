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

**Tap-to-distance:** the reticle + distance chips light up when `(isDualLensActive || currentCenterDepth > 0f)`
— i.e. on devices exposing hardware stereo depth or a valid triangulated centre depth.

### 1.3 MOCKUP wall-capture flow (`CaptureStep`)

`NONE → CAPTURE → RECTIFY → MASK → REVIEW` — grab a wall photo, rectify perspective, mask the paintable
region, review. The result becomes the static backdrop the design composes onto.

### 1.4 TRACE (lightbox) behaviour

Full-brightness surface for copying onto paper. Locks the image in place, **keeps the screen on with
brightness maxed**, and **blocks all touches until a deliberate four-tap exit** so a resting hand can't
disturb the reference. Exports a transparent-background PNG (not a solid fill).

---

## 2. The design (`Layer`)

> **TODO — likely stale beyond this note:** per §0/§1, the editor now holds exactly **one** design,
> not an ordered list of layers with an active-layer id — the older multi-layer model was removed. The
> `Layer` type itself (property table below) still names the design's per-image properties, so §2.1 is
> probably still close to accurate. §2.2's "Layer list operations" table is a different matter: a scan
> of the current reducer found no `AddLayer` / `RemoveLayer` / `ReorderLayers` / `ReplaceLayers` /
> `SetLayers` intents — the design is instead set/replaced wholesale (`SetDesign`, `SetDesignTransform`,
> `SetDesignProps`, `RestoreDesign`). §2.2 as written should be treated as unconfirmed/likely obsolete
> pending a full rewrite against the current intent set — out of scope for this pass, flagged rather
> than guessed at.

Every layer carries the full property set below; all except the runtime `bitmap` are serializable
(wire-transferable for co-op and project save).

### 2.1 Full layer property table

| Property | Default | Range / type | Effect |
|---|---|---|---|
| `name` | — | String | Display name in the Layers panel |
| `uri` | `null` | Uri | Source image location (serialized) |
| `bitmap` | `null` | Bitmap (`@Transient`) | Runtime pixels; never serialized |
| `isVisible` | `true` | Bool | Show/hide without deleting |
| `opacity` | `1.0` | `0.0–1.0` | Layer alpha |
| `brightness` | `0.0` | additive | Lighten (+) / darken (−) |
| `contrast` | `1.0` | multiplier | Contrast |
| `saturation` | `1.0` | multiplier | Colour intensity; `0` = greyscale |
| `colorBalanceR` | `1.0` | multiplier | Red channel gain |
| `colorBalanceG` | `1.0` | multiplier | Green channel gain |
| `colorBalanceB` | `1.0` | multiplier | Blue channel gain |
| `isImageLocked` | `false` | Bool | Lock transforms on this layer |
| `isSketch` | `false` | Bool | Treat as a hand sketch layer (affects draw handling) |
| `textParams` | `null` | `TextLayerParams` | Present when the layer is text (see §2.4) |
| `isLinked` | `false` | Bool | Link with the active layer so gestures move them together |
| `blendMode` | `SrcOver` | `BlendMode` | Compositing mode (see §2.3) |
| `warpMesh` | `[]` | `List<Float>` | Liquify/warp control-point mesh |
| `offset` | `(0,0)` | Offset | Pan within the canvas |
| `rotationX` | `0` | degrees | Tilt about width axis |
| `rotationY` | `0` | degrees | Tilt about height axis |
| `rotationZ` | `0` | degrees | In-plane spin |
| `scale` | `1.0` | multiplier | Uniform scale |
| `isInverted` | `false` | Bool | Colour invert |
| `stencilType` | `null` | `StencilLayerType` | Set when the layer is a generated stencil (see §5) |
| `stencilSourceId` | `null` | String | Id of the layer this stencil was built from |

### 2.2 Layer list operations

| Action (intent) | Behaviour |
|---|---|
| `ActivateLayer(id)` | Make a layer the edit target |
| `AddLayer(layer)` | Append, activate, clear the current tool; dismisses the panel unless duplicating |
| `RemoveLayer(id)` | Delete; if it was active, activate the first remaining layer |
| `ReorderLayers(order)` | Reorder by explicit id list (drag in the Layers panel) |
| `RenameLayer(id, name)` | Rename |
| `ToggleVisibility(id)` | Show/hide |
| `ReplaceLayers(layers, activeId)` | Wholesale replace (e.g. **flatten**) |
| `SetLayers(layers)` | Replace list only, leaving active id/tool untouched (undo restore, reload) |

Remote/spectator variants (`AppendLayer`, `RemoveLayerById`, `SetLayerTransformById`, `SetLayerProps`)
apply changes *by id with no active-layer side effects* — used when receiving co-op ops.

### 2.3 Blend modes (`BlendMode`)

Standard Compose/Skia blend modes are supported and serialized (`BlendModeSerializer`), default
`SrcOver`. The set includes the normal, darken (Multiply, Darken), lighten (Screen, Lighten, Plus),
contrast (Overlay, HardLight, SoftLight), and comparative (Difference, Exclusion) families, plus the
Porter-Duff compositing operators. `BrushStroke.blendModeOrdinal` defaults to `3` (= `SrcOver`).

### 2.4 Text layers (`TextLayerParams`)

Text is authored as parameters and rasterized to a bitmap (`TextRasterizer`, `RenderTextLayer` intent),
so it composites like any other layer while remaining re-editable. Font resolution uses a Google Fonts
cache (`GoogleFontCache`). Parameters cover the string, typeface, size, colour, and alignment.

---

## 3. Active-layer controls (Adjust / Transform / Color panels)

These act on the **active layer** (contrast with §1.1, which acts on the whole design per mode).

### 3.1 Visual adjustments (`ADJUST` / `ADJUSTMENTS` panel)

| Control (intent) | Default | Range | Notes |
|---|---|---|---|
| `SetOpacity` | `1.0` | `0–1` | |
| `SetBrightness` | `0.0` | additive | |
| `SetContrast` | `1.0` | multiplier | Dedicated `CurvesAdjustment` / `CurvesDialog` for fine curves |
| `SetSaturation` | `1.0` | multiplier | |
| `SetColorBalanceR/G/B` | `1.0` each | multiplier | Dedicated `ColorBalanceDialog` |
| `ToggleInvert` | `false` | — | |

Colour maths run through `ColorMatrixUtils`; curve edits through `CurvesUtil`.

### 3.2 Transform (`TRANSFORM` panel)

| Control | Default | Notes |
|---|---|---|
| `SetScale` | `1.0` | Uniform |
| `AddOffset(delta)` | `(0,0)` | **Incremental** — delta is added to current offset |
| `SetRotationX/Y/Z` | `0` | Per-axis |
| `SetLayerTransform(scale, offset, rx, ry, rz)` | — | Atomic set of all five |
| `CycleRotationAxis` | axis `Z` | Advance active 3D axis |
| `ToggleImageLock` | `false` | Lock this layer's transforms |

### 3.3 Tools (`Tool`)

Selected via `SetActiveTool`; `NONE` clears. Raster tools paint onto the active layer.

| Tool | Function |
|---|---|
| `NONE` | No tool active |
| `BRUSH` | Freehand paint (size, feathering, colour, blend) |
| `ERASER` | Erase to transparency |
| `BLUR` | Local blur |
| `HEAL` | Content-aware heal |
| `BURN` | Darken locally |
| `DODGE` | Lighten locally |
| `LIQUIFY` | GPU push/pull warp — routes through `SlamManager.applyLiquify`; writes `warpMesh` |
| `COLOR` | Colour sampling/fill; opens the picker |

**Brush parameters** (`BrushStroke`): `brushSize` (default `50`), `brushFeathering` (default `0`),
`colorArgb` (default opaque white), blend mode. Strokes are coarse-grained — emitted once on finger-lift
for compact co-op transfer. Sketch layers additionally use `SetSketchThickness` (int).

Colour controls: `ShowColorPicker` / `DismissColorPicker`, `SetActiveColor` (sets and closes),
`ToggleColorPanel`.

### 3.4 Panels (`EditorPanel`)

`NONE`, `LAYERS`, `ADJUSTMENTS`, `TRANSFORM`, `COLOR`, `ADJUST`. Managed by `ToggleAdjustPanel`,
`DismissPanel`, `ToggleColorPanel`. Any transform gesture (`BeginGesture`) auto-dismisses an open panel.

---

## 4. Background removal / segmentation

`BackgroundRemover` (+ `SubjectIsolator`) produces a cutout used both for compositing and as the input
to the stencil pipeline.

| Control | Default | Notes |
|---|---|---|
| `BeginSegmentation` / `EndSegmentation` | — | Run / finish the isolation pass |
| `SetSegmentationInfluence` | — | `0–1` blend between original and isolated result |
| `SetSegmentationPreview(bitmap)` | `null` | Live preview |
| `SetBackgroundBitmap(bitmap)` | `null` | The active backdrop (mockup wall, etc.) |

The isolated bitmap fed downstream is always the downsampled version (≤2048 px), not the full-res original.

---

## 5. Stencil generation (guided wizard)

A **layer-level tool**, not a mode. Turns any image layer into physically-cuttable, multi-layer,
tiled-PDF stencils. Topology is **OVERPAINT** — upper-layer islands are supported by surrounding sheet,
so no bridging is required.

### 5.1 Wizard steps (`StencilWizardStep`)

| Step | What happens | Available actions |
|---|---|---|
| `PICK_SOURCE` | Choose the source image layer | Select layer |
| `ISOLATE` | Run `SubjectIsolator`; confirm the cutout | Confirm / redo isolation |
| `CHOOSE_LAYERS` | Pick 1, 2, or 3 stencil layers | Set count |
| `GENERATE` | Pipeline runs (no user actions) | — (progress only) |
| `PREVIEW` | Cycle generated layers | Next/prev; rebuild |
| `EXPORT_PDF` | Set output size, tile to PDF, return | Set size/dimension; export |

The AzNavRail shows **only** the items relevant to the active step.

### 5.2 Stencil options

| Option | Default | Values | Effect |
|---|---|---|---|
| Layer count (`StencilLayerCount`) | `TWO` | 1 / 2 / 3 | How many tonal layers to cut |
| Layer roles (`StencilLayerType`) | — | `SILHOUETTE` (black, applied first) · `MIDTONE` (gray) · `HIGHLIGHT` (white, applied last) | Bottom-to-top paint order |
| Tonal polarity (`TonalPolarity`) | — | `DARK` / `LIGHT` | Subject's tonal bias |
| Output size (`outputSizeMm`) | `300` mm | float | Real-world size of the locked dimension |
| Locked dimension (`StencilOutputDimension`) | `WIDTH` | `WIDTH` / `HEIGHT` | Which dimension the size applies to |
| Total pages (`totalPageCount`) | derived | int | Recomputed whenever size changes |

Output bitmaps are ARGB_8888, white background, black content, **with registration marks** for
multi-sheet tiling. Export produces `exportedPdfUri` (share-ready) or `exportError`.

---

## 6. Relocalization & Teleological SLAM (the differentiator)

This is what "pocket-ready" means in practice. Detailed math in `RELOC_MAP_DESIGN.md`,
`SELF_GROWING_FINGERPRINT.md`, `TELEOLOGICAL_SLAM.md`, `NATIVE_ENGINE.md`.

- **Fingerprint capture.** When you lock onto a wall, the native engine (`MobileGS`, C++17) captures an
  OpenCV feature **fingerprint** of your marks: ORB/SuperPoint descriptors plus a handful of
  triangulated 3D points (`WallFeatureMap` / `Fingerprint`). No cloud, no room pre-scan.
- **Snap-back.** After tracking loss or a screen-off (pocket) event, the engine matches the live camera
  against the fingerprint and solves the pose via **PnP/RANSAC** to realign the overlay in milliseconds.
- **Teleological self-grow.** Because the intended result is known, OpenCV watches your progress and
  **extends the fingerprint from validated new marks** as you paint — so snap-back survives the original
  reference marks being painted over. Painting *tightens* the lock rather than degrading it.
- **Dual-lens awareness.** Auto-selects hardware stereo depth where a device exposes it; elsewhere falls
  back to single-camera motion-based (VIO-baseline) depth.
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
| Min parallax | `parallax_min_degrees` | float | Minimum parallax angle before triangulating depth |
| Show anchor boundary | `show_anchor_boundary` | — | Draw the anchor's boundary in AR |

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
`showFeaturePoints` and `showPoints` default **off** — they are raw perception-debug visualizations
with no onboarding explanation; `showPlaneGrids` defaults **on**, matching the onboarding copy that
promises "coloured shapes appear as the engine maps the surface" as user-facing scanning feedback.

> TODO: the previous text here described "method-appropriate defaults" applied via an
> `ApplyMethodLayerDefaults(activeMethod)` function tied to the now-removed Mural Method setting
> (§1.2). No such function currently exists in the reducer, so that mechanism has been removed from
> this doc pending confirmation of whatever (if anything) replaced it. Similarly, `ToggleVoxels` /
> `ToggleMesh` (and the underlying voxel/splat map and surface mesh they would show) were not found
> in the current reducer — the confidence-voxel and mesh subsystems have been deleted elsewhere in
> the codebase (see the removed "Heat Voxel Cubes" note in `UI_UX.md`), so treat "Voxels" and "Mesh"
> rows below as likely stale until independently reconfirmed.

| Toggle | Intent | Shows |
|---|---|---|
| Diagnostics | `ToggleDiagOverlay` | Master diagnostic overlay |
| Feature points | `ToggleFeaturePoints` | Tracked feature-point cloud |
| Plane grids | `TogglePlaneGrids` | Detected plane grids |
| Voxels *(TODO: confirm still live)* | `ToggleVoxels` | Confidence voxels (splat method) |
| Points | `TogglePoints` | Raw point cloud |
| Mesh *(TODO: confirm still live)* | `ToggleMesh` | Surface mesh (mesh method) |

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
