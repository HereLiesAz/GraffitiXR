# GraffitiXR

GraffitiXR is an android app for street artists. There are plenty of apps that overlay an image on your camera view so you can trace it, but when I'm painting a mural based on a sketch I have saved on my phone, using a tripod can really ebb my flow. I'm all over the damn place. Me, I put my phone in my pocket. Even the apps that use AR to keep the image steady and in one place can't deal with abysmal darkness of my pocket.

So I'm making something better by repurposing the grid method. I was always thinking, "Why can't these specific doodles be saved, like a persistent anchor, so the overlay is always just plain in the right spot?" So, now, that's what those doodles do.
Progress tracking replaces those marks with the piece itself as you go, like an ever-evolving fingerprinted target.

Just for shits and giggles, I included the non-AR version for image tracing just like you get with those other apps, too. Just in case you cray like that. Or if you cray-cray, there's Mockup mode. Nab a picture of the wall, cuz I got some quick tools for a quick mockup. And if you've got nothing to prove, you just want something copied onto paper perfectly, Trace mode allows you to use your phone as a lightbox, keeping your screen on with the brightness turned up, locking your image into place and blocking all touches until you're finished.

## The Technical Reality

GraffitiXR is a **Local-First, Offline-Capable** tool. It does not speak to the cloud. It uses a custom native engine to build confidence-based maps of the environment.

### 🧠 Neural Scan (Confidence Engine)
Powered by a custom **C++ engine (`MobileGS`)** running locally:
-   **Confidence Mapping:** The engine does not just scan once. It uses **Point Duplication** as a filter. As you move the camera, repeated detections of the same physical feature increase that voxel's "confidence score."
-   **Noise Filtration:** Splats are only rendered and persisted once they cross a confidence threshold. This filters out transient noise (people walking by, cars) and locks onto the static wall structure.

### 📂 The Project Container (`.gxr`)
Everything stays on the device. A project file is a zipped container holding:
-   **The Map:** Binary voxel data with confidence scores.
-   **The Fingerprint:** ORB/FREAK descriptors used to re-localize the map when you return to the site.
-   **The Art:** Your source image, edit history, and transparency settings.
-   **Sensor Data:** Raw GPS and IMU data for rough localization.

### 🚅 AzNavRail UI
-   **Thumb-Driven:** The entire UI is built on **AzNavRail**, placing all critical controls within thumb reach on a vertical rail.
-   **Lazy Grid:** A dynamic grid overlay that snaps to the estimated dominant plane of your confidence map, stabilizing the projection even if tracking jitters.
-   **Build Mode:** An AR blending tool allowing you to layer multiple images with various blend modes (Screen, Multiply, Overlay) and independent transforms to composite your mural plan directly on the wall.

## 🛠️ Tech Stack

* **Language:** Kotlin (UI/Logic) & C++17 (Native Core)
* **UI:** Jetpack Compose + [AzNavRail](https://github.com/HereLiesAZ/AzNavRail)
* **SLAM:** ARCore (Raw Depth/Pose) + Custom C++ Confidence Filter
* **Computer Vision:** OpenCV 4.x (Local Fingerprinting)
* **Rendering:** OpenGL ES 3.0

## 🏗️ Build Instructions

To build the project locally, you must first fetch the large dependencies (OpenCV, etc.) which are managed in a separate branch.

1.  **Fetch Dependencies:**
    Run the setup script in the root directory:
    ```bash
    # Linux / macOS
    chmod +x setup_libs.sh
    ./setup_libs.sh

    # Windows
    ./setup_libs.ps1
    ```
    This will download and configure OpenCV and other libraries into `app/libs`.

2.  **Build with Gradle:**
    Open the project in Android Studio or run via command line:
    ```bash
    ./gradlew assembleDebug
    ```

---
*Built with spray paint and code in New Orleans.*