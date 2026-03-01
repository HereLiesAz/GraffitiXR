# Application Screens

## 1. The AR Viewport (Main Screen)

There is effectively one screen. The background rendering layer changes based on the active mode.

### AR Mode (`EditorMode.AR`)
| Layer | Surface | Content |
|---|---|---|
| Bottom | `GLSurfaceView` (`ArRenderer`) | ARCore live camera feed via `BackgroundRenderer` |
| Middle | `SurfaceView` (`GsViewer`, `setZOrderMediaOverlay`) | Vulkan SLAM voxel splats |
| Top | Compose `Canvas` | 2D editor layers (bitmaps, transforms) |
| HUD | Compose `Text` chip | Live `trackingState` (green=TRACKING, orange=PAUSED, grey=other) |

ARCore owns the camera in this mode. CameraX Preview is **not** active.

### Overlay Mode (`EditorMode.OVERLAY`)
| Layer | Surface | Content |
|---|---|---|
| Bottom | `PreviewView` (CameraX) | Live camera via CameraX Preview |
| Top | Compose `Canvas` | 2D editor layers |

ARCore session is paused. CameraX owns the camera.

### Mockup Mode (`EditorMode.MOCKUP`)
No camera. Background is a user-selected static image (`backgroundBitmap`). Compose `Canvas` renders layers on top.

### Trace Mode (`EditorMode.TRACE`)
No camera. Full-screen layer display with touch input locked. Unlock gesture re-enables touch.

---

## 2. Editor Modes (Rail Items)

The "screens" are logic states navigated via the `AzNavRail`:

| Mode | Purpose |
|---|---|
| AR | Live scan + project image on real surface |
| Overlay | Project image over live camera (no SLAM) |
| Mockup | Compose on a static reference photo |
| Trace | Lightbox — image at full brightness for physical tracing |

---

## 3. Secondary Screens

### Mapping / Surveyor (`MappingActivity`)
Standalone Activity for scanning dedicated maps. Shows a live tracking chip and point count HUD. Requires camera permission; falls back gracefully if denied.

### Project Library
Full-screen bottom sheet over the main viewport. Lists saved `.gxr` projects; supports load, delete, and new project.

### Settings (Flyout)
*   Handedness (left/right rail docking).
*   Version info.
*   Update check.

---

## 4. Permission Flow

Camera and location permissions are requested together via `permissionLauncher` in `MainActivity`. `hasCameraPermission` state gates all camera-dependent rendering in `ArViewport`. Without camera permission, both AR and Overlay modes show no background.
