# Application Screens

## 1. The AR Viewport (Main Screen)
There is effectively only **one** screen.
* **Background:** Live Camera Feed (GLSurfaceView).
* **Foreground:** `AzNavRail` (Compose).
* **Overlay:** 3D Splats (OpenGL).

## 2. Modes (Rail Items)
The "Screens" are actually just logic states within the AR Viewport:

### A. Scan Mode
* **Visuals:** Shows the Confidence Cloud (Green/Yellow/Red dots).
* **Controls:** Reset Map, Save Map.

### B. Project Mode
* **Visuals:** Shows the selected User Image overlaid on the map.
* **Controls:** Opacity, Blending, File Picker.

### C. Trace Mode
* **Visuals:** High-brightness, locked screen. Touch inputs disabled (except specifically defined "Unlock" gesture).
* **Purpose:** Physical tracing on paper.

## 3. Settings (Flyout)
* **Permissions:** Camera, Storage status.
* **About:** Version info.