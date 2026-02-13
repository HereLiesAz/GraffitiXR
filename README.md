# GraffitiXR

GraffitiXR is an android app for street artists. There are plenty of apps that overlay an image on your camera view so you can trace it, but when I'm painting a mural based on a sketch I have saved on my phone, using a tripod can really ebb my flow. I'm all over the damn place. Me, I put my phone in my pocket. Even the apps that use AR to keep the image steady and in one place can't deal with abysmal darkness of my pocket.

So I'm making something better by repurposing the grid method. I was always thinking, "Why can't these specific doodles be saved, like a persistent anchor, so the overlay is always just plain in the right spot?" So, now, that's what those doodles do. Progress tracking replaces those marks with the piece itself as you go, like an ever-evolving fingerprinted target.

Just for shits and giggles, I included the non-AR version for image tracing just like you get with those other apps, too. Just in case you cray like that. Or if you cray-cray, there's Mockup mode. Nab a picture of the wall, cuz I got some quick tools for a quick mockup. And if you've got nothing to prove, you just want something copied onto paper perfectly, Trace mode allows you to use your phone as a lightbox, keeping your screen on with the brightness turned up, locking your image into place and blocking all touches until you're finished. 

**GraffitiXR** is an advanced augmented reality application for Android, designed to assist artists in visualizing and planning large-scale artworks such as murals. The app provides a suite of tools to project a digital image onto a real-world surface, offering a seamless workflow from concept to creation.

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
* [**Roadmap:**](docs/TODO.md) Future plans.
