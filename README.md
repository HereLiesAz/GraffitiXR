# GraffitiXR

GraffitiXR is an android app for street artists. There are plenty of apps that overlay an image on your camera view so you can trace it, but when I'm painting a mural based on a sketch I have saved on my phone, using a tripod can really ebb my flow. I'm all over the damn place. Me, I put my phone in my pocket. Even the apps that use AR to keep the image steady and in one place can't deal with abysmal darkness of my pocket.

So I'm making something better by repurposing the grid method. I was always thinking, "Why can't these specific doodles be saved, like a persistent anchor, so the overlay is always just plain in the right spot?" So, now, that's what those doodles do. Progress tracking replaces those marks with the piece itself as you go, like an ever-evolving fingerprinted target.

Just for shits and giggles, I included the non-AR version for image tracing just like you get with those other apps, too. Just in case you cray like that. Or if you cray-cray, there's Mockup mode. Nab a picture of the wall, cuz I got some quick tools for a quick mockup. And if you've got nothing to prove, you just want something copied onto paper perfectly, Trace mode allows you to use your phone as a lightbox, keeping your screen on with the brightness turned up, locking your image into place and blocking all touches until you're finished. 

**GraffitiXR** is an advanced augmented reality application for Android, designed to assist artists in visualizing and planning large-scale artworks such as murals. The app provides a suite of tools to project a digital image onto a real-world surface, offering a seamless workflow from concept to creation.

## Project Structure

The project uses a **Multi-Module Clean Architecture** to enforce separation of concerns and build speed.

### Modules
* **`:app`**: The application entry point, Dependency Injection (Hilt) wiring, and Navigation Graph.
* **`:core`**:
    *   **`:common`**: Universal utility classes, Kotlin extensions, and shared models (`UiState`, `DispatcherProvider`).
    *   **`:design`**: The design system (`AzNavRail`, Theme, Components like `Knob`, `InfoDialog`).
    *   **`:domain`**: Pure data models and repository interfaces (`ProjectRepository`, `SettingsRepository`).
    *   **`:data`**: Repository implementations, File I/O, and data persistence logic.
    *   **`:nativebridge`**: C++ native code (`MobileGS`, `GraffitiJNI`) integrating OpenCV and OpenGL ES.
* **`:feature`**:
    *   **`:ar`**: ARCore implementation, `ArView`, `ArViewModel`, and `TargetCreationFlow`.
    *   **`:editor`**: The canvas workspace (`MockupScreen`, `OverlayScreen`, `TraceScreen`, `EditorViewModel`).
    *   **`:dashboard`**: Project management (`ProjectLibraryScreen`), settings, and onboarding (`DashboardViewModel`).

## Key Features

1.  **AR Projection:** Map images onto physical surfaces with occlusion and light estimation.
2.  **Editor:**
    *   **Mockup Mode:** 2D layer composition on a static background.
    *   **Trace Mode:** High-contrast edge detection for physical tracing.
    *   **Overlay Mode:** Static camera overlay for quick alignment.
3.  **Surveyor:** Photogrammetry data capture (Target Creation Flow).
4.  **Local-First:** All processing is done on-device using a custom native engine.

## Technical Specifications

### 1. The Teleological Engine (MobileGS)
We use a custom C++ native engine (`MobileGS`) that implements **Teleological SLAM**.
* **Standard SLAM** asks: "Where am I based on the past?"
* **Teleological SLAM** asks: "Where am I based on the future (the art)?"
* **Capabilities:**
    * **Drift Correction:** Uses the digital overlay as a "Ground Truth" to correct drift when the physical wall changes.
    * **Voxel Pruning:** Automatically removes old feature points (bricks/cracks) when they are covered by paint, replacing them with new features from the artwork.

### 2. The Rail (AzNavRail)
A thumb-driven navigation paradigm designed for one-handed use.
* **Modes:** AR, Overlay, Mockup, Trace.
* **Design:** Layer-based editing (Opacity, Blend Modes, Transforms) accessible without blocking the view.

### 3. Architecture
* **`:core:domain`**: Pure business logic and Repository contracts.
* **`:core:data`**: Disk I/O and JSON Serialization.
* **`:core:nativebridge`**: C++17 Engine (MobileGS) implementing both Teleological SLAM (OpenCV) and Gaussian Splatting (OpenGL).
* **`:feature:*`**: Jetpack Compose UI.

## Build Setup

1.  **Prerequisites:**
    *   Android Studio (Ladybug+)
    *   JDK 17
    *   ARCore-supported device

2.  **SDK Tools:**
    *   **NDK (Side-by-side):** Required for native compilation.
    *   **CMake:** You **must** install version **3.22.1** specifically via the SDK Manager.

3.  **OpenCV Setup:**
    Before building, run the setup script to download and configure OpenCV:
    ```bash
    ./setup_libs.sh
    ```

4.  **Build Variant:** Select `debug` (Signed with debug keystore).

## Documentation

*   [**Architecture:**](docs/architecture.md) High-level module graph and state management.
*   [**Pipeline:**](docs/PIPELINE_3D.md) **(Deep Dive)** Technical specs for SphereSLAM and Gaussian Splatting.
*   [**Data Formats:**](docs/data_formats.md) Binary layouts for Maps and Projects.
*   [**Roadmap:**](docs/TODO.md) Future plans and backlog.
