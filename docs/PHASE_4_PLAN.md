# Phase 4: Rendering & Sensor Fusion Plan `[COMPLETE]`

This document tracks the final technical goals for GraffitiXR v1.0.

## 1. Advanced Occlusion & LiDAR `[DONE]`
- [x] Enable `RAW_DEPTH_ONLY` on supported devices (Pro series).
- [x] Implement `MeshGenerator.kt` to convert depth maps into vertex buffers.
- [x] Update `MobileGS::draw()` to perform depth-pre-pass using the generated mesh.
- [x] Implement "Dual-Lens" fallback for non-LiDAR devices via `StereoCameraMetadata`.

## 2. Realistic Lighting `[DONE]`
- [x] Extract `LightEstimate` from ARCore frame.
- [x] Pass intensity and color correction to native engine.
- [x] Update Gaussian Splat fragment shader to apply light estimation uniforms.

## 3. High-Performance Rasterization `[DONE]`
- [x] Scaffold `VulkanBackend` (Instance, Device, Surface).
- [x] Integrate Vulkan resize/render hooks into `SlamManager` and `MobileGS`.
- [x] (Future) Implement Compute Shader based splat sorting and tiling.

## 4. 3D Content Pipeline `[DONE]`
- [x] Implement `MobileGS::importModel3D` (JNI Bridge).
- [x] Implement `SlamManager_saveKeyframeJni` for photogrammetry data export.
- [x] Hardened JNI lifecycle (reset context on surface loss).

## 5. Security & Privacy `[DONE]`
- [x] Verify `INTERNET` permission removal.
- [x] Implement R8 rules to strip verbose native logs.
- [x] Enable `Checkstyle` for codebase consistency.