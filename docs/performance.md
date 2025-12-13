# Performance Optimization Guide

GraffitiXR is a high-performance AR application. Maintaining 60 FPS in AR mode is critical.

## **1. Rendering Loop Optimization (`ArRenderer.kt`)**

### **No Allocations in `onDrawFrame`**
-   **Rule:** Do not create new objects (like `Matrix`, `float[]`, `Vector3`) inside the `onDrawFrame` loop.
-   **Solution:** Pre-allocate reusable members (e.g., `private val projectionMatrix = FloatArray(16)`) and update them in place.
-   **Reason:** Frequent allocations trigger the Garbage Collector (GC), causing "stop-the-world" pauses that manifest as stutter.

### **Matrix Caching**
-   Calculations that depend only on the camera (like Projection and View matrices) should be done once per frame, not once per object.

### **Shader Management**
-   Shaders are compiled once during `onSurfaceCreated`.
-   Uniform locations are cached.
-   Texture binding is minimized.

## **2. Image Processing & Computer Vision**

### **Background Threads**
-   **Rule:** Never run OpenCV or ML Kit operations on the main UI thread or the GL render thread.
-   **Implementation:** Use `viewModelScope.launch(Dispatchers.IO)` or a dedicated `analysisScope` in `ArRenderer`.
-   **Synchronization:** Use `ReentrantLock` or `AtomicBoolean` (e.g., `isAnalyzing`) to drop frames if the previous analysis is still running. Do not queue up analysis tasks.

### **YUV vs. RGB**
-   When possible, process the Y-plane (grayscale) directly from the `Image` object (via `yuvToRgbConverter` or accessing the `ByteBuffer` directly) instead of converting the full image to a Bitmap.

## **3. Bitmap Management**

### **Memory Leaks**
-   Large Bitmaps are the #1 cause of `OutOfMemoryError`.
-   **Recycling:** Call `.recycle()` on Bitmaps when they are no longer needed, especially intermediate steps in image processing.
-   **OpenCV Mats:** Always call `.release()` on `Mat` objects. They use native memory that the JVM GC does not track well.

## **4. UI Performance (Compose)**

### **Recomposition**
-   State reads should be as local as possible.
-   Use `derivedStateOf` for values that update frequently (like scroll offsets or progress).
-   Avoid heavy computations in Composable bodies.

### **Canvas Drawing**
-   For custom drawing (like `TargetRefinementScreen`), minimize object creation in the `onDraw` block.
