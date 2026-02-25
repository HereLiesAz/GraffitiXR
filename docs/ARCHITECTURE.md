<!-- ~~~ FILE: ./docs/ARCHITECTURE.md ~~~ -->
# GraffitiXR Architecture

## High-Level Overview

GraffitiXR follows a multi-module Clean Architecture pattern, optimized for high-performance native rendering and local-first data persistence.

```mermaid
graph TD
    App[":app"] --> FeatureAR[":feature:ar"]
    App --> FeatureEditor[":feature:editor"]
    App --> FeatureDash[":feature:dashboard"]
    
    FeatureAR --> CoreNative[":core:nativebridge"]
    FeatureEditor --> CoreDomain[":core:domain"]
    
    CoreNative --> CoreNativeBridge[":core:nativebridge"]
    CoreNativeBridge --> Vulkan[Vulkan SDK]
    CoreNativeBridge --> GLES[OpenGL ES 3.0]
    CoreNativeBridge --> OpenCV[OpenCV 4.x]

Module Definitions
feature:ar
Responsible for ARCore session management, camera frame acquisition, and sensor fusion.
feature:editor
Provides tools for mural preparation. Layer hierarchy handling and JNI routing.
core:nativebridge
The C++17 engine (MobileGS) and JNI boundary. Handles memory management, spatial voxel hashing, Gaussian splatting, and OpenCV algorithm invocations.
Data Flow (Teleological Loop)
Observation: Camera captures environment.
Estimation: ARCore provides 6DOF pose.
Correction: OpenCV matches current frame against stored fingerprint.
Alignment: Native engine updates global map transform.