// FILE: docs/ARCHITECTURE.md
# GraffitiXR Architecture

## High-Level Overview

GraffitiXR follows a multi-module Clean Architecture pattern, optimized for high-performance native rendering and local-first data persistence.

~~~mermaid
graph TD
    App[":app"] --> FeatureAR[":feature:ar"]
    App --> FeatureEditor[":feature:editor"]
    App --> FeatureDash[":feature:dashboard"]

    FeatureAR --> CoreNative[":core:nativebridge"]
    FeatureAR --> CoreDomain[":core:domain"]
    FeatureEditor --> CoreDomain
    FeatureDash --> CoreData[":core:data"]

    CoreNative --> GLES[OpenGL ES 3.0]
    CoreNative --> OpenCV[OpenCV 4.x]
    CoreData --> CoreDomain
~~~

**Feature modules must not depend on other feature modules.**

## Module Definitions

### `:feature:ar`
ARCore session lifecycle (`ArViewModel`), camera frame acquisition (`ArRenderer`), and SLAM data feeding. `ArRenderer` renders the camera background and calls `slamManager.draw()` for voxel memory rendering via OpenGL ES 3.0.

### `:feature:editor`
Mural preparation tools. Layer hierarchy, image manipulation, and GPU-accelerated Liquify.

### `:core:nativebridge`
C++17 MobileGS engine and JNI boundary. Handles **Persistent Voxel Memory**, stochastic depth integration, PnP Relocalization, and O(1) opaque rendering.

## Data Flow (AR Pipeline)

Each ARCore tracking frame in `ArRenderer.onDrawFrame`:

~~~
camera.trackingState ────────────────────────► setArCoreTrackingState(isTracking)
frame.acquireCameraImage() [RGBA] ───────────► feedColorFrame() (Snap-Back thread)
frame.acquireDepthImage16Bits() ─────────────► feedArCoreDepth() (Stochastic Integration)
                                              │  └─ Confidence Reward (0.9 HW / 0.5 Mono)
camera.getViewMatrix/ProjectionMatrix ───────► updateCamera()
                                              │
                                    MobileGS::processDepthFrame()
                                              │
                            ┌─────────────────┴──────────────────┐
                     BackgroundRenderer                   slamManager.draw()
                  (camera feed, GLSurfaceView)       (opaque voxels, Z-buffered)
~~~

**Camera ownership:**
- `EditorMode.AR` → ARCore `Session` owns camera.
- `EditorMode.OVERLAY` → CameraX owns camera.

## Teleological Correction

The engine uses a dedicated background thread (`relocThreadFunc`) to continuously match the current camera frame against stored wall fingerprints. On a high-confidence PnP match, the engine automatically corrects global drift, enabling the mural to "snap back" instantly when the user resumes from a screen-off event.

---
*Documentation updated on 2026-04-24 during Persistent Voxel Memory and Pocket-Ready recovery implementation.*
