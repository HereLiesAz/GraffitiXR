# GraffitiXR

GraffitiXR is an Android application designed for artists and designers to visualize their artwork in a real-world context. The app uses augmented reality (AR) and other image processing techniques to overlay a digital image onto a camera feed or a static background, providing a powerful tool for planning murals, installations, and other creative projects.

---

## Features

GraffitiXR offers three distinct operational modes to fit various planning scenarios:

### 1. **AR Mode**
-   **Live Projection:** Uses ARCore to detect real-world surfaces and project an image onto them in real-time.
-   **Perspective Correction:** Users place four markers on a surface to define the projection area, and the app automatically calculates the correct perspective warp for the overlay image.

### 2. **Mock-up Mode**
-   **Static Image Mock-ups:** Users can select a background image from their gallery (e.g., a photo of a wall) and overlay their artwork on top of it.
-   **Manual Transformation:** Provides intuitive controls to manually adjust the four corners of the overlay image to match the perspective of the background photo. It also supports two-finger gestures for scaling and rotation.

### 3. **On-the-Go Mode**
-   **Simple Camera Overlay:** For devices without AR capabilities or for quick visualizations, this mode simply overlays the artwork on the live camera feed without any surface detection or perspective warping.

### Core Tools
-   **Image Loader:** Select any image from the device's gallery to use as an overlay or background.
-   **Background Remover:** Utilizes Google's ML Kit to automatically remove the background from an overlay image (e.g., turning a piece on a white background into a transparent overlay).
-   **Image Adjustments:** Sliders to fine-tune the overlay's **opacity**, **contrast**, and **saturation** to better match the lighting of the environment.
-   **UI Color Customization:** A color picker to change the app's UI theme.

---

## Technology Stack

-   **UI:** 100% [Jetpack Compose](https://developer.android.com/jetpack/compose) for a declarative and modern UI.
-   **Architecture:** Follows the [MVVM (Model-View-ViewModel)](https://developer.android.com/jetpack/guide) pattern with a single-activity structure.
-   **State Management:** Utilizes Kotlin's [Flow](https://developer.android.com/kotlin/flow) and `StateFlow` for reactive and centralized state management.
-   **Augmented Reality:** [ARCore](https://developers.google.com/ar) via the AndroidX XR libraries (`androidx.xr.compose`, `androidx.xr.scenecore`, `androidx.xr.runtime`).
-   **Camera:** [CameraX](https://developer.android.com/training/camerax) for a consistent and easy-to-use camera API.
-   **Image Loading:** [Coil](https://coil-kt.github.io/coil/) for efficient and flexible image loading.
-   **Machine Learning:** [Google ML Kit (Selfie Segmentation)](https://developers.google.com/ml-kit/vision/selfie-segmentation) for the background removal feature.

---

## Getting Started

### Prerequisites
-   Android Studio (latest stable version)
-   An ARCore-compatible device or emulator (for AR Mode)
-   Android SDK 34+

### Build Instructions

1.  **Clone the repository:**
    ```bash
    git clone https://github.com/your-repo/graffitixr.git
    ```
2.  **Open in Android Studio:**
    -   Open Android Studio.
    -   Select "Open an existing project".
    -   Navigate to the cloned repository directory and open it.
3.  **Build the project:**
    -   Android Studio should automatically sync the Gradle files.
    -   To build the project, click `Build > Make Project` or run the following command in the terminal:
    ```bash
    ./gradlew build
    ```
4.  **Run the application:**
    -   Select a run configuration and a target device (or emulator).
    -   Click `Run > Run 'app'`.

---

## Project Status & Roadmap

This project is under active development. For a detailed list of completed features, planned enhancements, and known issues, please refer to the [TODO.md](TODO.md) file.