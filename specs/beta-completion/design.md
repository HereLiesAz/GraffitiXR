# Design: Beta Completion & Feature Rollout

## 1. System Architecture Overview

The system remains a **Multi-Module Android Application** with a heavy reliance on a **Native C++ Engine (`MobileGS`)** for core rendering and AR processing. The Beta phase focuses on stabilizing the AR pipeline, enhancing the Editor with professional tools, and ensuring robust export capabilities.

### Modules & Responsibilities
*   `app`: DI (Hilt), Navigation, Application Lifecycle.
*   `feature:ar`: AR Mode logic, Camera (CameraX/ARCore), `ArRenderer`, `TargetEvolutionEngine`.
*   `feature:editor`: Canvas logic, `DrawingCanvas`, `Layer` management, `ExportManager`.
*   `core:nativebridge`: JNI interface, `SlamManager`, `MobileGS` (C++).
*   `core:common`: Math utilities, Extension functions.

---

## 2. Feature Design: Sketching Tools

**Goal:** Provide a Photoshop-like experience for modifying mural sketches.

### Architecture
*   **`DrawingCanvas` (Kotlin/Compose):**
    *   Handles touch events and state for the active layer.
    *   Supports `Brush`, `Eraser` natively via `android.graphics.Canvas` and `Path`.
*   **`ImageProcessor` (JNI Wrapper):**
    *   Interface for complex image manipulations.
    *   Methods: `liquify(bitmap, vectorField)`, `heal(bitmap, mask)`, `burnDodge(bitmap, map)`.
*   **Native Implementation (`core:nativebridge/src/main/cpp`):**
    *   **Liquify:** Grid-based mesh warp using OpenGL shaders (ES 3.0) or OpenCV `remap`. *Decision: OpenGL for real-time preview.*
    *   **Heal:** OpenCV `inpaint` (Telea algorithm).
    *   **Burn/Dodge:** Pixel shader operation on the texture.

### UX Flow
1.  User selects tool from `Toolbar`.
2.  **Brush/Eraser:** Direct drawing on the `Canvas`.
3.  **Liquify:** Enters a "Warp Mode" (overlay grid). User drags grid points. `WarpableImage` logic reused here.
4.  **Heal/Burn:** User paints a mask. On release, the mask + original bitmap are sent to JNI for processing. Result updates the layer.

---

## 3. Feature Design: Export Logic

**Goal:** High-fidelity export of the project.

### Architecture
*   **`ExportManager` (Kotlin Class):**
    *   Singleton managed by Hilt.
*   **Single Image Export:**
    *   Creates an off-screen `Bitmap` (monitor resolution or custom).
    *   `Canvas` created for this bitmap.
    *   Iterates `Project.layers` from bottom to top.
    *   Applies `Matrix` (Scale, Rotate, Translate) and `Paint` (Alpha, BlendMode) for each layer.
    *   Saves result to JPG/PNG.
*   **Layer Export:**
    *   Creates directory: `Exports/{ProjectName}_Layers/`.
    *   Iterates layers.
    *   Saves each layer's `Bitmap` as `Layer_{N}_{Name}.png`.
    *   *Constraint:* Must handle "Group" layers by flattening or exporting individually (User preference). Default: Individual.

---

## 4. Feature Design: AR & "Teleological SLAM"

**Goal:** Robust, jitter-free locking of the projection onto the physical wall.

### Architecture
*   **`TeleologicalTracker` (New Class in `feature:ar`):**
    *   Manages the `solvePnP` loop.
    *   **Inputs:**
        *   `CurrentFrame` (Camera Image).
        *   `TargetFingerprint` (Feature points of the reference image).
        *   `3DWorldPoints` (Known structure from "Target Evolution").
    *   **Process:**
        1.  Extract features from `CurrentFrame` (ORB/SIFT - via OpenCV).
        2.  Match with `TargetFingerprint`.
        3.  Run `solvePnP` (RANSAC) to find Camera Pose ($R, t$).
        4.  Filter pose (Kalman Filter or Low-Pass) to reduce jitter.
        5.  Update `MobileGS` camera matrix.
*   **"Target Evolution":**
    *   **Refinement:** `TargetEvolutionEngine` uses `approxPolyDP` to snap MLKit segmentation contours to a clean 4-point polygon (the "Physical Target").
    *   **User Action:** "Tap to Snap" - User taps the object, engine runs segmentation + poly approximation.

---

## 5. Feature Design: Passive Triangulation (Stereo Depth)

**Goal:** Depth for non-LiDAR devices.

### Architecture
*   **`DepthProvider` (Interface):**
    *   `fun getDepthMap(): ByteBuffer?`
    *   `fun getConfidence(): Float`
*   **`LidarDepthProvider` (Implementation):**
    *   Wraps `ARCore` / `Camera2` ToF streams.
*   **`StereoDepthProvider` (New Implementation):**
    *   **Hardware Access:** Uses `Camera2` API's `logical` multi-camera support to request synchronized frames.
    *   **Processing (JNI):**
        *   Passes Left/Right images to Native.
        *   `cv::StereoSGBM` computes disparity.
        *   `reprojectImageTo3D` creates depth map.
    *   **Output:** Returns depth buffer to `MobileGS`.

---

## 6. Bug Fix Design: Camera Blocking

**Issue:** AR view is black/opaque, blocking camera.

### Diagnosis & Fix
*   **Layering:**
    *   Layer 1 (Bottom): `androidx.camera.view.PreviewView` (Camera Feed).
    *   Layer 2 (Top): `android.opengl.GLSurfaceView` (AR Overlay).
*   **The Fix:**
    1.  Ensure `GLSurfaceView` is initialized with `PixelFormat.TRANSLUCENT`.
    2.  Ensure `ArRenderer` / `MobileGS` clears with `glClearColor(0,0,0,0)`.
    3.  **Critical:** Disable any `BackgroundRenderer` usage in `ArRenderer` that attempts to draw the camera feed *onto* the GL surface (since `PreviewView` handles it). The GL surface must *only* draw the virtual content (Splats/Grid).
    4.  Verify `setZOrderMediaOverlay(true)` is called on the `GLSurfaceView`.

---

## 7. Native Bridge Interface (JNI)

**Updated `NativeMethods.kt`:**

```kotlin
external fun initStereoDepth(width: Int, height: Int)
external fun updateStereoFrame(left: ByteBuffer, right: ByteBuffer)
external fun processHeal(handle: Long, bitmap: Bitmap, mask: Bitmap)
external fun processLiquify(handle: Long, bitmap: Bitmap, meshData: FloatArray)
external fun processBurnDodge(handle: Long, bitmap: Bitmap, map: Bitmap)
```

## 8. Testing Strategy
*   **Unit Tests:** Kotlin logic for `ExportManager`, `TargetEvolutionEngine` (math parts).
*   **Instrumented Tests:** `DrawingCanvas` interaction.
*   **Manual Validation:**
    *   Verify AR Overlay transparency on physical device.
    *   Verify "Snap to Target" accuracy.
    *   Verify Export output quality.
