# Low-Light AI Mapping Design

**Date:** 2026-04-28
**Goal:** Reduce time-to-confident-map in low-light conditions for both initial scanning and relocalization.

---

## Problem

In low light, each camera frame contributes less to the voxel map: stereo depth matching degrades (less texture for the stereo matcher to lock onto) and ORB feature detection produces fewer, weaker keypoints. The result is that confidence scores accumulate more slowly per frame — the artist has to scan longer before the mural locks in.

---

## Existing Infrastructure

Before designing, several relevant pieces were found already in place:

- **SuperPointDetector** — fully implemented in `core/nativebridge/src/main/cpp/SuperPointDetector.cpp` using `cv::dnn::Net` (ONNX backend). Loaded from `assets/superpoint.onnx` via `nativeLoadSuperPoint()`. However, it is **not wired into the tracking loop** — `MobileGS.cpp` still uses `cv::ORB::create(500)` (line 47) and `mFeatureDetector->detectAndCompute()` (line 254).
- **SuperPointDetector GPU** — currently configured as `DNN_TARGET_CPU` only.
- **`mLightLevel`** — already stored in `MobileGS`, updated each frame via `nativeUpdateLightLevel()` JNI, and passed to `SurfaceMesh::update()`. Not yet used to gate feature detection or enhancement.

---

## Solution Overview

Three changes, in order of risk and impact:

1. Wire SuperPoint into the active tracking loop (replacing ORB)
2. Enable OpenCL GPU backend for SuperPoint
3. Add `LowLightEnhancer` — brightness-gated frame enhancement via Zero-DCE++

---

## Architecture

### Data Flow

```
Camera Frame (grayscale)
    │
    ▼
[mLightLevel < threshold?]
    │ yes                    │ no
    ▼                        │
LowLightEnhancer::enhance()  │
    │                        │
    └──────────┬─────────────┘
               ▼
    SuperPointDetector::detect()
               │
               ▼
    Keypoints + Descriptors → SLAM pipeline (voxel confidence update)
```

The enhanced frame is also used for depth integration in the same pass, improving stereo matching quality without an additional enhancement call.

---

## Components

### 1. Wire SuperPoint (MobileGS.cpp)

Replace the ORB calls with SuperPoint. ORB is retained as a fallback if `mSuperPoint` is not loaded.

**Files changed:**
- `core/nativebridge/src/main/cpp/MobileGS.cpp` — lines 47 and 254

**Logic at line 254:**
```cpp
if (mSuperPoint.isLoaded()) {
    mSuperPoint.detect(frame, kps, descs);
} else {
    mFeatureDetector->detectAndCompute(frame, cv::noArray(), kps, descs);
}
```

No changes to `SuperPointDetector.cpp` or `.h` for this step.

---

### 2. GPU Backend for SuperPoint (SuperPointDetector.cpp)

In `SuperPointDetector::load()`, try OpenCL first and fall back to CPU:

```cpp
mNet.setPreferableBackend(cv::dnn::DNN_BACKEND_DEFAULT);
mNet.setPreferableTarget(cv::dnn::DNN_TARGET_OPENCL);
// run a warmup forward pass; on failure, fall back:
//   mNet.setPreferableTarget(cv::dnn::DNN_TARGET_CPU);
```

No model changes. No new dependencies. Same ONNX file.

---

### 3. LowLightEnhancer

A new native class mirroring the `SuperPointDetector` pattern exactly.

**New files:**
- `core/nativebridge/src/main/cpp/include/LowLightEnhancer.h`
- `core/nativebridge/src/main/cpp/LowLightEnhancer.cpp`

**Interface:**
```cpp
class LowLightEnhancer {
public:
    bool load(const std::vector<uchar>& onnxBytes);
    bool isLoaded() const;
    bool enhance(const cv::Mat& input, cv::Mat& output);
private:
    cv::dnn::Net      mNet;
    std::mutex        mMutex;
    std::atomic<bool> mLoaded{false};
};
```

- Input/output: `CV_8UC1` grayscale, resized to 600×400 (width×height) before inference (Zero-DCE++ accepts arbitrary sizes; 600×400 matches the landscape camera orientation and balances quality vs. speed on mid-range hardware).
- GPU backend: same OpenCL-first, CPU-fallback pattern as SuperPoint change above.
- Model: `zerodce.onnx` (~500KB), added to `app/src/main/assets/`.

**Brightness threshold:** `mLightLevel < 0.35f`. This value matches ARCore's normalized light estimate — 0.35 corresponds roughly to candlelit / dim indoor conditions. Exposed as a constant in `MobileGS.h` for easy tuning.

---

### 4. MobileGS Integration

**`MobileGS.h`** — add member and loader:
```cpp
LowLightEnhancer mEnhancer;
bool loadLowLightEnhancer(const std::vector<uchar>& onnxBytes);
static constexpr float kLowLightThreshold = 0.35f;
```

**`MobileGS.cpp`** — in the feature detection path:
```cpp
cv::Mat featureFrame = frame;
if (mEnhancer.isLoaded() && mLightLevel < kLowLightThreshold) {
    cv::Mat enhanced;
    if (mEnhancer.enhance(frame, enhanced)) featureFrame = enhanced;
}
// then pass featureFrame to SuperPoint (or ORB fallback)
```

---

### 5. JNI Surface (GraffitiJNI.cpp)

One new JNI function, mirroring `nativeLoadSuperPoint`:

```cpp
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeLoadLowLightEnhancer(
        JNIEnv* env, jobject thiz, jobject assetManager) {
    AAssetManager* mgr = AAssetManager_fromJava(env, assetManager);
    AAsset* asset = AAssetManager_open(mgr, "zerodce.onnx", AASSET_MODE_BUFFER);
    if (!asset) return;
    size_t len = AAsset_getLength(asset);
    std::vector<uchar> buf(len);
    AAsset_read(asset, buf.data(), len);
    AAsset_close(asset);
    if (gSlamEngine) gSlamEngine->loadLowLightEnhancer(buf);
}
```

`nativeUpdateLightLevel` already exists — no Kotlin-side changes needed for the light level gate.

---

### 6. Kotlin (SlamManager.kt)

One new external function declaration and one new public method, mirroring the existing `loadSuperPoint` / `nativeLoadSuperPoint` pattern in `SlamManager.kt`:

```kotlin
fun loadLowLightEnhancer(assetManager: AssetManager) = nativeLoadLowLightEnhancer(assetManager)
private external fun nativeLoadLowLightEnhancer(assetManager: AssetManager)
```

Called at the same call site as `loadSuperPoint()` (wherever that is invoked in `:feature:ar`).

---

## New Assets

| File | Size | Purpose |
|------|------|---------|
| `app/src/main/assets/zerodce.onnx` | ~500KB | Zero-DCE++ low-light enhancement model |

`superpoint.onnx` already exists in assets.

---

## Performance Budget

| Device tier | SuperPoint (GPU) | Enhancement (GPU) | Headroom |
|-------------|-----------------|-------------------|---------|
| Flagship (SD 8 Gen 2) | ~2ms | ~3ms | 11ms @ 60fps |
| Mid-range (SD 7 Gen 1) | ~5ms | ~6ms | 5ms @ 60fps |

Enhancement only runs when `mLightLevel < 0.35f`, so in normal lighting the overhead is zero. On mid-range devices in low light, the combined ~11ms fits within a 16ms frame budget. If profiling shows it doesn't, the fallback is to run enhancement every other frame (caching the last enhanced output).

---

## Out of Scope

- Monocular depth supplementation (Approach B) — deferred; scale ambiguity requires metric calibration work that is a separate design.
- UI indicator for low-light mode — not needed; the improvement is silent and automatic.
- Tuning `kLowLightThreshold` per-device — single constant is sufficient for v1.

---

## Files Changed Summary

| File | Change |
|------|--------|
| `core/nativebridge/src/main/cpp/MobileGS.cpp` | Wire SuperPoint, add enhancement gate |
| `core/nativebridge/src/main/cpp/MobileGS.h` | Add `mEnhancer`, `loadLowLightEnhancer()`, `kLowLightThreshold` |
| `core/nativebridge/src/main/cpp/SuperPointDetector.cpp` | Switch to OpenCL backend with CPU fallback |
| `core/nativebridge/src/main/cpp/LowLightEnhancer.cpp` | New file |
| `core/nativebridge/src/main/cpp/include/LowLightEnhancer.h` | New file |
| `core/nativebridge/src/main/cpp/GraffitiJNI.cpp` | Add `nativeLoadLowLightEnhancer` |
| `core/nativebridge/src/main/java/com/hereliesaz/graffitixr/nativebridge/SlamManager.kt` | Declare and call `nativeLoadLowLightEnhancer` |
| `app/src/main/assets/zerodce.onnx` | New asset |
