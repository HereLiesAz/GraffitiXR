# GraffitiXR

GraffitiXR is an android app for someone painting a mural that overlays an image onto the camera view. Include sliders to change the opacity, contrast, and saturation of the image. There should also be a button to select a new image to overlay. Use com.hereliesaz.graffitixr.

---

## What it is

This is an Android application that uses augmented reality (AR) to project an image onto a wall or other surface. It allows the user to see how a mural or other artwork would look in a real-world environment before it is created.

## What it's for

The main purpose of this application is to help artists, designers, and homeowners visualize how a mural or other large-scale artwork will look in a specific location. It can be used to test different images, sizes, and placements without the need for physical mockups.

## How it works

If AR is enabled, the application uses the device's camera, AndroidXR, and Jetpack XR Scenecore to track the environment and detect surfaces. When a surface is detected, the user can select an image from their device to project onto it. The app uses a custom rendering engine to render the image on the surface, with controls for opacity, contrast, and saturation. The user can also define the area where the mural should be projected by placing markers on the wall. The app uses these markers to calculate the correct perspective and distortion for the projected image. If AR is not enabled, then the device will need to be placed on a tripod, and simply overlays the image onto the camera view, with the same adjustable settings for the image.

## How the user interacts with it

The user interacts with the application through a simple user interface. The main screen shows the camera view with the AR overlay. There are buttons to select an image, add markers, and adjust the properties of the projected image. The user flow is as follows (* denotes AR-specific step):

1) The user points the camera at a wall or other surface.
2) The user adds markers to the wall to define the area for the mural. *
3) The user selects an image from their device.
4) The app projects the image onto the defined area.
5) The user can then adjust the opacity, contrast, and saturation of the image to see how it looks in the environment.

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