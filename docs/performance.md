# Performance Guide

## The Rendering Loop (16ms Budget)

### 1. Instanced Rendering
We use `glDrawArraysInstanced` to render thousands of splats.
* **Optimization:** We do NOT update the VBO every frame if the map hasn't changed.
* **Dirty Flag:** `MobileGS` tracks `isDirty`. The VBO is only re-uploaded when `isDirty == true`.

### 2. The Sorter Thread
Gaussian Splatting requires back-to-front sorting for alpha blending.
* **Constraint:** Sorting 100k points takes time.
* **Solution:** We run `std::sort` (or a Radix Sort) on a **background thread**.
* **Double Buffering:** The render thread draws using `IndexBuffer_A` while the sorter thread sorts `IndexBuffer_B`. They swap pointers atomically.

### 3. JNI Overhead
* **Rule:** Minimize JNI calls per frame.
* **Bad:** Calling `getSplatCount()` every millisecond from Kotlin.
* **Good:** Passing a pointer to a struct once, and updating that struct in C++.

### 4. Thermal Throttling
ARCore + Camera + OpenGL + CV = Heat.
* **Mitigation:** If the device gets too hot, we automatically lower the `MAX_SPLATS` limit to reduce GPU load.