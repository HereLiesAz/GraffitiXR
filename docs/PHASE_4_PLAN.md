# Phase 4 Implementation Plan: Advanced Rendering & Sensors

## 1. LiDAR & Advanced Depth Integration
**Goal:** Leverage hardware depth sensors (LiDAR) on Pro devices for more accurate surface mapping and occlusion.

### Tasks:
- [ ] **Depth Mode Selection:**
    - Update `ArRenderer.kt` to query `session.isDepthModeSupported()`.
    - Prefer `Config.DepthMode.RAW_DEPTH_ONLY` on supported devices to minimize temporal smoothing noise from the standard Depth API.
- [ ] **Mesh Generation (LiDAR):**
    - Implement `MeshGenerator` class in `core:common` or `feature:ar`.
    - Process raw depth maps into a low-poly triangular mesh representing the wall surface.
    - Export mesh vertices to `SlamManager` for precise projection.

## 2. Vulkan Rendering Backend (Optimization)
**Goal:** Transition from OpenGL ES 3.0 instancing to Vulkan compute shaders for high-performance Gaussian Splat rasterization.

### Tasks:
- [ ] **Scaffolding:**
    - Fill out `VulkanRenderer.kt` stub in `feature:ar`.
    - Add Vulkan dependency checks in `build.gradle.kts`.
- [ ] **Native Integration:**
    - Create `VulkanBackend.cpp` in `core:native`.
    - Implement `vkCreateInstance` and `vkCreateDevice` with Android-specific extensions (`VK_KHR_android_surface`).
- [ ] **Compute Rasterizer:**
    - Port `drawJni` logic to a Vulkan Compute Shader.
    - Implement depth-buffer sharing between ARCore (OES texture) and Vulkan.

## 3. Application Hardening & Polish
**Goal:** Ensure stability and production-readiness.

### Tasks:
- [ ] **JNI Robustness:**
    - Verify `ByteBuffer` lifecycle to prevent use-after-free in native code.
    - Add explicit error codes to JNI functions.
- [ ] **Telemetry & Privacy:**
    - Audit all network calls (should be zero).
    - Silencing remaining `Log.d` calls in release builds using R8 rules.
- [ ] **CI/CD Hardening:**
    - Add `checkstyle` or `ktlint` to the build pipeline.
