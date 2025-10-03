

# **A Developer's Blueprint for GraffitiXR: Integrating ARCore, Custom Rendering, and Computer Vision on Android**

## **Part I: Project Foundation and Architecture**

This document provides an exhaustive technical blueprint for the development of GraffitiXR, a specialized Android application designed for artists and designers. The application's primary function is to overlay a user-selected image onto a camera view, offering three distinct operational modes: a non-AR Image Trace mode for live tracing, a Mock-Up mode for applying perspective warps to an image on a static background, and a flagship AR Overlay mode that projects the image onto real-world surfaces using augmented reality. A central, advanced requirement involves a hybrid tracking system that leverages computer vision to stabilize the AR projection against drift, using real-world marks as a ground truth.

This report serves as a comprehensive developer's guide, detailing the architectural strategy, technology stack, and granular implementation steps required to build this complex, high-performance application.

### **1.1. Architectural Overview & Core Principles**

The architecture of GraffitiXR is designed to be modular, scalable, and robust, accommodating the distinct requirements of its three operational modes while managing complex, resource-intensive tasks like camera operation, real-time rendering, and computer vision processing.

#### **High-Level Architecture Diagram**

The application is structured into several distinct layers, each with a clear separation of concerns:

* **UI Layer (Activities/Fragments & ViewModels):** This layer is the user's entry point, responsible for managing all user interactions, displaying the main interface, and reflecting the application's state. It will contain the main Activity, Fragments for specific UI elements like slider dialogs, and ViewModels to hold and manage UI state in a lifecycle-aware manner.  
* **Camera & AR Service Layer:** A critical abstraction layer that provides a unified interface for camera and AR session management. This service will be responsible for initializing and configuring CameraX for the non-AR modes and managing the ARCore Session for the AR Overlay mode. This centralizes camera logic and simplifies state transitions between modes.  
* **Rendering Engine (OpenGL ES):** A custom rendering pipeline built around Android's GLSurfaceView and GLSurfaceView.Renderer. This engine is responsible for two primary tasks: drawing the live camera feed to the screen background and rendering the user's overlay image with real-time adjustments for opacity, saturation, and contrast controlled by custom fragment shaders.  
* **Computer Vision Module (OpenCV):** An isolated module dedicated to the advanced anchor stabilization feature. It will consume camera frames, perform feature detection and optical flow tracking using the OpenCV library, and calculate the necessary corrective data to counteract AR drift.  
* **Data Layer (Repositories/Models):** This layer handles data persistence and retrieval. Its responsibilities include managing the loading of user-selected images from the device's MediaStore and handling the saving and loading of project states.

#### **Architectural Principles**

The design of GraffitiXR is guided by several core software engineering principles to ensure a high-quality, maintainable codebase:

* **Modularity:** Each major component—UI, Camera/AR, Rendering, and Computer Vision—is designed as a self-contained unit with well-defined responsibilities and interfaces. This separation of concerns is crucial for managing complexity. For example, the Computer Vision Module will have no direct knowledge of the UI; it will simply process image data and output tracking results, which are then consumed by the Rendering Engine. This approach simplifies development, testing, and future maintenance.  
* **Lifecycle-Awareness:** Components that interact with system resources, particularly the camera and device sensors, must be strictly bound to the Android Activity/Fragment lifecycle. Failure to do so is a common source of bugs, memory leaks, and application crashes. The selection of Jetpack libraries like CameraX and ViewModel inherently promotes this principle, as they are designed to manage their own setup and teardown in response to lifecycle events.1 The ARCore  
  Session and GLSurfaceView will also be explicitly managed within the onResume() and onPause() lifecycle methods.  
* **Single Source of Truth:** The application's state—such as the active mode (IMAGE\_TRACE, MOCK\_UP, or AR\_OVERLAY), the selected image, and the current values for opacity, contrast, and saturation—will be centralized within a shared ViewModel. The UI layer will observe this state using reactive patterns (e.g., Kotlin StateFlow) and update itself automatically when the state changes. This prevents state desynchronization issues and creates a predictable, unidirectional data flow.

### **1.2. Core Technology Stack Selection & Justification**

The selection of the core technology stack is a critical architectural decision driven by the application's specific and demanding feature set. Each library and framework has been chosen to provide the necessary capabilities while adhering to modern Android development best practices.

#### **Strategic Selection of an "AR Optional" Configuration**

The application features two non-AR modes (Image Trace, Mock-Up) that are functional on any Android device with a camera, and one AR mode (AR Overlay) that requires specialized hardware and software support. Declaring the application as AR Required in the AndroidManifest.xml would instruct the Google Play Store to make the app available only on devices that support ARCore.4 This would severely limit the application's reach, preventing users who are only interested in the tracing or mock-up features from installing it.

Consequently, the application must be configured as AR Optional. This approach maximizes the potential user base but introduces a key architectural responsibility: the application itself must manage the ARCore dependency at runtime. Before the user can enter the AR Overlay mode, the application must programmatically check if the device supports ARCore using ArCoreApk.checkAvailability() and, if necessary, guide the user through the installation or update of "Google Play Services for AR" by invoking ArCoreApk.requestInstall().4 This requires a more sophisticated initialization flow with robust error handling to manage cases where AR is not available, but it is the correct strategy to ensure the application is accessible to the widest possible audience.

#### **Camera API: CameraX over Camera2**

For accessing the device's camera, Android offers two primary APIs: the low-level Camera2 API and the higher-level Jetpack library, CameraX. While Camera2 provides exhaustive, granular control over camera hardware, it is notoriously complex and requires developers to manage device-specific configurations and edge cases manually.2

For GraffitiXR, CameraX is the superior choice. As a Jetpack library, it is designed for ease of use, provides a consistent API across a vast number of Android devices (API level 21+), and abstracts away much of the underlying complexity.7 Its lifecycle-aware nature is a significant advantage in an application with complex state transitions, as it automatically handles the opening, closing, and session management of the camera in coordination with the app's lifecycle, drastically reducing boilerplate code and potential for resource leaks.3 Furthermore, the CameraX

ImageAnalysis use case provides a streamlined, high-performance pipeline for accessing camera frames for real-time processing.10 This is indispensable for the advanced anchor stabilization feature, which requires feeding the camera stream directly into the OpenCV module for analysis.11

#### **AR Framework: ARCore SDK (Jetpack XR)**

Google's ARCore is the definitive platform for building augmented reality experiences on Android. It provides the essential capabilities required for GraffitiXR, including motion tracking (to understand the device's position in the world), environmental understanding (to detect surfaces like walls and floors), and light estimation (to realistically light virtual objects).14

The implementation will specifically utilize the modern androidx.xr:arcore artifacts, also known as ARCore for Jetpack XR. This version of the SDK offers several advantages over the legacy com.google.ar:core library, including Kotlin-first, asynchronous APIs (using coroutines), and deeper integration with the broader Android Jetpack ecosystem, aligning with contemporary Android development standards.16

#### **Mandating a Custom OpenGL ES Renderer over High-Level Abstractions**

A pivotal requirement for GraffitiXR is the ability for users to adjust the opacity, saturation, and contrast of the overlay image in real-time via UI sliders. While opacity can be handled by standard rendering techniques, saturation and contrast are per-pixel color transformations. The most performant way to execute such operations in real-time is on the Graphics Processing Unit (GPU) using a custom fragment shader.19

High-level AR rendering libraries, such as the deprecated Sceneform or its community-maintained successor SceneView 20, are designed to simplify the process of rendering 3D models or standard Android

Views (via ViewRenderable 21). They achieve this by abstracting away the complexities of the underlying OpenGL ES pipeline. However, this abstraction makes it difficult, if not impossible, to inject custom fragment shaders and pass dynamic

uniform variables (like slider values) to them for real-time visual effects. Attempting to force this functionality into such a high-level framework would be counterproductive.

Therefore, a custom rendering engine built directly on OpenGL ES is not merely an option but a technical necessity. The foundational ARCore sample applications (hello\_ar\_java and hello\_ar\_kotlin) demonstrate this low-level approach, using GLSurfaceView to render both the camera preview and virtual objects.24 By adopting this architecture, the application gains direct and complete control over the GPU rendering pipeline. This allows for the implementation of a custom fragment shader that can receive opacity, saturation, and contrast values from the UI and apply these transformations to the overlay image with maximum performance, fulfilling a core product requirement.25

#### **Computer Vision: OpenCV for Android SDK**

The requirement to stabilize the AR projection using user-placed, real-world marks is the application's most technically challenging feature. ARCore's Simultaneous Localization and Mapping (SLAM) algorithm, while powerful, is susceptible to tracking errors that accumulate over time, causing virtual objects (and their anchors) to "drift" from their intended physical location.26

To counteract this, a secondary, more precise tracking system is needed. OpenCV (Open Computer Vision) is the industry-standard library for such tasks. The OpenCV for Android SDK provides a comprehensive suite of algorithms for feature detection (such as ORB, SIFT, and FAST), feature description, and motion tracking (optical flow).27 By integrating OpenCV, GraffitiXR can implement a sophisticated hybrid system. ARCore will provide the primary world tracking, while OpenCV will be used to detect and track the specific, high-contrast marks placed by the user. The data from OpenCV's tracking will then be used to calculate and apply real-time micro-corrections to the pose of the rendered overlay, effectively locking it to the physical marks and creating a highly stable AR experience. This advanced integration of AR and CV is a key differentiator for the application.31

### **1.3. Project Setup and Configuration**

A correct initial project setup is crucial for a smooth development process. This involves configuring the Gradle build scripts, the Android manifest, and establishing a logical project structure.

#### **build.gradle(.kts) Configuration**

The application's module-level build.gradle file must be configured with the appropriate settings and dependencies.

* **SDK Versions:** The minSdkVersion must be set to at least 24 (Android 7.0 Nougat). Although CameraX supports API 21+, ARCore's functional requirement is API 24, and even in an AR Optional app, this is the minimum level at which AR features can be activated.4 The  
  targetSdkVersion and compileSdkVersion should be set to the latest stable Android API level.  
* **Build Features:** ViewBinding should be enabled to facilitate safe and easy interaction with UI layout files. Java 8+ language features are also required.  
  Groovy  
  android {  
      //...  
      compileOptions {  
          sourceCompatibility JavaVersion.VERSION\_1\_8  
          targetCompatibility JavaVersion.VERSION\_1\_8  
      }  
      kotlinOptions {  
          jvmTarget \= '1.8'  
      }  
      buildFeatures {  
          viewBinding true  
      }  
  }

* **Dependencies:** The core dependencies for the project are outlined in Table 1.1.

#### **AndroidManifest.xml Configuration**

The AndroidManifest.xml file must declare the necessary permissions, features, and metadata for the application to function correctly.

* **Permissions and Features:** The CAMERA permission is fundamental. The android.hardware.camera.ar feature must be declared but marked as not required (android:required="false") to support the "AR Optional" strategy.4  
  XML  
  \<uses-permission android:name\="android.permission.CAMERA" /\>  
  \<uses-feature android:name\="android.hardware.camera.ar" android:required\="false" /\>  
  \<uses-feature android:glEsVersion\="0x00020000" android:required\="true" /\>

* **ARCore Metadata:** The application tag must include the metadata entry specifying that ARCore is optional. This is the key declaration that prevents the Play Store from filtering the app on non-AR devices.4  
  XML  
  \<application...\>  
      \<meta-data android:name\="com.google.ar.core" android:value\="optional" /\>  
     ...  
  \</application\>

* **User Privacy Disclosure:** As required by the ARCore terms of service, a disclosure regarding data collection by Google Play Services for AR must be included in the application, typically on a main menu or notice screen. The manifest does not contain this, but it is a required part of the application's setup.36

#### **Project Structure**

A well-organized package structure will be employed to enforce the modular architecture. The root package will be com.hereliesaz.graffitixr.

* com.hereliesaz.graffitixr.ui: Contains all UI-related classes, including Activities, Fragments, ViewModels, and any custom Views like the AzNavRailView implementation.  
* com.hereliesaz.graffitixr.core: Houses application-wide components, such as the Application class, dependency injection setup, and core service definitions.  
* com.hereliesaz.graffitixr.camera: Manages all CameraX-related setup, configuration, and helper classes.  
* com.hereliesaz.graffitixr.ar: Encapsulates all ARCore-specific logic, including session management, plane detection, and anchor handling.  
* com.hereliesaz.graffitixr.rendering: Contains the GLSurfaceView, the custom GraffitiXRRenderer, shader management classes, and data structures for rendering (e.g., vertex buffers).  
* com.hereliesaz.graffitixr.cv: Isolates all OpenCV integration code, including the feature detection and tracking algorithms for stabilization.  
* com.hereliesaz.graffitixr.data: Includes data models and repository classes for accessing images from MediaStore or other data sources.  
* com.hereliesaz.graffitixr.utils: A collection of utility classes and Kotlin extension functions used throughout the application.

#### **Table 1.1: Core Project Dependencies**

A clear, centralized list of dependencies is essential for project setup and maintenance. It provides an actionable checklist for configuring the build.gradle file and serves as a quick reference for the project's technological foundation.

| Category | Dependency | Version (Latest Stable) | Purpose |
| :---- | :---- | :---- | :---- |
| **Android Jetpack** | androidx.core:core-ktx | 1.12.0+ | Core Kotlin extensions for the Android framework. |
|  | androidx.appcompat:appcompat | 1.6.1+ | Provides backward-compatible versions of UI components. |
|  | androidx.lifecycle:lifecycle-viewmodel-ktx | 2.6.2+ | Provides lifecycle-aware ViewModels with Kotlin coroutine support for state management. |
| **UI** | com.google.android.material:material | 1.10.0+ | Provides Material Design components, including sliders and bottom sheet dialogs. |
|  | androidx.constraintlayout:constraintlayout | 2.1.4+ | Advanced layout manager for creating complex and flexible UIs. |
| **CameraX** | androidx.camera:camera-core | 1.3.0+ | The core CameraX library providing the main APIs.37 |
|  | androidx.camera:camera-camera2 | 1.3.0+ | The Camera2 implementation backend for CameraX.37 |
|  | androidx.camera:camera-lifecycle | 1.3.0+ | Provides lifecycle-aware binding for CameraX use cases.37 |
|  | androidx.camera:camera-view | 1.3.0+ | Provides the PreviewView widget for simplified camera preview display.37 |
| **ARCore** | com.google.ar:core | 1.40.0+ | The official Google ARCore SDK for Android motion tracking and environmental understanding.4 |
| **Computer Vision** | com.quickbirdstudios:opencv-contrib:4.6.0 | 4.6.0 | A community-maintained distribution of the OpenCV library and its contrib modules, simplifying integration into Android projects. |
| **Coroutines** | org.jetbrains.kotlinx:kotlinx-coroutines-android | 1.7.3+ | Library for managing asynchronous operations and background tasks in Kotlin. |

## **Part II: Building the Core Application Shell: Camera and UI**

With the project architecture and foundation established, the next phase is to construct the application's interactive shell. This involves implementing the camera preview, which acts as the visual foundation for all modes, and building the primary user interface for navigation and control, centered around the AzNavRail component.

### **2.1. Implementing the Camera Service with CameraX**

A robust camera service is the backbone of GraffitiXR. Using CameraX simplifies this process by handling low-level device specifics and providing a clean, use-case-based API.

#### **Setup PreviewView**

The user interface will be built around a live camera feed. The androidx.camera.view.PreviewView is the recommended component for this purpose, as it handles the complexities of displaying a camera stream, including aspect ratio correction and surface management.35

In the main activity's layout file, activity\_main.xml, a PreviewView will be added to occupy the entire screen, serving as the base layer upon which other UI elements and the image overlay will be placed.

XML

\<androidx.constraintlayout.widget.ConstraintLayout...\>

    \<androidx.camera.view.PreviewView  
        android:id\="@+id/viewFinder"  
        android:layout\_width\="match\_parent"  
        android:layout\_height\="match\_parent" /\>

    \</androidx.constraintlayout.widget.ConstraintLayout\>

This PreviewView will be used directly in the Image Trace mode. For the AR Overlay mode, it will be hidden or replaced by the GLSurfaceView required for custom rendering.

#### **Camera Provider and Lifecycle Binding**

The core of the CameraX API revolves around the ProcessCameraProvider, which is used to bind the lifecycle of a camera to a lifecycle owner, such as an Activity or Fragment.37 This is a powerful feature that ensures the camera is properly initialized, started, stopped, and released in sync with the UI, preventing resource leaks.3

The implementation will proceed as follows:

1. In the MainActivity, an instance of ProcessCameraProvider will be retrieved asynchronously.  
2. A startCamera() method will be created. This method will be responsible for configuring and binding the necessary CameraX use cases.  
3. It will instantiate a Preview use case and connect it to the PreviewView's surface provider: preview.setSurfaceProvider(viewFinder.surfaceProvider).  
4. It will also instantiate an ImageAnalysis use case. This use case is crucial as it provides a stream of camera frames that can be processed without interrupting the preview. These frames will be fed into the OpenCV module for the stabilization feature.11  
5. Finally, it will call cameraProvider.bindToLifecycle(), passing the activity as the LifecycleOwner, a CameraSelector configured for the rear camera, and the instantiated Preview and ImageAnalysis use cases.37

Kotlin

// In MainActivity.kt  
private fun startCamera() {  
    val cameraProviderFuture \= ProcessCameraProvider.getInstance(this)

    cameraProviderFuture.addListener({  
        val cameraProvider: ProcessCameraProvider \= cameraProviderFuture.get()

        val preview \= Preview.Builder().build().also {  
            it.setSurfaceProvider(viewBinding.viewFinder.surfaceProvider)  
        }

        imageAnalysis \= ImageAnalysis.Builder()  
           .setBackpressureStrategy(ImageAnalysis.STRATEGY\_KEEP\_ONLY\_LATEST)  
           .build()  
        // The analyzer for imageAnalysis will be set later when needed

        val cameraSelector \= CameraSelector.DEFAULT\_BACK\_CAMERA

        try {  
            cameraProvider.unbindAll()  
            cameraProvider.bindToLifecycle(  
                this, cameraSelector, preview, imageAnalysis)  
        } catch(exc: Exception) {  
            Log.e(TAG, "Use case binding failed", exc)  
        }  
    }, ContextCompat.getMainExecutor(this))  
}

#### **Permissions Handling**

Accessing the camera requires explicit user permission at runtime. The modern approach for handling this is to use the ActivityResultContracts.RequestPermission API, which provides a clean, callback-based mechanism for requesting permissions and handling the user's response.35 The

startCamera() method will only be called after the android.permission.CAMERA permission has been successfully granted by the user.

#### **Utility for Frame Conversion (ImageProxy \-\> Bitmap / OpenCV Mat)**

A critical piece of infrastructure for this application is a utility for converting the ImageProxy objects provided by the ImageAnalysis use case into more universally usable formats. The ImageProxy is a wrapper around an android.media.Image and is highly efficient for in-memory access, but it is not a Bitmap or an OpenCV Mat.11

* ImageProxy to Bitmap Conversion:  
  For features that may require a standard Android Bitmap, a conversion function is necessary. The most common format from CameraX is YUV\_420\_888, which consists of three separate image planes (Y, U, and V). A robust conversion method involves:  
  1. Extracting the ByteBuffer for each of the three planes.  
  2. Copying the data from the planes into a single ByteArray in the NV21 format.  
  3. Creating a YuvImage object from the NV21 byte array and the image dimensions.  
  4. Calling yuvImage.compressToJpeg() to compress the image into a JPEG format in an in-memory ByteArrayOutputStream.  
  5. Finally, using BitmapFactory.decodeByteArray() to decode the JPEG byte array into a Bitmap object.40 This multi-step process is necessary because there is no direct path from YUV planes to a  
     Bitmap in the Android SDK.  
* ImageProxy to OpenCV Mat Conversion:  
  For the computer vision stabilization feature, converting the ImageProxy directly to an OpenCV Mat is far more efficient than the intermediate Bitmap conversion, as it avoids the costly JPEG compression/decompression cycle. The process involves:  
  1. Getting the Y, U, and V planes from the ImageProxy.  
  2. Creating three separate OpenCV Mat objects from the ByteBuffer of each plane, specifying their respective dimensions.  
  3. Using Imgproc.cvtColorTwoPlane() or a similar OpenCV function to merge the Y and UV planes into a color Mat (e.g., in RGBA or BGR format). This Mat can then be directly used by OpenCV's feature detection and tracking algorithms.

### **2.2. Constructing the User Interface with AzNavRail**

The primary navigation and control system for the application is the AzNavRail library. As the provided documentation for this library is inaccessible 41, and search results are unhelpful 41, the implementation will be based on a functional interpretation of the user's description. The goal is to create a flexible, custom component that mimics the described behavior and can be easily adapted or replaced if the actual library's API becomes known.

#### **State Management with ViewModel**

A MainViewModel will serve as the single source of truth for the UI's state. It will manage the current operational mode and all adjustable parameters.

Kotlin

// In a ViewModel file  
enum class AppMode {  
    IMAGE\_TRACE,  
    MOCK\_UP,  
    AR\_OVERLAY  
}

class MainViewModel : ViewModel() {  
    private val \_currentMode \= MutableStateFlow(AppMode.IMAGE\_TRACE)  
    val currentMode: StateFlow\<AppMode\> \= \_currentMode.asStateFlow()

    // Other state variables for opacity, saturation, etc.  
    private val \_opacity \= MutableStateFlow(1.0f)  
    val opacity: StateFlow\<Float\> \= \_opacity.asStateFlow()

    fun setMode(mode: AppMode) {  
        \_currentMode.value \= mode  
    }

    fun setOpacity(value: Float) {  
        \_opacity.value \= value  
    }  
    //... other setters  
}

#### **Creating a Custom AzNavRailView**

To simulate the described DSL-like functionality, a custom View will be created. This approach encapsulates the logic and provides a clean API for the MainActivity.

1. **Data Models:** Data classes will represent the navigation items.  
   Kotlin  
   sealed class AzRailItem(val iconRes: Int, val label: String, val onClick: () \-\> Unit)  
   class AzRailAction(iconRes: Int, label: String, onClick: () \-\> Unit) : AzRailItem(iconRes, label, onClick)  
   class AzRailToggle(iconRes: Int, label: String, val isChecked: StateFlow\<Boolean\>, onClick: () \-\> Unit) : AzRailItem(iconRes, label, onClick)

2. **Custom View:** A custom AzNavRailView will be created, likely extending LinearLayout with a vertical orientation. It will have a public function setItems(items: List\<AzRailItem\>) that clears any existing views and programmatically inflates a button or toggle button for each item in the list, setting its icon, text, and onClickListener.

#### **Dynamic UI Updates**

The MainActivity will observe the currentMode StateFlow from the MainViewModel. When the mode changes, a function will be called to generate the appropriate list of AzRailItems and pass it to the AzNavRailView.

Kotlin

// In MainActivity.kt  
lifecycleScope.launch {  
    viewModel.currentMode.collect { mode \-\>  
        updateNavRail(mode)  
    }  
}

private fun updateNavRail(mode: AppMode) {  
    val items \= when (mode) {  
        AppMode.IMAGE\_TRACE \-\> buildImageTraceItems()  
        AppMode.MOCK\_UP \-\> buildMockUpItems()  
        AppMode.AR\_OVERLAY \-\> buildArOverlayItems()  
    }  
    viewBinding.azNavRail.setItems(items)  
}

This reactive pattern ensures that the navigation rail always reflects the correct controls for the current application state.

#### **Implementing Slider Dialogs**

When a user taps on an item like "Opacity," "Saturation," or "Contrast," the associated onClick lambda will trigger the display of a control panel. A BottomSheetDialogFragment is an excellent choice for this, as it provides a modern, non-intrusive UI pattern.

This dialog fragment will contain a com.google.android.material.slider.Slider widget. A TextView will be placed above the slider to act as a key, indicating the slider's function and range (e.g., "Contrast: Low \<---\> High"). As the user interacts with the slider, its OnChangeListener will call the corresponding setter method in the MainViewModel (e.g., viewModel.setContrast(slider.value)), updating the application state. The rendering layer, observing this state, will then update the visual representation of the overlay in real-time.

## **Part III: Implementation of Non-AR Modes**

These modes form the core functionality of GraffitiXR for users on all devices, providing essential tools for artists without the overhead of augmented reality.

### **3.1. Image Trace Mode (Static Device Overlay)**

In Image Trace mode, the application functions as a digital light-box. It overlays a semi-transparent image directly onto the live camera feed, allowing an artist to trace the design onto a physical surface by looking at their device's screen. This mode assumes the device is held in a fixed position, for instance, on a tripod.

#### **Image Selection**

The user journey begins with selecting an image. The "Image" item in the AzNavRail will trigger an Intent to open the system's file picker.

Kotlin

// In the lambda for the "Image" AzRailItem  
val intent \= Intent(Intent.ACTION\_GET\_CONTENT).apply {  
    type \= "image/\*"  
}  
imagePickerLauncher.launch(intent)

An ActivityResultLauncher will handle the result, receiving the Uri of the selected image. This Uri is then used to decode the image into an Android Bitmap object, which will be stored in the ViewModel and used for the overlay.

#### **Overlay Rendering (Simple Approach)**

For this non-AR mode, a full OpenGL ES rendering pipeline is unnecessary and overly complex. A simpler, more direct approach using standard Android Views is sufficient and more efficient.

An ImageView will be placed in the layout, positioned directly on top of the PreviewView. This ImageView will be responsible for displaying the user-selected Bitmap. User manipulations and property adjustments will be handled by directly modifying the ImageView's properties:

* **Opacity:** The alpha property of the ImageView can be set directly from the opacity slider's value (a float between 0.0 and 1.0).  
* **Transformations:** Standard touch gestures can be used to manipulate the overlay. A ScaleGestureDetector can be used to handle pinch-to-zoom gestures, which will modify the scaleX and scaleY properties. A custom two-finger rotation gesture detector can be implemented to modify the rotation property.

For contrast and saturation, which are not native properties of an ImageView, a ColorMatrixColorFilter can be applied. A ColorMatrix is a 5x4 matrix that can perform complex color transformations. By setting the appropriate values in the matrix, one can adjust the saturation and contrast of the ImageView's content. While this is processed on the CPU and is less performant than a GPU-based shader, it is perfectly adequate for this mode where the background (the camera preview) and the foreground are rendered by separate, optimized components.

#### **Connecting UI Controls**

The MainActivity will observe the state Flows for opacity, saturation, and contrast from the MainViewModel. Whenever a value changes (e.g., the user moves a slider), the observer will be triggered, and it will update the corresponding property on the overlay ImageView. For contrast and saturation, this involves recalculating the ColorMatrix and creating a new ColorMatrixColorFilter to apply to the ImageView. This reactive connection ensures that the user's adjustments are reflected on the screen instantly.

### **3.2. Mock-Up Mode (Static Background with Perspective Warp)**

The Mock-Up mode allows a user to visualize a mural on a surface for which they only have a static photograph. The user selects a background image (e.g., a photo of a wall) and then overlays their artwork, applying a perspective warp to make it fit the surface in the photo realistically.

#### **Workflow**

Upon entering Mock-Up mode, the live camera feed from PreviewView is hidden. The user is first prompted to select a background image, which is then displayed in a standard ImageView that fills the screen. Next, the user selects their overlay image. This overlay is not rendered in a simple ImageView, but in a custom View that supports interactive, four-corner perspective warping.

#### **Implementing Perspective Warp**

The core of this mode is the ability to perform a four-point perspective transformation. While Android's Canvas.drawBitmapMesh() can be used for image distortion, it is known to produce non-ideal results, such as creating unnatural diagonal folds across the image when transforming a simple quadrilateral.42

A more robust and mathematically precise method is to use the android.graphics.Matrix class and its setPolyToPoly() method. This function computes the 3x3 transformation matrix that maps a set of source points to a corresponding set of destination points. For a four-point perspective warp, it provides a perfect solution.42

The implementation will involve creating a custom WarpView:

1. **Class Definition:** Create a WarpView class that extends View. This view will maintain a reference to the overlay Bitmap and an array of four PointF objects, representing the current screen coordinates of the four draggable corners.  
2. **Drawing Logic:** In the onDraw() method, the transformation matrix will be calculated on every draw call.  
   * The source polygon (srcPoints) is a fixed array representing the four corners of the original, untransformed Bitmap: \[0f, 0f, bitmap.width.toFloat(), 0f, bitmap.width.toFloat(), bitmap.height.toFloat(), 0f, bitmap.height.toFloat()\].  
   * The destination polygon (dstPoints) is a dynamically updated array containing the x and y coordinates of the four draggable PointF objects.  
   * A Matrix object is configured using matrix.setPolyToPoly(srcPoints, 0, dstPoints, 0, 4). The final argument, 4, specifies that a full perspective transformation should be calculated.  
   * The overlay is drawn to the canvas using this matrix: canvas.drawBitmap(overlayBitmap, matrix, null).  
   * Small circles or handles will also be drawn at the location of each PointF to indicate to the user that they are draggable.  
3. **Interaction:** The onTouchEvent() method will be overridden to manage user interaction. It will detect when a touch event occurs near one of the four corner handles. If a drag gesture is initiated on a handle, the corresponding PointF's coordinates will be updated, and invalidate() will be called to trigger a redraw with the new perspective.

#### **State and Undo/Redo Logic**

This mode requires a more complex state management system to support undo and redo functionality.

* **Warp Toggle:** The "Warp" button in the AzNavRail will function as a toggle. When it is active, the corner handles on the WarpView are visible and can be manipulated. When the user presses the "Warp" button again, the handles disappear, and the current transformation is considered "committed."  
* **Command Stack:** A command pattern will be implemented within the ViewModel to manage the history of operations. Two stacks (e.g., LinkedLists) will be maintained: an undoStack and a redoStack.  
  * When the user commits a warp transformation or changes a property like contrast, the current state (e.g., the set of four destination points, the contrast value) is encapsulated in a "command" object and pushed onto the undoStack. The redoStack is cleared at this point.  
  * Pressing the "Undo" button pops the most recent command from the undoStack, applies the *previous* state to the view, and pushes the popped command onto the redoStack.  
  * Pressing the "Redo" button pops from the redoStack, reapplies that command's state, and pushes it back onto the undoStack.  
* **Reset:** The "Reset" button provides a clean slate by clearing both the undoStack and redoStack and restoring the overlay image to its initial, untransformed state at the center of the screen.

## **Part IV: Implementation of AR Overlay Mode**

This mode is the centerpiece of the GraffitiXR application, transforming the device into a magic window that projects the user's artwork onto real-world surfaces. It leverages the full power of the ARCore SDK and requires a custom OpenGL ES rendering pipeline to achieve the desired visual effects and performance.

### **4.1. Integrating ARCore and Plane Detection**

The foundation of the AR experience is the ARCore Session, which manages the device's understanding of the world.

#### **Session Management**

A dedicated helper class, ARCoreSessionManager, will be created to encapsulate all ARCore-related lifecycle management. This class will be responsible for:

1. **ARCore Availability Check:** Upon initialization, it will check if ARCore is supported and installed using ArCoreApk.checkAvailability(). If not, it will manage the user flow for installing or updating "Google Play Services for AR".4  
2. **Session Creation:** Once ARCore is confirmed to be available, it will create an ARCore Session object.  
3. **Lifecycle Integration:** It will expose resume() and pause() methods that will be called from the MainActivity's onResume() and onPause() lifecycle callbacks. These methods will, in turn, call session.resume() and session.pause() to correctly manage the AR session and its resource-intensive tracking processes.45

#### **Enabling Plane Detection**

For the user to place their mural on a wall or floor, the application must first detect these surfaces. This is a core capability of ARCore's environmental understanding feature.14 When configuring the ARCore

Session, the PlaneTrackingMode must be set to HORIZONTAL\_AND\_VERTICAL. This instructs ARCore to actively look for and track both horizontal surfaces (like floors and tables) and vertical surfaces (like walls).17 Enabling this feature requires the application to hold the

android.permission.SCENE\_UNDERSTANDING\_COARSE permission, which should be requested from the user if not already granted.17

#### **Visualizing Planes**

To provide crucial feedback to the user about where they can place their artwork, the detected planes will be visually rendered. In each frame, the application will query ARCore for all tracked planes using session.getAllTrackables(Plane.class). For each plane whose tracking state is TRACKING, the renderer will:

1. Get the plane's boundary polygon using plane.getPolygon(). This returns a FloatBuffer of vertices defining the shape of the detected surface.  
2. Triangulate this polygon and render it as a semi-transparent mesh using OpenGL ES. A simple grid texture can be applied to make the surface more apparent to the user. This visual guide is a standard and effective UX pattern in AR applications, as demonstrated in many ARCore examples.24

### **4.2. The Custom OpenGL ES Rendering Engine**

As established in the architectural design, a custom rendering engine is necessary to handle the real-time image adjustments. This engine will be built using Android's GLSurfaceView and the GLSurfaceView.Renderer interface.

#### **GLSurfaceView and Renderer**

When the application transitions into AR Overlay mode, the PreviewView used for the non-AR modes will be hidden, and a GLSurfaceView will be made visible in its place. A custom class, GraffitiXRRenderer, will be created and set as the renderer for this surface. This class will implement the three key methods of the Renderer interface 25:

* onSurfaceCreated(): This method is called once when the rendering surface is created. It is the ideal place to perform one-time setup tasks, such as compiling the GLSL shaders, creating shader programs, loading textures, and initializing OpenGL state (e.g., setting the clear color and enabling blending).  
* onSurfaceChanged(): This method is called when the surface dimensions change, such as during device rotation. It is used to set the OpenGL viewport and update the projection matrix to account for the new aspect ratio.  
* onDrawFrame(): This method is called repeatedly, once for each frame to be rendered. This is the heart of the rendering loop. Inside this method, the following steps will occur in sequence:  
  1. ARCore's session is updated, and the latest device pose and environmental understanding are acquired.  
  2. The camera background is rendered. ARCore provides a highly optimized way to do this without manual texture handling.  
  3. The detected plane visualizations are drawn.  
  4. If an overlay image has been placed, its model matrix is updated based on its anchor's pose, and it is rendered using the custom shader program.

#### **Vertex Shader (ar\_overlay.vert)**

The vertex shader is responsible for positioning the vertices of the rendered objects in 3D space. For the overlay image, which will be rendered on a simple two-triangle quad, the vertex shader will be straightforward.

* **Inputs:** It will take the vertex position (a\_Position) and texture coordinates (a\_TexCoord) as attributes. It will also take the combined model-view-projection matrix (u\_MvpMatrix) as a uniform.  
* **Processing:** Its primary function is to transform the incoming vertex position by the MVP matrix to calculate its final clip-space position: gl\_Position \= u\_MvpMatrix \* a\_Position;.  
* **Outputs:** It will pass the texture coordinates through to the fragment shader via a varying variable (v\_TexCoord).

#### **Fragment Shader (ar\_overlay.frag)**

The fragment shader is executed for every pixel of the rendered quad and is where all the custom visual effects are implemented. This is the core of the custom rendering engine.

The shader will receive the interpolated texture coordinates (v\_TexCoord) from the vertex shader. It will also declare several uniform variables, which are global constants that can be set from the application's Java/Kotlin code for each frame:

* uniform sampler2D u\_Texture;: The texture containing the user's selected image.  
* uniform float u\_Opacity;: The opacity value (0.0 to 1.0) from the UI slider.  
* uniform float u\_Saturation;: The saturation multiplier (e.g., 0.0 for grayscale, 1.0 for normal, \>1.0 for oversaturated).  
* uniform float u\_Contrast;: The contrast multiplier (e.g., 1.0 for normal).

The shader's main function will execute the image adjustments in a specific order 19:

OpenGL Shading Language

\#ifdef GL\_ES  
precision mediump float;  
\#endif

varying vec2 v\_TexCoord;  
uniform sampler2D u\_Texture;  
uniform float u\_Opacity;  
uniform float u\_Saturation;  
uniform float u\_Contrast;

void main() {  
    // 1\. Sample the original color from the texture  
    vec4 color \= texture2D(u\_Texture, v\_TexCoord);

    // 2\. Apply Contrast  
    // This formula increases the distance of each color component from the midpoint (0.5)  
    color.rgb \= (color.rgb \- 0.5) \* u\_Contrast \+ 0.5;

    // 3\. Apply Saturation  
    // This formula interpolates between a grayscale version of the color and the original color  
    const vec3 luminanceWeight \= vec3(0.2125, 0.7154, 0.0721);  
    float luminance \= dot(color.rgb, luminanceWeight);  
    vec3 gray \= vec3(luminance);  
    color.rgb \= mix(gray, color.rgb, u\_Saturation);

    // 4\. Apply Opacity  
    // The final alpha is the texture's alpha multiplied by the uniform opacity  
    color.a \*= u\_Opacity;

    gl\_FragColor \= color;  
}

To enable the opacity effect, OpenGL's blending must be enabled in the onSurfaceCreated() method of the renderer. This tells the GPU how to combine the fragment shader's output color with the color already in the framebuffer (i.e., the camera background).49

Java

// In the Renderer's setup code  
GLES20.glEnable(GLES20.GL\_BLEND);  
GLES20.glBlendFunc(GLES20.GL\_SRC\_ALPHA, GLES20.GL\_ONE\_MINUS\_SRC\_ALPHA);

### **4.3. Projecting, Manipulating, and Locking the Image**

With the rendering engine in place, the final step is to handle user interaction for placing and adjusting the overlay.

* **Placement via Hit-Testing:** The application will listen for tap gestures on the GLSurfaceView. When a tap occurs, its screen coordinates are passed to ARCore's frame.hitTest(tap) method. This method performs a raycast from the tap location into the 3D scene and returns a list of intersections with any tracked geometry, primarily the detected planes.15  
* **Creating an Anchor:** If the hit test returns a valid result on a plane, an Anchor is created at the point of intersection using hitResult.createAnchor().18 An anchor represents a fixed location and orientation in the real world. ARCore continuously refines its understanding of the world and updates the pose of all anchors accordingly to keep them "stuck" to their physical locations.52  
* **Rendering the Overlay:** A simple 3D plane (a quad made of two triangles) is defined. In the onDrawFrame loop, if an anchor exists, the renderer will get the anchor's latest pose (anchor.getPose()), use it to construct a model matrix, and render the quad at that position. The user's selected image, which has been loaded into an OpenGL texture, is applied to this quad.  
* **Manipulation and Locking:**  
  * Gesture detectors for scaling (pinch-to-zoom) and rotation (two-finger twist) will be attached to the GLSurfaceView.  
  * When the overlay is in the "Unlocked" state, these gestures will modify the local transformation matrix of the rendered quad. For example, scaling will adjust the scale components of the model matrix, and rotation will apply a rotation around the axis perpendicular to the plane's surface. These transformations are relative to the parent anchor's pose.  
  * The "Lock/Unlock" toggle in the AzNavRail controls a boolean state in the ViewModel. When the user toggles it to "Lock," the gesture detectors are disabled, fixing the overlay's current size and rotation. This "Lock" action also serves as the trigger to initialize the advanced OpenCV-based stabilization process detailed in the next section.

## **Part V: Advanced Anchor Stabilization with OpenCV**

This section addresses the most technically sophisticated feature of GraffitiXR: a custom computer vision system designed to augment ARCore's tracking and eliminate anchor drift. This hybrid AR+CV approach provides an exceptionally stable "locked" mode by using user-placed physical marks as a local ground truth.

#### **The Hybrid AR+CV Stabilization Feedback Loop**

ARCore's SLAM-based motion tracking is a powerful, general-purpose system that works by identifying and tracking thousands of visually distinct "feature points" across the entire environment.14 While remarkably effective, this process can accumulate small errors over time, especially during rapid movements or in environments with repetitive textures. This accumulation of error manifests as "drift," where an

Anchor that should be fixed to a real-world spot appears to slowly move or slide.26

The core requirement to use specific, user-placed marks on the wall to "solidify" the projection's placement provides the basis for a more robust, two-tiered tracking solution. ARCore will continue to provide the primary, global tracking of the device's overall position and orientation. In parallel, a secondary, fine-grained computer vision system built with OpenCV will focus exclusively on tracking the user's high-contrast marks. This CV system does not replace ARCore; instead, it acts as a high-frequency error-correction mechanism.

The process operates as a continuous feedback loop that begins the moment the user "locks" the overlay:

1. **Reference Capture:** When the user locks the image, the application captures the current camera frame. Within a region of interest (ROI) corresponding to the projected overlay, it uses an OpenCV feature detector to identify a set of stable, trackable points (the user's marks). The initial screen-space coordinates of these points are stored as the "reference configuration."  
2. **Per-Frame Tracking:** For every subsequent frame provided by ARCore, the application uses an optical flow algorithm to track the movement of these specific reference points.  
3. **Error Calculation:** The system calculates the average 2D displacement vector between the points' original reference coordinates and their newly tracked coordinates. This vector represents the perceived drift in screen space (e.g., "the marks have shifted 5 pixels to the left and 2 pixels up").  
4. **Corrective Action:** This 2D screen-space error vector is then translated into a small, corrective 3D transformation. This transformation is applied directly to the rendered object's model matrix, effectively nudging the virtual overlay back into perfect alignment with the physical marks on the wall. This frame-by-frame correction cancels out ARCore's drift in real time, resulting in a projection that appears unshakably fixed to the surface.

### **5.1. Integrating the OpenCV SDK**

Proper integration of the OpenCV library is the first step.

* **Dependency and Loading:** The OpenCV for Android dependency, as specified in Table 1.1, will be added to the build.gradle file. To ensure the native C++ libraries (.so files) are available to the application at runtime, a call to OpenCVLoader.initDebug() will be made early in the application's lifecycle, typically in the onCreate method of the MainActivity or a custom Application class.53  
* **Frame Acquisition Bridge:** To ensure the computer vision analysis is perfectly synchronized with the AR rendering, the image data must come directly from the ARCore Frame. In the onDrawFrame method of the renderer, after updating the ARCore session, the camera image can be acquired via frame.acquireCameraImage(). This returns an android.media.Image object in YUV\_420\_888 format. A utility function, as described in Part II, will be used to convert this multi-planar YUV image into a single grayscale OpenCV Mat object, which is the required input format for most feature detection and tracking algorithms.

### **5.2. Feature Detection and Tracking Implementation**

The logic for detecting and tracking the user's marks is implemented as follows:

* **On "Lock" Event:**  
  1. When the user activates the "Lock" toggle, the stabilization process is initialized.  
  2. The current ARCore Frame is acquired, and its image is converted to a grayscale OpenCV Mat, let's call it referenceFrameMat.  
  3. A Rect defining a region of interest (ROI) is calculated. This ROI should correspond to the screen-space bounding box of the rendered virtual overlay.  
  4. Within this ROI, Imgproc.goodFeaturesToTrack is called. This function implements the Shi-Tomasi corner detection algorithm, which is excellent for finding stable points suitable for tracking.28 The detected corners are stored as a list of  
     Point objects, referencePoints.  
  5. The referenceFrameMat and referencePoints are stored for use in the next frame.  
* **On Subsequent Frames (while "Locked"):**  
  1. In each subsequent call to onDrawFrame, the new frame's image is acquired and converted to a new grayscale Mat, currentFrameMat.  
  2. The core tracking operation is performed using Video.calcOpticalFlowPyrLK. This function implements the Lucas-Kanade optical flow algorithm with pyramids, which is robust for tracking features between consecutive video frames.55  
     Java  
     // Simplified example of the optical flow call  
     MatOfPoint2f nextPts \= new MatOfPoint2f();  
     MatOfByte status \= new MatOfByte();  
     MatOfFloat err \= new MatOfFloat();  
     Video.calcOpticalFlowPyrLK(previousFrameMat, currentFrameMat, referencePoints, nextPts, status, err);

  3. The function outputs the new locations of the tracked points (nextPts) and a status vector indicating which points were successfully tracked.  
  4. The average 2D displacement vector (dx, dy) is calculated by averaging the difference between the corresponding points in referencePoints and nextPts for all successfully tracked points.  
  5. For the next iteration, currentFrameMat becomes the new previousFrameMat, and nextPts becomes the new referencePoints.

### **5.3. Calculating and Applying the Pose Correction**

The final step is to translate the calculated 2D screen-space displacement vector into a 3D correction for the rendered object.

The 2D vector (dx, dy) represents a drift in the image plane. This needs to be converted into a small translation in the 3D world space that will counteract this drift when projected back onto the screen.

A direct and effective method is to apply a corrective translation to the object's local model matrix before it is multiplied by the view and projection matrices. The magnitude of this 3D translation must be scaled based on the object's distance from the camera. A simplified approach is to map the screen-space displacement to a displacement on the plane of the anchor in world space.

1. The 2D displacement vector (dx, dy) is normalized by the screen dimensions.  
2. This normalized vector is then scaled by a factor related to the object's size and distance to approximate a corresponding world-space shift along the axes of the anchor's local coordinate system.  
3. A small translation matrix is created from this calculated 3D shift.  
4. This corrective translation matrix is multiplied with the object's local transformation matrix (which handles its scale and rotation relative to the anchor).  
5. The final, corrected model matrix is then used for rendering. This process, repeated every frame, ensures the virtual overlay remains visually locked to the physical marks on the wall, providing a stable and convincing augmented reality experience.

## **Part VI: Finalizing the Application and Future Directions**

The final stage of development involves polishing the application by implementing state persistence, optimizing for performance, and considering potential future enhancements that could expand its capabilities.

### **6.1. Saving and State Management**

A crucial feature for any creative tool is the ability to save and resume work. The "Save" item in the AzNavRail will provide this functionality.

* **Saving a Project:** When the user initiates a save, the application must serialize its current state into a persistent format, such as a JSON file stored in the app's private directory. The data to be saved will depend on the current mode:  
  * **All Modes:** The URI of the selected overlay image, the current transformation (position, scale, rotation), and the values for opacity, saturation, and contrast.  
  * **Mock-Up Mode:** In addition to the above, the URI of the background image and the precise coordinates of the four perspective warp corner points must be saved.  
  * **AR Overlay Mode:** Persisting an AR scene is more complex than saving simple properties. A standard Anchor is tied to a specific ARCore session and cannot be saved directly. The correct solution for this is to use ARCore's **Cloud Anchors** feature. When saving, the local Anchor would be "hosted," which uploads its feature map data to Google's servers and returns a unique Cloud Anchor ID.57 This ID can be saved locally. To load the project, the application would later "resolve" this ID, querying Google's servers to recreate the anchor at the same physical location, even in a new session or on a different device.59

### **6.2. Performance Considerations and Optimization**

GraffitiXR is a computationally intensive application, and maintaining a smooth user experience requires careful attention to performance.

* **Threading:** All heavy computations, especially the OpenCV feature tracking, must be performed on a background thread to avoid blocking the main UI thread and causing stuttering or "Application Not Responding" errors. A dedicated ExecutorService or Kotlin Coroutines using Dispatchers.Default should be used for all CV processing.  
* **GPU and Shader Efficiency:** The fragment shader, while powerful, is executed for millions of pixels per second. It should be kept as efficient as possible. The proposed shader for color adjustments is already computationally lightweight, using standard arithmetic and built-in functions. Complex branching (if statements) or loops should be avoided in shaders whenever possible.  
* **Memory Management:** The application will handle large Bitmap and OpenCV Mat objects. It is critical to manage this memory carefully. When a Bitmap is no longer needed, its recycle() method should be called to release its native memory. Similarly, OpenCV Mat objects should be explicitly released using their release() method to prevent memory leaks.  
* **ARCore Performance:** ARCore's tracking incurs a constant CPU cost. To optimize this, any Anchors that are no longer in use should be explicitly detached from the session using anchor.detach(). ARCore does not automatically garbage collect trackables that have anchors attached, so manual management is necessary to keep the session running efficiently.26

### **6.3. Potential Enhancements**

Once the core functionality is implemented and stable, several exciting features could be added to enhance GraffitiXR's capabilities.

* **Video Overlays:** The custom OpenGL ES renderer can be extended to support video playback. This would involve using an Android SurfaceTexture as the source for an OpenGL texture. The SurfaceTexture can be connected to a MediaPlayer, allowing video files to be projected onto surfaces in both AR and non-AR modes.  
* **Shared AR Experiences:** A full implementation of Cloud Anchors would enable truly collaborative use cases. One user could place and align a mural design in AR, and then share the Cloud Anchor ID with a collaborator. The second user could then resolve that anchor on their own device to see the exact same virtual overlay in the shared physical space, facilitating remote design reviews and teamwork.58  
* **Advanced Grid-Based Stabilization:** The current stabilization method relies on tracking arbitrary, user-placed marks. A more robust and precise system could be built using predefined fiducial markers. The OpenCV library includes a powerful aruco module for creating and detecting ArUco markers.60 An artist could print a sheet with several ArUco markers and tape it to the wall. The application could then use OpenCV to detect these markers, which provide not only precise 2D positions but also a full 3D pose (position and orientation). This highly accurate pose information could be used to create an extremely stable local coordinate system, virtually eliminating any perceptible drift.  
* **3D Model Support:** The rendering engine could be expanded beyond simple 2D images to support the loading and rendering of 3D models (e.g., in .obj or .gltf format). This would allow users to visualize 3D sculptures or installations in addition to 2D murals, significantly broadening the application's utility.

#### **Works cited**

1. The exciting aspects of Android Camera \- Android Developers Blog, accessed October 2, 2025, [https://android-developers.googleblog.com/2022/03/the-exciting-aspects-of-android-camera.html](https://android-developers.googleblog.com/2022/03/the-exciting-aspects-of-android-camera.html)  
2. The Evolution of Android Camera APIs \- Innominds, accessed October 2, 2025, [https://www.innominds.com/blog/the-evolution-of-android-camera-apis](https://www.innominds.com/blog/the-evolution-of-android-camera-apis)  
3. CameraX: Learn how to use CameraController | Husayn Hakeem | Android Developers, accessed October 2, 2025, [https://medium.com/androiddevelopers/camerax-learn-how-to-use-cameracontroller-e3ed10fffecf](https://medium.com/androiddevelopers/camerax-learn-how-to-use-cameracontroller-e3ed10fffecf)  
4. Enable AR in your Android app | ARCore | Google for Developers, accessed October 2, 2025, [https://developers.google.com/ar/develop/java/enable-arcore](https://developers.google.com/ar/develop/java/enable-arcore)  
5. Camera2 overview | Android media, accessed October 2, 2025, [https://developer.android.com/media/camera/camera2](https://developer.android.com/media/camera/camera2)  
6. Camera2 vs CameraX: A Comparison of Android Camera APIs | by Steven \- Medium, accessed October 2, 2025, [https://medium.com/@seungbae2/camera2-vs-camerax-a-comparison-of-android-camera-apis-5db2b5ff302e](https://medium.com/@seungbae2/camera2-vs-camerax-a-comparison-of-android-camera-apis-5db2b5ff302e)  
7. Choose a camera library | Android media | Android Developers, accessed October 2, 2025, [https://developer.android.com/media/camera/choose-camera-library](https://developer.android.com/media/camera/choose-camera-library)  
8. CameraX: A Simpler Approach to Camera Application Development | TO THE NEW Blog, accessed October 2, 2025, [https://www.tothenew.com/blog/camerax-a-simpler-approach-to-camera-application-development/](https://www.tothenew.com/blog/camerax-a-simpler-approach-to-camera-application-development/)  
9. To integrate a simple Camera using CameraX in a Kotlin android app. | by rohit garg, accessed October 2, 2025, [https://medium.com/@rohitgarg2016/to-integrate-a-simple-camera-using-camerax-in-a-kotlin-android-app-cefa4cfd0edc](https://medium.com/@rohitgarg2016/to-integrate-a-simple-camera-using-camerax-in-a-kotlin-android-app-cefa4cfd0edc)  
10. CameraX overview | Android media, accessed October 2, 2025, [https://developer.android.com/media/camera/camerax](https://developer.android.com/media/camera/camerax)  
11. Image analysis | Android media \- Android Developers, accessed October 2, 2025, [https://developer.android.com/media/camera/camerax/analyze](https://developer.android.com/media/camera/camerax/analyze)  
12. AI Vision on Android: CameraX ImageAnalysis \+ MediaPipe \+ Compose \- droidcon, accessed October 2, 2025, [https://www.droidcon.com/2025/01/24/ai-vision-on-android-camerax-imageanalysis-mediapipe-compose/](https://www.droidcon.com/2025/01/24/ai-vision-on-android-camerax-imageanalysis-mediapipe-compose/)  
13. Android CameraX: Preview, Analyze, Capture. | by Husayn Hakeem | ProAndroidDev, accessed October 2, 2025, [https://proandroiddev.com/android-camerax-preview-analyze-capture-1b3f403a9395](https://proandroiddev.com/android-camerax-preview-analyze-capture-1b3f403a9395)  
14. Overview of ARCore and supported development environments \- Google for Developers, accessed October 2, 2025, [https://developers.google.com/ar/develop](https://developers.google.com/ar/develop)  
15. Fundamental concepts | ARCore \- Google for Developers, accessed October 2, 2025, [https://developers.google.com/ar/develop/fundamentals](https://developers.google.com/ar/develop/fundamentals)  
16. ARCore for Jetpack XR \- Android Developers, accessed October 2, 2025, [https://developer.android.com/jetpack/androidx/releases/xr-arcore](https://developer.android.com/jetpack/androidx/releases/xr-arcore)  
17. Detect planes using ARCore for Jetpack XR \- Android Developers, accessed October 2, 2025, [https://developer.android.com/develop/xr/jetpack-xr-sdk/arcore/planes](https://developer.android.com/develop/xr/jetpack-xr-sdk/arcore/planes)  
18. Create anchors with ARCore for Jetpack XR \- Android Developers, accessed October 2, 2025, [https://developer.android.com/develop/xr/jetpack-xr-sdk/arcore/anchors](https://developer.android.com/develop/xr/jetpack-xr-sdk/arcore/anchors)  
19. yulu/GLtext: Android Project: OpenGL ES shaders for ... \- GitHub, accessed October 2, 2025, [https://github.com/yulu/GLtext](https://github.com/yulu/GLtext)  
20. SceneView is a 3D/AR Android View with ARCore and Google Filament integrated, accessed October 2, 2025, [https://sceneview.github.io/sceneform-android/sceneview.html](https://sceneview.github.io/sceneform-android/sceneview.html)  
21. Display image using ImageView in ARCore \- android \- Stack Overflow, accessed October 2, 2025, [https://stackoverflow.com/questions/54404307/display-image-using-imageview-in-arcore](https://stackoverflow.com/questions/54404307/display-image-using-imageview-in-arcore)  
22. ViewRenderable | Sceneform (1.15.0) \- Google for Developers, accessed October 2, 2025, [https://developers.google.com/sceneform/reference/com/google/ar/sceneform/rendering/ViewRenderable](https://developers.google.com/sceneform/reference/com/google/ar/sceneform/rendering/ViewRenderable)  
23. Simplifying Android Augmented Images | by Prakhar Srivastava \- Medium, accessed October 2, 2025, [https://medium.com/@prakharsrivastava\_219/simplifying-android-augmented-images-7659efd6b827](https://medium.com/@prakharsrivastava_219/simplifying-android-augmented-images-7659efd6b827)  
24. Quickstart for Android | ARCore \- Google for Developers, accessed October 2, 2025, [https://developers.google.com/ar/develop/java/quickstart](https://developers.google.com/ar/develop/java/quickstart)  
25. Displaying graphics with OpenGL ES | Views | Android Developers, accessed October 2, 2025, [https://developer.android.com/develop/ui/views/graphics/opengl](https://developer.android.com/develop/ui/views/graphics/opengl)  
26. Working with Anchors | ARCore \- Google for Developers, accessed October 2, 2025, [https://developers.google.com/ar/develop/anchors](https://developers.google.com/ar/develop/anchors)  
27. Feature Detection \- OpenCV, accessed October 2, 2025, [https://docs.opencv.org/4.x/d7/d66/tutorial\_feature\_detection.html](https://docs.opencv.org/4.x/d7/d66/tutorial_feature_detection.html)  
28. Feature Detection and Description \- OpenCV Documentation, accessed October 2, 2025, [https://docs.opencv.org/4.x/db/d27/tutorial\_py\_table\_of\_contents\_feature2d.html](https://docs.opencv.org/4.x/db/d27/tutorial_py_table_of_contents_feature2d.html)  
29. OpenCV Python \- Feature Detection \- Tutorials Point, accessed October 2, 2025, [https://www.tutorialspoint.com/opencv\_python/opencv\_python\_feature\_detection.htm](https://www.tutorialspoint.com/opencv_python/opencv_python_feature_detection.htm)  
30. Object Recognition with OpenCV on Android | by Akshika Wijesundara, PhD \- Medium, accessed October 2, 2025, [https://akshikawijesundara.medium.com/object-recognition-with-opencv-on-android-6435277ab285](https://akshikawijesundara.medium.com/object-recognition-with-opencv-on-android-6435277ab285)  
31. I Built a Fully Offline Mobile AR App in Kotlin — No ARCore, No Internet, Just OpenCV \+ OpenGL \+ ArUco Markers : r/androiddev \- Reddit, accessed October 2, 2025, [https://www.reddit.com/r/androiddev/comments/1mahwon/i\_built\_a\_fully\_offline\_mobile\_ar\_app\_in\_kotlin/](https://www.reddit.com/r/androiddev/comments/1mahwon/i_built_a_fully_offline_mobile_ar_app_in_kotlin/)  
32. EnoxSoftware/ARFoundationWithOpenCVForUnityExample: An example of converting an ARFoundation camera image to OpenCV's Mat format. \- GitHub, accessed October 2, 2025, [https://github.com/EnoxSoftware/ARFoundationWithOpenCVForUnityExample](https://github.com/EnoxSoftware/ARFoundationWithOpenCVForUnityExample)  
33. An AR based procedure to automate image labelling in the context 3D object recognition | by Alfredo Rubio | Medium, accessed October 2, 2025, [https://medium.com/@alfredo.rubio.arevalo/an-ar-based-procedure-to-automate-image-labelling-in-the-context-3d-object-recognition-a5129b55a755](https://medium.com/@alfredo.rubio.arevalo/an-ar-based-procedure-to-automate-image-labelling-in-the-context-3d-object-recognition-a5129b55a755)  
34. ARCore for Android: Quickstart Guide 2024 \- Daily.dev, accessed October 2, 2025, [https://daily.dev/blog/arcore-for-android-quickstart-guide-2024](https://daily.dev/blog/arcore-for-android-quickstart-guide-2024)  
35. Getting Started with CameraX \- Android Developers, accessed October 2, 2025, [https://developer.android.com/codelabs/camerax-getting-started](https://developer.android.com/codelabs/camerax-getting-started)  
36. google-ar/arcore-android-sdk: ARCore SDK for Android Studio \- GitHub, accessed October 2, 2025, [https://github.com/google-ar/arcore-android-sdk](https://github.com/google-ar/arcore-android-sdk)  
37. How to Create Custom Camera using CameraX in Android? \- GeeksforGeeks, accessed October 2, 2025, [https://www.geeksforgeeks.org/android/how-to-create-custom-camera-using-camerax-in-android/](https://www.geeksforgeeks.org/android/how-to-create-custom-camera-using-camerax-in-android/)  
38. Implement a preview | Android media \- Android Developers, accessed October 2, 2025, [https://developer.android.com/media/camera/camerax/preview](https://developer.android.com/media/camera/camerax/preview)  
39. Let's build an Android camera app\! CameraX \+ Compose | by Tom Colvin | ProAndroidDev, accessed October 2, 2025, [https://proandroiddev.com/lets-build-an-android-camera-app-camerax-compose-9ea47356aa80](https://proandroiddev.com/lets-build-an-android-camera-app-camerax-compose-9ea47356aa80)  
40. This Java code converts android's ImageProxy to Bitmap. \- GitHub Gist, accessed October 2, 2025, [https://gist.github.com/Ahwar/b6797f81671b2f8fb3f7cc5de3c9a5dc](https://gist.github.com/Ahwar/b6797f81671b2f8fb3f7cc5de3c9a5dc)  
41. github.com, accessed October 2, 2025, [https://github.com/HereLiesAz/AzNavRail](https://github.com/HereLiesAz/AzNavRail)  
42. jameswhite7/android\_trapezoidal\_imagewarp: android ... \- GitHub, accessed October 2, 2025, [https://github.com/jameswhite7/android\_trapezoidal\_imagewarp](https://github.com/jameswhite7/android_trapezoidal_imagewarp)  
43. image processing \- Using Android Canvas.drawBitmapMesh gives ..., accessed October 2, 2025, [https://stackoverflow.com/questions/53660429/using-android-canvas-drawbitmapmesh-gives-unexpected-bitmap-transformation](https://stackoverflow.com/questions/53660429/using-android-canvas-drawbitmapmesh-gives-unexpected-bitmap-transformation)  
44. how to apply perspective transformation to ImageProxy or Bitmap in android, accessed October 2, 2025, [https://stackoverflow.com/questions/63173146/how-to-apply-perspective-transformation-to-imageproxy-or-bitmap-in-android](https://stackoverflow.com/questions/63173146/how-to-apply-perspective-transformation-to-imageproxy-or-bitmap-in-android)  
45. Image Tracking Using ArCore in Android | by Siddharth \- Medium, accessed October 2, 2025, [https://medium.com/@sidcasm/image-tracking-using-arcore-in-android-f95daf3eee10](https://medium.com/@sidcasm/image-tracking-using-arcore-in-android-f95daf3eee10)  
46. Getting Started with Google ARCore, Part 2: Visualizing Planes & Placing Objects, accessed October 2, 2025, [https://www.andreasjakl.com/getting-started-with-google-arcore-part-2-visualizing-planes-placing-objects/](https://www.andreasjakl.com/getting-started-with-google-arcore-part-2-visualizing-planes-placing-objects/)  
47. Getting Started with OpenGL ES in Android | by AmirHossein Aghajari \- Medium, accessed October 2, 2025, [https://medium.com/@aghajari/getting-started-with-opengl-es-in-android-13249ea42300](https://medium.com/@aghajari/getting-started-with-opengl-es-in-android-13249ea42300)  
48. Android OpenGL ES: Step-by-Step Guide \- Canopas, accessed October 2, 2025, [https://canopas.com/android-opengl-es-getting-started-3925704e745a](https://canopas.com/android-opengl-es-getting-started-3925704e745a)  
49. Blending \- LearnOpenGL, accessed October 2, 2025, [https://learnopengl.com/Advanced-OpenGL/Blending](https://learnopengl.com/Advanced-OpenGL/Blending)  
50. Changing level of opacity in OpenGL ES 2.0 \- Stack Overflow, accessed October 2, 2025, [https://stackoverflow.com/questions/15693014/changing-level-of-opacity-in-opengl-es-2-0](https://stackoverflow.com/questions/15693014/changing-level-of-opacity-in-opengl-es-2-0)  
51. Build your first Android AR app using ARCore and Sceneform \- Flexiple, accessed October 2, 2025, [https://flexiple.com/android/build-your-first-android-ar-app-using-arcore-and-sceneform](https://flexiple.com/android/build-your-first-android-ar-app-using-arcore-and-sceneform)  
52. Anchor | ARCore \- Google for Developers, accessed October 2, 2025, [https://developers.google.com/ar/reference/java/com/google/ar/core/Anchor](https://developers.google.com/ar/reference/java/com/google/ar/core/Anchor)  
53. OpenCV4Android Samples \- OpenCV, accessed October 2, 2025, [https://opencv.org/opencv4android-samples/](https://opencv.org/opencv4android-samples/)  
54. Setting up AruCo for Android Studio \- Stack Overflow, accessed October 2, 2025, [https://stackoverflow.com/questions/31472919/setting-up-aruco-for-android-studio](https://stackoverflow.com/questions/31472919/setting-up-aruco-for-android-studio)  
55. Video Stabilization Using Point Feature Matching in OpenCV \- LearnOpenCV, accessed October 2, 2025, [https://learnopencv.com/video-stabilization-using-point-feature-matching-in-opencv/](https://learnopencv.com/video-stabilization-using-point-feature-matching-in-opencv/)  
56. OpenCV: image stabilization \- Stack Overflow, accessed October 2, 2025, [https://stackoverflow.com/questions/28632533/opencv-image-stabilization](https://stackoverflow.com/questions/28632533/opencv-image-stabilization)  
57. Everything about ArCloud Anchors Android — ArCore/Sceneview \- ProAndroidDev, accessed October 2, 2025, [https://proandroiddev.com/everything-about-arcloud-anchors-android-arcore-sceneview-66505bf9c7a6](https://proandroiddev.com/everything-about-arcloud-anchors-android-arcore-sceneview-66505bf9c7a6)  
58. ARCore Cloud Anchors with persistent Cloud Anchors \- Google Codelabs, accessed October 2, 2025, [https://codelabs.developers.google.com/codelabs/arcore-cloud-anchors](https://codelabs.developers.google.com/codelabs/arcore-cloud-anchors)  
59. Sample code for the ARCore Cloud Anchors Codelab \- GitHub, accessed October 2, 2025, [https://github.com/google-ar/codelab-cloud-anchors](https://github.com/google-ar/codelab-cloud-anchors)  
60. Detection of ArUco Markers \- OpenCV, accessed October 2, 2025, [https://docs.opencv.org/4.x/d5/dae/tutorial\_aruco\_detection.html](https://docs.opencv.org/4.x/d5/dae/tutorial_aruco_detection.html)  
61. Detecting ArUco markers with OpenCV and Python \- GeeksforGeeks, accessed October 2, 2025, [https://www.geeksforgeeks.org/computer-vision/detecting-aruco-markers-with-opencv-and-python-1/](https://www.geeksforgeeks.org/computer-vision/detecting-aruco-markers-with-opencv-and-python-1/)