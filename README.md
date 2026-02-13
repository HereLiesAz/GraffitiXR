# GraffitiXR

**GraffitiXR** is an Android augmented reality tool for street artists. It enables the design, visualization, and projection of murals onto physical walls using ARCore and a layer-based editor.

## Project Structure

The project uses a **Multi-Module Clean Architecture** to enforce separation of concerns and build speed.

### Modules
* **`:app`**: The application entry point and navigation host (`MainActivity`, `MainScreen`).
* **`:core`**:
    * **`:common`**: **Crucial.** Contains the Unified State models (`EditorUiState`, `ArUiState`) shared across features to prevent circular dependencies.
    * **`:design`**: The design system (`AzNavRail`, Theme, Components).
    * **`:data`**: Repositories and local storage.
    * **`:domain`**: Business logic and use cases.
    * **`:native`**: JNI bridges for OpenCV.
* **`:feature`**:
    * **`:ar`**: ARCore implementation, `ArView`, and `TargetCreationFlow`.
    * **`:editor`**: The canvas workspace (`MockupScreen`, `OverlayScreen`, `TraceScreen`).
    * **`:dashboard`**: Project management and settings.
* **`:opencv`**: Pre-compiled OpenCV Android SDK.

## Key Features

1.  **AR Projection:** Map images onto physical surfaces with occlusion and light estimation.
2.  **Editor:**
    * **Mockup Mode:** 2D layer composition on a static background.
    * **Trace Mode:** High-contrast edge detection for physical tracing.
    * **Overlay Mode:** Static camera overlay for quick alignment.
3.  **Surveyor:** Photogrammetry data capture (Target Creation Flow).

## Build Setup

1.  **Prerequisites:** Android Studio (Ladybug+), JDK 17, ARCore-supported device.
2.  **NDK:** Required for OpenCV and CMake tasks.
3.  **Build Variant:** `debug` (Signed with debug keystore).


## Documentation

* [**Architecture:**](docs/ARCHITECTURE.md) High-level module graph and state management.
* [**Pipeline:**](docs/PIPELINE_3D.md) **(Deep Dive)** Technical specs for SphereSLAM and Gaussian Splatting.
* [**Data Formats:**](docs/DATA_FORMATS.md) Binary layouts for Maps and Projects.
* [**Roadmap:**](docs/ROADMAP.md) Future plans.