# GraffitiXR

## Overview

This is an Android application that uses ARCore to overlay a virtual mural onto a set of real-world marker images. The application is built with Kotlin and uses OpenGL for rendering the AR content. It's designed to track multiple images and stitch them together to form a single, large-scale virtual mural.

## Features

*   **Real-time Image Tracking:** The app uses ARCore's Augmented Images API to detect and track multiple predefined marker images in the environment.
*   **Virtual Mural Overlay:** Once the markers are detected, the app renders a virtual mural that appears to be painted on the surface where the markers are located.
*   **Dynamic Mural Loading:** The mural texture and the marker images are loaded dynamically.
*   **OpenGL Rendering:** The camera feed and the virtual content are rendered using OpenGL ES 3.0.
*   **Jetpack Compose UI:** The UI components are built with Jetpack Compose.

## Project Structure

The project follows a standard Android application structure. Here are some of the key files and directories:

*   `app/src/main/java/com/hereliesaz/graffitixr/`: The root package for the application's source code.
    *   `MainActivity.kt`: The main entry point of the application. It handles UI setup, camera permissions, and the lifecycle of the AR session.
    *   `MuralRenderer.kt`: The core of the AR functionality. This class implements the `GLSurfaceView.Renderer` interface and manages the ARCore session, tracks augmented images, and renders the virtual mural and camera background.
    *   `MuralViewModel.kt`: A `ViewModel` that manages the state of the mural, including the list of markers and the mural image URI.
    *   `rendering/`: This package contains classes related to OpenGL rendering, such as shaders, textures, and geometric meshes.
*   `app/src/main/assets/`: Contains assets used by the application, such as shaders.
*   `app/build.gradle.kts`: The build script for the application module. It contains the project's dependencies.
*   `gradle/libs.versions.toml`: The version catalog for the project's dependencies.

## Dependencies

The project relies on several key libraries:

*   **ARCore:** The core AR library for tracking and environmental understanding.
*   **Jetpack Compose:** For building the user interface.
*   **Kotlin Coroutines:** For managing asynchronous operations.
*   **OpenGL ES 3.0:** For rendering.

## Getting Started

### Prerequisites

*   An ARCore-supported Android device.
*   Android Studio.
*   An understanding of Android development in Kotlin.

### Building and Running

1.  **Clone the repository:**
    ```bash
    git clone <repository-url>
    ```
2.  **Open in Android Studio:** Open the cloned project in Android Studio.
3.  **Build the project:** Android Studio should automatically sync the Gradle files and download the required dependencies. You can then build the project by going to `Build > Make Project`.
4.  **Run on a device:** Connect an ARCore-supported device and run the application.

## ARCore Credentials

For the basic functionality of this application (on-device image tracking), you **do not need any special API keys or credentials**.

However, if you wish to extend the application to use ARCore's cloud-based services, such as **Cloud Anchors** or the **Geospatial API**, you will need to:

1.  Set up a project in the [Google Cloud Console](https://console.cloud.google.com/).
2.  Enable the **ARCore API** for your project.
3.  Create and restrict an **API Key** for your Android application.

You can find more information in the official [ARCore documentation](https://developers.google.com/ar).
