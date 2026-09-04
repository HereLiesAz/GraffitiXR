# Application Screens

## 1. The AR Viewport (Main Screen)

There is effectively one screen. The background rendering layer changes based on the active mode.

### AR Mode (`EditorMode.AR`)
| Layer | Surface | Content |
|---|---|---|
| Bottom | `GLSurfaceView` (`ArRenderer`) | ARCore live camera feed via `BackgroundRenderer`, composited with the AR overlay. There is no persistent 3D map or voxel/splat rendering — see [`NATIVE_ENGINE.md`](../NATIVE_ENGINE.md). |
| Top | Compose `Canvas` | The single design image's transform and legibility overlay |
| HUD | Compose `Text` chip | Live tracking state (green=TRACKING, grey=SEARCHING) based on `arUiState.isScanning` |

ARCore owns the camera in this mode. CameraX Preview is **not** active.

### Overlay Mode (`EditorMode.OVERLAY`)
| Layer | Surface | Content |
|---|---|---|
| Bottom | `PreviewView` (CameraX) on ARCore-available devices; on the small number of devices without ARCore, a planar homography tracker over the same OpenCV pipeline | Live camera |
| Top | Compose `Canvas` | The single design image |

ARCore session is paused (where available). CameraX or the homography tracker owns the camera.

### Mockup Mode (`EditorMode.MOCKUP`)
No camera. Background is a user-selected static image (`backgroundBitmap`). Compose `Canvas` renders the design on top.

### Trace Mode (`EditorMode.TRACE`)
No camera. Full-screen design display with touch input locked (lightbox use). Volume-button sequence (Up, Down, Up, Down) exits — see `docs/UI_UX.md`.

(There is no Stencil mode — `EditorMode.STENCIL` was deleted; it auto-bounced to Mockup with no route, and stencil generation has no implementing code anywhere in the current tree. See `docs/STENCILS.md` for the historical, unimplemented design.)

---

## 2. Editor Modes (Rail Items)

The "screens" above are logic states navigated via the `AzNavRail`. The rail's own top-level
structure — three accordion hosts (Modes, Adjust, Project) plus the plain Open and Help items — is
documented in [`UI_UX.md`](../UI_UX.md); this table is only the mode list itself:

| Mode | Purpose |
|---|---|
| AR | Live scan + project image on real surface, tracked via the fingerprint relocalizer |
| Overlay | Project image over live camera, no relocalization tracking |
| Mockup | Compose on a static reference photo |
| Trace | Lightbox — image at full brightness for physical tracing |
| Design | Placement and legibility tools for the single design image (opacity, brightness, contrast, saturation, colour balance, invert, outline, subject isolation) |

---

## 3. Secondary Screens

### Project Library
Full-screen bottom sheet over the main viewport. Lists saved `.gxr` projects; supports load, delete, and new project.

### Settings (Flyout)
*   Handedness (left/right rail docking).
*   Diagnostic overlay, feature points, plane grids, points perception toggles.
*   Drift correction, self-grow, and feature-map relocalization diagnostics toggles (all off by default).
*   Crash reporting consent (opt-in, off by default).
*   Version info and update check (user-triggered GitHub API call).

---

## 4. Permission Flow

Camera and location permissions are requested together via `permissionLauncher` in `MainActivity`. `hasCameraPermission` state gates all camera-dependent rendering in `ArViewport`. Without camera permission, both AR and Overlay modes show no background.


---
*Documentation updated on 2026-09-04: removed the deleted `EditorMode.STENCIL` mode and its
multi-layer-stencil-generation description (no implementing code anywhere in the tree), removed the
false "SLAM voxel splats via `slamManager.draw()`" claim (no such method or rendering exists — see
`NATIVE_ENGINE.md`), corrected "2D editor layers" to reflect the single design image (the multi-layer
stack was removed), and pointed the rail-hierarchy description at `UI_UX.md`'s corrected version.
Prior update: 2026-03-17, website redesign and Stencil generation integration phase.*
