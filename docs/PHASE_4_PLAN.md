# Phase 4 Implementation Plan: Advanced Rendering & Sensors

## 1. LiDAR & Advanced Depth Integration
**Goal:** Leverage hardware depth sensors (LiDAR) on Pro devices for more accurate surface mapping and occlusion.

### Tasks:
- [x] **Depth Mode Selection:**
    - Update `ArRenderer.kt` to query `session.isDepthModeSupported()`.
    - Prefer `Config.DepthMode.RAW_DEPTH_ONLY` on supported devices to minimize temporal smoothing noise from the standard Depth API.
- [x] **Mesh Generation (LiDAR):**
    - Implement `MeshGenerator` class in `feature:ar`.
    - Process raw depth maps into a low-poly triangular mesh representing the wall surface.
    - Export mesh vertices to `SlamManager` for precise projection.

## 2. Vulkan Rendering Backend (Optimization)
**Goal:** Transition from OpenGL ES 3.0 instancing to Vulkan compute shaders for high-performance Gaussian Splat rasterization.

### Tasks:
- [x] **Scaffolding:**
    - Fill out `VulkanRenderer.kt` stub in `feature:ar`.
    - Add Vulkan dependency checks in `build.gradle.kts`.
- [x] **Native Integration:**
    - Create `VulkanBackend.cpp` in `core:nativebridge`.
    - Implement `vkCreateInstance` and `vkCreateDevice` with Android-specific extensions (`VK_KHR_android_surface`).
- [x] **Compute Rasterizer:**
    - Port `drawJni` logic to a Vulkan Compute Shader (Scaffolded Compute Pipeline).
    - Implement depth-buffer sharing between ARCore (OES texture) and Vulkan.

## 3. Application Hardening & Polish
**Goal:** Ensure stability and production-readiness.

### Tasks:
- [x] **JNI Robustness:**
    - Verify `ByteBuffer` lifecycle to prevent use-after-free in native code (Implemented rigorous checks).
    - Add explicit error codes to JNI functions.
- [x] **Telemetry & Privacy:**
    - Audit all network calls (should be zero).
    - Silencing remaining `Log.d` calls in release builds using R8 rules.
- [x] **CI/CD Hardening:**
    - Add `checkstyle` or `ktlint` to the build pipeline.
