# **A Developer's Blueprint for GraffitiXR: Integrating ARCore, Custom Rendering, and Computer Vision on Android**

## **Part I: Project Foundation and Architecture**

This document provides an exhaustive technical blueprint for the development of GraffitiXR, a specialized Android application designed for artists and designers. The application's primary function is to overlay a user-selected image onto a camera view, offering three distinct operational modes: a non-AR Image Trace mode for live tracing, a Mock-Up mode for applying perspective warps to an image on a static background, and a flagship AR Overlay mode that projects the image onto real-world surfaces using augmented reality. A key feature of the AR mode is the ability to create Image Targets at runtime from the live camera feed.

This report serves as a comprehensive developer's guide, detailing the architectural strategy, technology stack, and granular implementation steps required to build this complex, high-performance application in **Kotlin** using **Jetpack Compose**.

### **1.1. Architectural Overview & Core Principles**

The architecture of GraffitiXR is designed to be modular, scalable, and robust, accommodating the distinct requirements of its three operational modes while managing complex, resource-intensive tasks like camera operation, real-time rendering, and computer vision processing.

#### **High-Level Architecture Diagram**

The application is structured into several distinct layers, each with a clear separation of concerns:

*   **UI Layer (Jetpack Compose & ViewModels):** This layer is the user's entry point, built entirely with Jetpack Compose for a modern, declarative UI. It is responsible for managing all user interactions and reflecting the application's state. State will be managed in ViewModels and hoisted to the UI, following modern Compose best practices.
*   **Camera & AR Service Layer:** A critical abstraction layer that provides a unified interface for camera and AR session management. This service is responsible for managing the ARCore SDK for the AR Overlay mode.
*   **Rendering Engine (OpenGL ES):** A custom rendering pipeline built around Android's GLSurfaceView and GLSurfaceView.Renderer. This engine is responsible for two primary tasks: drawing the live camera feed to the screen background and rendering the user's overlay image with real-time adjustments for opacity, saturation, and contrast controlled by custom fragment shaders.
*   **Computer Vision Module (OpenCV):** An isolated module that can be used for advanced features like stabilizing the AR projection against drift.
*   **Data Layer (Repositories/Models):** This layer handles data persistence and retrieval, including loading user-selected images and managing project states.

#### **Architectural Principles**

*   **Modularity:** Each major component is designed as a self-contained unit.
*   **Lifecycle-Awareness:** Components that interact with system resources, particularly the camera, are strictly bound to the Android Activity lifecycle.
*   **Single Source of Truth:** The application's state is centralized within a shared ViewModel (`MainViewModel.kt`), using Kotlin StateFlow for reactive updates.

### **1.2. Core Technology Stack Selection & Justification**

#### **AR Framework: ARCore SDK**

ARCore is Google's platform for building augmented reality experiences. It provides robust image recognition and tracking capabilities, which are essential for GraffitiXR's core feature of creating and tracking Image Targets (Augmented Images) at runtime. This allows the user to turn any part of their environment into a trackable AR marker, providing a flexible and intuitive workflow for placing virtual content.

#### **Mandating a Custom OpenGL ES Renderer**

A pivotal requirement for GraffitiXR is the ability for users to adjust the opacity, saturation, and contrast of the overlay image in real-time. The most performant way to execute such per-pixel color transformations is on the GPU using a custom fragment shader. Therefore, a custom rendering engine built directly on OpenGL ES is a technical necessity. This gives the application complete control over the GPU rendering pipeline, allowing for the implementation of custom shaders to achieve the desired visual effects with maximum performance.

#### **Computer Vision: OpenCV for Android SDK**

OpenCV is the industry-standard library for computer vision tasks. While it was previously used for an experimental feature-matching implementation, it is no longer actively used in the rendering pipeline. It remains a dependency for potential future computer vision features.

### **1.3. Project Setup and Configuration**

#### **build.gradle(.kts) Configuration**

The application's module-level `build.gradle.kts` file is configured with the necessary dependencies, including Jetpack Compose, CameraX, OpenCV, and ARCore.

#### **AndroidManifest.xml Configuration**

The `AndroidManifest.xml` file declares the necessary permissions, primarily `android.permission.CAMERA`. It also specifies the requirement for OpenGL ES 2.0 or higher. AR-specific features are not declared as required, allowing the app to be installed on a wide range of devices.

#### **Project Structure**

A well-organized package structure is employed:

*   `com.hereliesaz.graffitixr.ui`: UI-related classes, Composables, ViewModels.
*   `com.hereliesaz.graffitixr.composables`: Specific UI components for different screens.
*   `com.hereliesaz.graffitixr.utils`: Utility classes and helper functions.
*   `com.hereliesaz.graffitixr.data`: Data models and serialization logic.

#### **Table 1.1: Core Project Dependencies**

| Category                | Dependency                               | Purpose                                                                |
| :---------------------- | :--------------------------------------- | :--------------------------------------------------------------------- |
| **UI (Jetpack Compose)**  | `androidx.activity:activity-compose`     | Integration for Jetpack Compose within an Activity.                    |
|                         | `androidx.compose.ui:ui`                 | Core Jetpack Compose UI library.                                       |
|                         | `androidx.compose.material3:material3`   | Provides Material Design 3 components.                                 |
| **CameraX**             | `androidx.camera:camera-core`, etc.      | Provides a consistent API for camera access (used in non-AR modes).    |
| **AR**                  | `com.google.ar:core`                    | The ARCore SDK for Android.                    |
| **Computer Vision**     | `libs.opencv`                            | The OpenCV for Android library for computer vision tasks.              |
| **Coroutines**          | `org.jetbrains.kotlinx:kotlinx-coroutines-android` | Library for managing asynchronous operations.                          |

## **Part II: Implementation of AR Overlay Mode**

This mode is the centerpiece of the GraffitiXR application, leveraging ARCore to project the user's artwork onto real-world surfaces.

### **2.1. Integrating ARCore**

#### **Session Management**

The `ARCoreManager.kt` class is responsible for the lifecycle management of the ARCore session.

1.  **Initialization:** The `ARCoreManager` is instantiated in `MainActivity` and passed to the `MainViewModel`.
2.  **Lifecycle Integration:** The `ARScreen` composable uses a `LifecycleEventObserver` to manage the AR session via the `ARCoreManager`. It calls `arCoreManager.resume()` on `ON_RESUME` and `arCoreManager.pause()` on `ON_PAUSE`.
3.  **Deinitialization:** The `MainActivity`'s `onDestroy` method calls `arCoreManager.close()` to clean up resources.

#### **Runtime Image Target Creation**

This is a core feature of the AR mode.

1.  **User Trigger:** The user taps a "Create Target" button in the UI (`MainScreen.kt`).
2.  **ViewModel Logic:** This action calls the `onCreateTargetClicked()` function in the `MainViewModel`.
3.  **ARCore Process:** The ViewModel takes the current screen content as a `Bitmap`, creates an `AugmentedImageDatabase` from it, and reconfigures the ARCore session to use this new database for tracking.
4.  **State Update:** The ViewModel updates the `UiState` to reflect that a target has been successfully created.

### **2.2. The Custom OpenGL ES Rendering Engine**

The application uses a custom rendering engine built on `GLSurfaceView` to gain full control over the AR scene.

#### **GLSurfaceView and Renderer in Compose**

The `ARScreen.kt` composable uses the `AndroidView` composable to embed a `GLSurfaceView` into the Jetpack Compose UI. A custom `ARCoreRenderer.kt` class is set as the renderer for this surface.

*   `onSurfaceCreated()`: Initializes the `BackgroundRenderer` and `AugmentedImageRenderer`.
*   `onSurfaceChanged()`: Sets the viewport and display rotation.
*   `onDrawFrame()`: The heart of the rendering loop. It gets the latest ARCore `Frame`, draws the background camera image, and then iterates through any tracked `AugmentedImage`s, using the `AugmentedImageRenderer` to draw content on top of them.

### **2.3. AR Persistence via OpenCV Fingerprinting**

A cornerstone of GraffitiXR's professional utility is its AR persistence. An artist can begin work at a location, save their project, and return later to find their digital artwork precisely re-anchored to the same physical spot. This is accomplished through a sophisticated "fingerprinting" process that leverages the OpenCV library, while relying on ARCore for live tracking.

#### **The Core Problem of Persistence**

ARCore's `AugmentedImageDatabase` is a powerful tool for real-time tracking, but it is a runtime object that cannot be directly serialized or saved. To persist an AR session, we must store a representation of the real-world target that can be used to reconstruct the tracking database later.

#### **Solution: OpenCV ORB Feature Extraction**

When a user designates an image as an AR target (via `onCreateTargetClicked()` in the `MainViewModel`), the following occurs:

1.  **Bitmap to Mat Conversion:** The target `Bitmap` is converted into an OpenCV `Mat` object, the standard matrix data structure used by the library.
2.  **ORB Feature Detection:** The ORB (Oriented FAST and Rotated BRIEF) algorithm, a high-performance and rotation-invariant feature detector, is used to analyze the `Mat`.
    *   `KeyPoints` are identified: These are the most visually distinct points in the image (corners, edges, etc.).
    *   `Descriptors` are computed: For each keypoint, a descriptor is a vector that describes the unique visual pattern of the pixels surrounding that point.
3.  **Serialization:** The list of `KeyPoints` and the `Mat` of descriptors together form the target's unique "fingerprint." Because these are not standard data types, they are serialized to a JSON string using custom `kotlinx.serialization` serializers (`KeyPointSerializer`, `MatSerializer`). This JSON string is then stored within the `ProjectData` object.

This fingerprint is a compact, storable, and robust representation of the visual information needed to identify the target.

#### **Reloading a Project: Reconstructing the AR Session**

When a saved project is loaded:

1.  The `ProjectData` is deserialized from JSON, restoring the OpenCV fingerprint.
2.  Crucially, the original target image `Bitmap` is also reloaded from its saved URI. The fingerprint alone is not used for tracking.
3.  The reloaded `Bitmap` is used to reconstruct a fresh `AugmentedImageDatabase` for ARCore.
4.  The ARCore session is configured with this new database, and ARCore takes over the live tracking.

This hybrid approach provides a robust and efficient solution: OpenCV creates a persistent, storable "identity" for the AR target, while ARCore handles the high-performance, real-time tracking in the live session.
