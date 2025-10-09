# **A Developer's Blueprint for GraffitiXR: Integrating Vuforia, Custom Rendering, and Computer Vision on Android**

## **Part I: Project Foundation and Architecture**

This document provides an exhaustive technical blueprint for the development of GraffitiXR, a specialized Android application designed for artists and designers. The application's primary function is to overlay a user-selected image onto a camera view, offering three distinct operational modes: a non-AR Image Trace mode for live tracing, a Mock-Up mode for applying perspective warps to an image on a static background, and a flagship AR Overlay mode that projects the image onto real-world surfaces using augmented reality. A key feature of the AR mode is the ability to create Image Targets at runtime from the live camera feed.

This report serves as a comprehensive developer's guide, detailing the architectural strategy, technology stack, and granular implementation steps required to build this complex, high-performance application in **Kotlin** using **Jetpack Compose**.

### **1.1. Architectural Overview & Core Principles**

The architecture of GraffitiXR is designed to be modular, scalable, and robust, accommodating the distinct requirements of its three operational modes while managing complex, resource-intensive tasks like camera operation, real-time rendering, and computer vision processing.

#### **High-Level Architecture Diagram**

The application is structured into several distinct layers, each with a clear separation of concerns:

*   **UI Layer (Jetpack Compose & ViewModels):** This layer is the user's entry point, built entirely with Jetpack Compose for a modern, declarative UI. It is responsible for managing all user interactions and reflecting the application's state. State will be managed in ViewModels and hoisted to the UI, following modern Compose best practices.
*   **Camera & AR Service Layer:** A critical abstraction layer that provides a unified interface for camera and AR session management. This service is responsible for managing the Vuforia Engine for the AR Overlay mode.
*   **Rendering Engine (OpenGL ES):** A custom rendering pipeline built around Android's GLSurfaceView and GLSurfaceView.Renderer. This engine is responsible for two primary tasks: drawing the live camera feed to the screen background and rendering the user's overlay image with real-time adjustments for opacity, saturation, and contrast controlled by custom fragment shaders.
*   **Computer Vision Module (OpenCV):** An isolated module that can be used for advanced features like stabilizing the AR projection against drift.
*   **Data Layer (Repositories/Models):** This layer handles data persistence and retrieval, including loading user-selected images and managing project states.

#### **Architectural Principles**

*   **Modularity:** Each major component is designed as a self-contained unit.
*   **Lifecycle-Awareness:** Components that interact with system resources, particularly the camera, are strictly bound to the Android Activity lifecycle.
*   **Single Source of Truth:** The application's state is centralized within a shared ViewModel (`MainViewModel.kt`), using Kotlin StateFlow for reactive updates.

### **1.2. Core Technology Stack Selection & Justification**

#### **AR Framework: Vuforia Engine SDK**

The Vuforia Engine is a powerful and mature platform for building augmented reality experiences. It provides robust image recognition and tracking capabilities, which are essential for GraffitiXR's core feature of creating and tracking Image Targets at runtime. This allows the user to turn any part of their environment into a trackable AR marker, providing a flexible and intuitive workflow for placing virtual content.

#### **Mandating a Custom OpenGL ES Renderer**

A pivotal requirement for GraffitiXR is the ability for users to adjust the opacity, saturation, and contrast of the overlay image in real-time. The most performant way to execute such per-pixel color transformations is on the GPU using a custom fragment shader. Therefore, a custom rendering engine built directly on OpenGL ES is a technical necessity. This gives the application complete control over the GPU rendering pipeline, allowing for the implementation of custom shaders to achieve the desired visual effects with maximum performance.

#### **Computer Vision: OpenCV for Android SDK**

OpenCV is the industry-standard library for computer vision tasks. While not fully implemented for stabilization in the current version, its inclusion in the project provides the foundation for future enhancements, such as augmenting Vuforia's tracking to counteract drift by tracking user-placed real-world marks.

### **1.3. Project Setup and Configuration**

#### **build.gradle(.kts) Configuration**

The application's module-level `build.gradle.kts` file is configured with the necessary dependencies, including Jetpack Compose, CameraX, OpenCV, and the local `:vuforia` module which encapsulates the Vuforia Engine SDK.

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
| **AR**                  | `project(":vuforia")`                    | The Vuforia Engine SDK, included as a local module.                    |
| **Computer Vision**     | `libs.opencv`                            | The OpenCV for Android library for computer vision tasks.              |
| **Coroutines**          | `org.jetbrains.kotlinx:kotlinx-coroutines-android` | Library for managing asynchronous operations.                          |

## **Part II: Implementation of AR Overlay Mode**

This mode is the centerpiece of the GraffitiXR application, leveraging the Vuforia Engine to project the user's artwork onto real-world surfaces.

### **2.1. Integrating the Vuforia Engine**

#### **Session Management**

The `MainActivity.kt` is responsible for the lifecycle management of the Vuforia Engine.

1.  **Initialization:** In `onCreate()`, `VuforiaJNI.initAR()` is called to initialize the native Vuforia components.
2.  **Texture Loading:** Immediately after initialization, textures required by the rendering engine (e.g., for placeholder objects) are loaded from the app's assets and passed to the native layer via `VuforiaJNI.setTextures()`.
3.  **Lifecycle Integration:** The `VuforiaCameraScreen.kt` composable uses a `DisposableEffect` with a `LifecycleEventObserver` to manage the AR session. It calls `VuforiaJNI.startAR()` on `ON_RESUME` and `VuforiaJNI.stopAR()` on `ON_PAUSE`.
4.  **Deinitialization:** In `onDestroy()`, `VuforiaJNI.deinitAR()` is called to clean up resources.

#### **Runtime Image Target Creation**

This is a core feature of the AR mode.

1.  **User Trigger:** The user taps a "Create Target" button in the UI (`MainScreen.kt`).
2.  **ViewModel Logic:** This action calls the `onCreateTargetClicked()` function in the `MainViewModel`.
3.  **Native Call:** The ViewModel then invokes the native `VuforiaJNI.createImageTarget()` function.
4.  **Vuforia Process:** The native Vuforia code captures the current camera frame and processes it to create a new, trackable Image Target.
5.  **State Update:** The ViewModel updates the `UiState` to reflect that a target has been successfully created.

### **2.2. The Custom OpenGL ES Rendering Engine**

The application uses a custom rendering engine built on `GLSurfaceView` to gain full control over the AR scene.

#### **GLSurfaceView and Renderer in Compose**

The `VuforiaCameraScreen.kt` composable uses the `AndroidView` composable to embed a `GLSurfaceView` into the Jetpack Compose UI. A custom `VuforiaRenderer.kt` class is set as the renderer for this surface.

*   `onSurfaceCreated()`: Calls `VuforiaJNI.initRendering()` to set up the native rendering pipeline.
*   `onSurfaceChanged()`: Calls `VuforiaJNI.configureRendering()` to inform the native layer of the screen dimensions and orientation.
*   `onDrawFrame()`: The heart of the rendering loop. It calls the native `VuforiaJNI.renderFrame()` function on every frame. This single call is responsible for drawing the camera background and any AR content. The actual rendering logic is handled entirely in the native C++ code.

### **2.3. Projecting and Manipulating the Image**

Once an Image Target is created, the user can project their chosen artwork onto it.

*   **Image Selection:** The user selects an overlay image from their device. This triggers the `onOverlayImageSelected(uri)` function in the `MainViewModel`.
*   **Texture Preparation:** The ViewModel uses a `Texture` utility class to load the image from the provided `Uri` and convert it into a `ByteBuffer`.
*   **Passing to Native:** The ViewModel then calls the `VuforiaJNI.setOverlayTexture()` function, passing the dimensions and `ByteBuffer` of the user's image to the native rendering engine.
*   **Rendering the Overlay:** The native rendering code is responsible for detecting when an Image Target is being tracked by the camera. When a target is visible, the renderer draws the user-provided overlay image, mapped onto a 3D quad, at the target's position and orientation.
*   **Applying Visual Effects:** The custom fragment shader (implemented in native code) will eventually be used to apply real-time adjustments for opacity, saturation, and contrast, based on values passed from the UI through the JNI bridge.
