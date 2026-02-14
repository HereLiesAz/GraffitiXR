# GRAFFITIXR ARCHITECTURE

## Philosophy
Local-first. Offline-dominant. Performance-critical. **Strict Offline Policy:** `INTERNET` permission is removed to ensure absolute privacy and reliable field use.

## The Core: MobileGS (Native)
The engine is a C++ implementation of Gaussian Splatting and Teleological SLAM, optimized for Android.
* **Location:** `core/nativebridge/src/main/cpp`
* **Backends:**
    * **OpenGL ES 3.0:** Default rasterization via instancing.
    * **Vulkan (Experimental):** Compute-shader backend for high-density splat rendering.
* **Access:** Strictly via `SlamManager` (Kotlin).

## Sensors & Mapping
* **LiDAR (Pro Devices):** High-fidelity surface mapping via `RAW_DEPTH_ONLY`. Mesh vertices are generated in Kotlin and refined in C++.
* **Standard Depth:** Automatic depth API used as a fallback on non-LiDAR devices.

## The Bridge: SlamManager (Singleton)
A Hilt-provided Singleton that lives as long as the Application.
* **Role:** Manages the C++ pointer (`nativeHandle`).
* **Injection:** `@Inject lateinit var slamManager: SlamManager`
* **Lifecycle:** Initialized once. Destroyed never (practically).

## The Build System
* **Language:** Kotlin + C++17
* **DI:** Hilt (via KSP)
* **Build:** Gradle (Kotlin DSL)
* **Quality:** `Checkstyle` enforced for Kotlin and Java; `clang-format` recommended for C++.

## Data Flow
```mermaid
graph TD
    AR[AR Renderer] -- Depth/Pose --> SM[SlamManager]
    MS[MeshGenerator] -- Vertices --> SM
    ED[Editor ViewModel] -- Bitmap --> SM
    SM -- JNI --> Cpp[Native Engine]
    Cpp -- OpenGL/Vulkan --> Surface[GLSurfaceView/SurfaceView]
    AR -- PnP Correction --> SM -- alignMap() --> Cpp
```

## Security
* **Network:** Cleartext traffic disabled; no internet access permitted.
* **Logging:** Verbose and Debug logs automatically stripped in release builds.
* **Analytics:** Disabled.

The Native Bridge (Unified)
Previously a split system, the native bridge is now consolidated into a single pipeline.

1. The Kotlin Gatekeeper: SlamManager
   Location: core/nativebridge/src/main/java/com/hereliesaz/graffitixr/nativebridge/SlamManager.kt

Role: The Singleton source of truth. It manages the C++ pointer (nativeHandle).

Responsibility:

Initializing/Destroying the C++ engine.

Feeding Camera Frames (Depth/Color) to the SLAM system.

Exposing StateFlows (e.g., mappingQuality) to the UI.

Providing utility functions for OpenCV features (ORB extraction).

2. The C++ Implementation: GraffitiJNI.cpp
   Location: core/nativebridge/src/main/cpp/GraffitiJNI.cpp

Role: The translator.

Responsibility:

Converts Java objects (ByteBuffers, Arrays, Bitmaps) into C++ structures (cv::Mat, pointers).

Calls methods on the MobileGS instance.

3. The Engine: MobileGS
   Location: core/nativebridge/src/main/cpp/MobileGS.h

Role: The brain.

Responsibility:

Tracking: Teleological SLAM (tracking against the future painting).

Mapping: Gaussian Splatting storage and rendering.

Keyframing: Managing the Voxel Confidence Map.

Data Flow
The AR Loop
ARCore provides a Frame.

ArRenderer extracts the Image (YUV/Depth) and Camera Pose.

SlamManager.feedDepthData() is called with raw ByteBuffers.

JNI locks the buffers and passes pointers to MobileGS.

MobileGS updates the splats.

ArRenderer calls SlamManager.draw().

MobileGS renders the splats directly to the GL Surface.

The Editor Loop (Image Processing)
User selects an image.

EditorViewModel loads the Bitmap.

SlamManager.extractFeatures(bitmap) is called.

JNI converts Bitmap to cv::Mat (Grayscale).

OpenCV runs ORB detection.

Descriptors are returned as ByteArray to Kotlin.