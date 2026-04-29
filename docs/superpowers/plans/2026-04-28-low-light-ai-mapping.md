# Low-Light AI Mapping Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reduce time-to-confident-map in low light by enabling SuperPoint GPU acceleration and brightness-gated Zero-DCE++ frame enhancement.

**Architecture:** SuperPoint already exists as a native class but is unused — ORB is still the active detector. This plan wires SuperPoint in, gives it an OpenCL backend, adds a new `LowLightEnhancer` class that applies Zero-DCE++ to RGB frames, and gates both on the existing `mLightLevel` field. Model loading is triggered from `ArViewModel.init`.

**Tech Stack:** C++17, OpenCV DNN (ONNX backend), OpenCL via `cv::ocl::haveOpenCL()`, Android NDK, Kotlin + Hilt, mockk for JVM tests.

---

## File Map

| File | Action | What changes |
|------|--------|-------------|
| `core/nativebridge/src/main/assets/superpoint.onnx` | Add | New binary asset |
| `core/nativebridge/src/main/assets/zerodce.onnx` | Add | New binary asset |
| `core/nativebridge/src/main/cpp/SuperPointDetector.cpp` | Modify | OpenCL backend with CPU fallback |
| `core/nativebridge/src/main/cpp/include/MobileGS.h` | Modify | Add `mL2Matcher`, `mEnhancer`, `kLowLightThreshold`, `loadLowLightEnhancer()` |
| `core/nativebridge/src/main/cpp/MobileGS.cpp` | Modify | Init L2 matcher; reloc loop: gray conversion, enhancement gate, SuperPoint wiring, matcher selection |
| `core/nativebridge/src/main/cpp/include/LowLightEnhancer.h` | Create | New class declaration |
| `core/nativebridge/src/main/cpp/LowLightEnhancer.cpp` | Create | New class implementation |
| `core/nativebridge/src/main/cpp/CMakeLists.txt` | Modify | Add `LowLightEnhancer.cpp` to `add_library` |
| `core/nativebridge/src/main/cpp/GraffitiJNI.cpp` | Modify | Add `nativeLoadLowLightEnhancer` JNI function |
| `core/nativebridge/src/main/java/com/hereliesaz/graffitixr/nativebridge/SlamManager.kt` | Modify | Add `loadLowLightEnhancer()` public method and `nativeLoadLowLightEnhancer` external |
| `core/nativebridge/src/test/java/com/hereliesaz/graffitixr/nativebridge/SlamManagerModelLoadingTest.kt` | Create | JVM tests for new Kotlin surface |
| `feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/ArViewModel.kt` | Modify | Call `loadSuperPoint` and `loadLowLightEnhancer` in `init` |
| `feature/ar/src/test/java/com/hereliesaz/graffitixr/feature/ar/ArViewModelTest.kt` | Modify | Add test for model loading calls |

---

## Task 0: Obtain ONNX model assets

**Files:**
- Create: `core/nativebridge/src/main/assets/superpoint.onnx`
- Create: `core/nativebridge/src/main/assets/zerodce.onnx`

This is a prerequisite. No code changes. Do this before any other task.

- [ ] **Step 1: Get superpoint.onnx**

  The existing JNI code at `GraffitiJNI.cpp:439` expects an asset named `superpoint.onnx`. The ONNX model must produce two outputs: shape `[1, 65, Hc, Wc]` (keypoint heatmap) and `[1, 256, Hc, Wc]` (descriptors), where `Hc = H/8`, `Wc = W/8`. Input shape: `[1, 1, H, W]`, float32, normalized `[0, 1]`.

  Search for "SuperPoint ONNX" — community exports from `rpautrat/SuperPoint` or `magicleap/SuperPointPretrainedNetwork` match this schema. Place the downloaded file at:
  ```
  core/nativebridge/src/main/assets/superpoint.onnx
  ```

- [ ] **Step 2: Get zerodce.onnx**

  Zero-DCE++ (also called Zero-DCE_extension by Li-Chongyi). The ONNX model must accept input shape `[1, 3, H, W]` float32 normalized `[0, 1]` and output the same shape. Export from the official PyTorch weights using:
  ```python
  import torch
  model.eval()
  dummy = torch.randn(1, 3, 400, 600)
  torch.onnx.export(model, dummy, "zerodce.onnx",
      opset_version=11,
      input_names=["input"],
      output_names=["output"],
      dynamic_axes={"input": {2: "H", 3: "W"}, "output": {2: "H", 3: "W"}})
  ```
  Place at:
  ```
  core/nativebridge/src/main/assets/zerodce.onnx
  ```

- [ ] **Step 3: Verify assets are visible to the build**

  ```bash
  ls core/nativebridge/src/main/assets/
  ```
  Expected: `shaders  superpoint.onnx  zerodce.onnx`

- [ ] **Step 4: Commit**

  ```bash
  git add core/nativebridge/src/main/assets/superpoint.onnx \
          core/nativebridge/src/main/assets/zerodce.onnx
  git commit -m "chore: add SuperPoint and Zero-DCE++ ONNX model assets"
  ```

---

## Task 1: Enable OpenCL GPU backend for SuperPoint

**Files:**
- Modify: `core/nativebridge/src/main/cpp/SuperPointDetector.cpp:14-19`

Currently `load()` hardcodes `DNN_TARGET_CPU`. This task switches to OpenCL when available, falling back gracefully.

- [x] **Step 1: Edit SuperPointDetector.cpp**

  Open `core/nativebridge/src/main/cpp/SuperPointDetector.cpp`. Find the `load()` method. Replace lines 17–18:

  **Before:**
  ```cpp
  mNet.setPreferableBackend(cv::dnn::DNN_BACKEND_DEFAULT);
  mNet.setPreferableTarget(cv::dnn::DNN_TARGET_CPU);
  ```

  **After:**
  ```cpp
  mNet.setPreferableBackend(cv::dnn::DNN_BACKEND_DEFAULT);
  if (cv::ocl::haveOpenCL()) {
      mNet.setPreferableTarget(cv::dnn::DNN_TARGET_OPENCL);
      LOGD("SuperPoint: using OpenCL backend");
  } else {
      mNet.setPreferableTarget(cv::dnn::DNN_TARGET_CPU);
      LOGD("SuperPoint: OpenCL unavailable, using CPU backend");
  }
  ```

- [x] **Step 2: Build to verify compilation**

  ```bash
  ./gradlew :core:nativebridge:assembleDebug 2>&1 | tail -20
  ```
  Expected: `BUILD SUCCESSFUL`

- [x] **Step 3: Commit**

  ```bash
  git add core/nativebridge/src/main/cpp/SuperPointDetector.cpp
  git commit -m "feat: enable OpenCL GPU backend for SuperPoint with CPU fallback"
  ```

---

## Task 2: Add L2 matcher and wire SuperPoint into the relocalization loop

**Files:**
- Modify: `core/nativebridge/src/main/cpp/include/MobileGS.h:116-118`
- Modify: `core/nativebridge/src/main/cpp/MobileGS.cpp:48, 250-259`

ORB descriptors are binary (`CV_8U`) matched with BruteForce-Hamming. SuperPoint descriptors are float (`CV_32F`) and require BruteForce-L2. We add a second matcher and select the right one by descriptor type, preserving backward compatibility with any ORB-derived wall fingerprints saved to disk.

- [x] **Step 1: Add mL2Matcher to MobileGS.h**

  Open `core/nativebridge/src/main/cpp/include/MobileGS.h`. In the private section, find:

  ```cpp
  cv::Ptr<cv::ORB> mFeatureDetector;
  cv::Ptr<cv::DescriptorMatcher> mMatcher;
  SuperPointDetector mSuperPoint;
  ```

  Replace with:

  ```cpp
  cv::Ptr<cv::ORB> mFeatureDetector;
  cv::Ptr<cv::DescriptorMatcher> mMatcher;    // BruteForce-Hamming for ORB (CV_8U)
  cv::Ptr<cv::DescriptorMatcher> mL2Matcher;  // BruteForce-L2 for SuperPoint (CV_32F)
  SuperPointDetector mSuperPoint;
  ```

- [x] **Step 2: Initialize mL2Matcher in MobileGS::initialize()**

  Open `core/nativebridge/src/main/cpp/MobileGS.cpp`. Find line 48:

  ```cpp
  mMatcher = cv::DescriptorMatcher::create("BruteForce-Hamming");
  ```

  Add the L2 matcher immediately after:

  ```cpp
  mMatcher = cv::DescriptorMatcher::create("BruteForce-Hamming");
  mL2Matcher = cv::DescriptorMatcher::create("BruteForce-L2");
  ```

- [x] **Step 3: Wire SuperPoint into the relocalization loop**

  In `MobileGS::relocThreadFunc()`, find the block starting at line 252:

  ```cpp
  std::vector<cv::KeyPoint> kps;
  cv::Mat descs;
  mFeatureDetector->detectAndCompute(frame, cv::noArray(), kps, descs);

  if (descs.empty()) continue;

  std::vector<std::vector<cv::DMatch>> matches;
  mMatcher->knnMatch(descs, mWallDescriptors, matches, 2);
  ```

  Replace entirely with:

  ```cpp
  // Convert RGB frame to grayscale for SuperPoint
  cv::Mat gray;
  cv::cvtColor(frame, gray, cv::COLOR_RGB2GRAY);

  std::vector<cv::KeyPoint> kps;
  cv::Mat descs;
  // Use SuperPoint if loaded and wall fingerprint type matches (float) or is absent
  bool useSuperPoint = mSuperPoint.isLoaded() &&
      (mWallDescriptors.empty() || mWallDescriptors.type() == CV_32F);
  if (useSuperPoint && !mSuperPoint.detect(gray, kps, descs)) {
      useSuperPoint = false;
  }
  if (!useSuperPoint) {
      mFeatureDetector->detectAndCompute(gray, cv::noArray(), kps, descs);
  }

  if (descs.empty()) continue;

  cv::Ptr<cv::DescriptorMatcher>& activeMatcher =
      (descs.type() == CV_32F) ? mL2Matcher : mMatcher;
  std::vector<std::vector<cv::DMatch>> matches;
  activeMatcher->knnMatch(descs, mWallDescriptors, matches, 2);
  ```

- [x] **Step 4: Build to verify compilation**

  ```bash
  ./gradlew :core:nativebridge:assembleDebug 2>&1 | tail -20
  ```
  Expected: `BUILD SUCCESSFUL`

- [x] **Step 5: Commit**

  ```bash
  git add core/nativebridge/src/main/cpp/include/MobileGS.h \
          core/nativebridge/src/main/cpp/MobileGS.cpp
  git commit -m "feat: wire SuperPoint into reloc loop with L2 matcher and ORB fallback"
  ```

---

## Task 3: Create LowLightEnhancer native class

**Files:**
- Create: `core/nativebridge/src/main/cpp/include/LowLightEnhancer.h`
- Create: `core/nativebridge/src/main/cpp/LowLightEnhancer.cpp`
- Modify: `core/nativebridge/src/main/cpp/CMakeLists.txt`

Mirrors the `SuperPointDetector` pattern exactly: loads ONNX via `cv::dnn::Net`, OpenCL-first backend, mutex-guarded inference. Takes and returns `CV_8UC3` RGB images; internally resizes to 600×400 for inference, then resizes output back to the original dimensions.

- [x] **Step 1: Create LowLightEnhancer.h**

  Create `core/nativebridge/src/main/cpp/include/LowLightEnhancer.h` with this content:

  ```cpp
  #pragma once
  #include <opencv2/opencv.hpp>
  #include <opencv2/dnn.hpp>
  #include <atomic>
  #include <mutex>
  #include <vector>

  class LowLightEnhancer {
  public:
      LowLightEnhancer() = default;

      bool load(const std::vector<uchar>& onnxBytes);
      bool isLoaded() const { return mLoaded; }

      // input/output: CV_8UC3 RGB. Returns false if model not loaded or inference fails.
      bool enhance(const cv::Mat& input, cv::Mat& output);

  private:
      cv::dnn::Net      mNet;
      std::mutex        mMutex;
      std::atomic<bool> mLoaded{false};
  };
  ```

- [x] **Step 2: Create LowLightEnhancer.cpp**

  Create `core/nativebridge/src/main/cpp/LowLightEnhancer.cpp` with this content:

  ```cpp
  #include "include/LowLightEnhancer.h"
  #include <android/log.h>
  #include <algorithm>

  #define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, "LowLightEnhancer", __VA_ARGS__)
  #define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "LowLightEnhancer", __VA_ARGS__)

  bool LowLightEnhancer::load(const std::vector<uchar>& onnxBytes) {
      std::lock_guard<std::mutex> lock(mMutex);
      mLoaded = false;
      try {
          mNet = cv::dnn::readNetFromONNX(onnxBytes);
          if (mNet.empty()) return false;
          mNet.setPreferableBackend(cv::dnn::DNN_BACKEND_DEFAULT);
          if (cv::ocl::haveOpenCL()) {
              mNet.setPreferableTarget(cv::dnn::DNN_TARGET_OPENCL);
              LOGD("Using OpenCL backend");
          } else {
              mNet.setPreferableTarget(cv::dnn::DNN_TARGET_CPU);
              LOGD("OpenCL unavailable, using CPU backend");
          }
          mLoaded = true;
          return true;
      } catch (...) {
          LOGE("Failed to load ONNX model");
          return false;
      }
  }

  bool LowLightEnhancer::enhance(const cv::Mat& input, cv::Mat& output) {
      if (!mLoaded) return false;
      std::lock_guard<std::mutex> lock(mMutex);
      if (mNet.empty()) return false;
      try {
          static const int INF_W = 600;
          static const int INF_H = 400;

          cv::Mat resized;
          cv::resize(input, resized, cv::Size(INF_W, INF_H), 0, 0, cv::INTER_AREA);

          cv::Mat f;
          resized.convertTo(f, CV_32F, 1.0 / 255.0);
          cv::Mat blob = cv::dnn::blobFromImage(f);  // shape [1,3,H,W]

          mNet.setInput(blob);
          cv::Mat out = mNet.forward();  // shape [1,3,H,W], float [0,1]

          if (out.dims < 4 || out.size[1] != 3) return false;
          const int H = out.size[2];
          const int W = out.size[3];
          const float* data = (const float*)out.data;
          const int hw = H * W;

          cv::Mat result(H, W, CV_8UC3);
          for (int y = 0; y < H; ++y) {
              for (int x = 0; x < W; ++x) {
                  const int idx = y * W + x;
                  result.at<cv::Vec3b>(y, x) = {
                      (uchar)std::min(255, (int)(data[idx]          * 255.0f)),
                      (uchar)std::min(255, (int)(data[hw + idx]     * 255.0f)),
                      (uchar)std::min(255, (int)(data[2 * hw + idx] * 255.0f))
                  };
              }
          }
          cv::resize(result, output, input.size(), 0, 0, cv::INTER_LINEAR);
          return true;
      } catch (...) {
          LOGE("Inference failed");
          return false;
      }
  }
  ```

- [x] **Step 3: Add LowLightEnhancer.cpp to CMakeLists.txt**

  Open `core/nativebridge/src/main/cpp/CMakeLists.txt`. Find the `add_library` block:

  ```cmake
  add_library(graffitixr SHARED
      GraffitiJNI.cpp
      MobileGS.cpp
      VoxelHash.cpp
      SurfaceMesh.cpp
      SurfaceUnroller.cpp
      StereoProcessor.cpp
      SuperPointDetector.cpp
      ImageWarper.cpp
  )
  ```

  Add `LowLightEnhancer.cpp`:

  ```cmake
  add_library(graffitixr SHARED
      GraffitiJNI.cpp
      MobileGS.cpp
      VoxelHash.cpp
      SurfaceMesh.cpp
      SurfaceUnroller.cpp
      StereoProcessor.cpp
      SuperPointDetector.cpp
      LowLightEnhancer.cpp
      ImageWarper.cpp
  )
  ```

- [x] **Step 4: Build to verify the new class compiles**

  ```bash
  ./gradlew :core:nativebridge:assembleDebug 2>&1 | tail -20
  ```
  Expected: `BUILD SUCCESSFUL`

- [x] **Step 5: Commit**

  ```bash
  git add core/nativebridge/src/main/cpp/include/LowLightEnhancer.h \
          core/nativebridge/src/main/cpp/LowLightEnhancer.cpp \
          core/nativebridge/src/main/cpp/CMakeLists.txt
  git commit -m "feat: add LowLightEnhancer native class for Zero-DCE++ inference"
  ```

---

## Task 4: Wire LowLightEnhancer into MobileGS

**Files:**
- Modify: `core/nativebridge/src/main/cpp/include/MobileGS.h`
- Modify: `core/nativebridge/src/main/cpp/MobileGS.cpp`

Adds the enhancer member, the brightness threshold constant, the loader method, and the brightness-gated enhancement step in the reloc loop. Enhancement runs on the RGB frame before grayscale conversion, so the improved image feeds into SuperPoint detection.

- [x] **Step 1: Update MobileGS.h**

  Open `core/nativebridge/src/main/cpp/include/MobileGS.h`.

  Add the include near the top, after `#include "SuperPointDetector.h"`:

  ```cpp
  #include "LowLightEnhancer.h"
  ```

  In the **public** section, add after `bool loadSuperPoint(...)`:

  ```cpp
  bool loadLowLightEnhancer(const std::vector<uchar>& onnxBytes);
  ```

  In the **private** section, add after `SuperPointDetector mSuperPoint;`:

  ```cpp
  LowLightEnhancer mEnhancer;
  static constexpr float kLowLightThreshold = 0.35f;
  ```

- [x] **Step 2: Add loader implementation to MobileGS.cpp**

  Open `core/nativebridge/src/main/cpp/MobileGS.cpp`. Find line 424:

  ```cpp
  bool MobileGS::loadSuperPoint(const std::vector<uchar>& onnxBytes) { return mSuperPoint.load(onnxBytes); }
  ```

  Add immediately after:

  ```cpp
  bool MobileGS::loadLowLightEnhancer(const std::vector<uchar>& onnxBytes) { return mEnhancer.load(onnxBytes); }
  ```

- [x] **Step 3: Add enhancement gate in relocThreadFunc**

  In `MobileGS::relocThreadFunc()`, find the gray conversion line you added in Task 2:

  ```cpp
  cv::Mat gray;
  cv::cvtColor(frame, gray, cv::COLOR_RGB2GRAY);
  ```

  Replace with the enhancement-gated version:

  ```cpp
  // Optionally enhance the RGB frame under low light before grayscale conversion
  cv::Mat workFrame = frame;
  if (mEnhancer.isLoaded() && mLightLevel < kLowLightThreshold) {
      cv::Mat enhanced;
      if (mEnhancer.enhance(frame, enhanced)) workFrame = enhanced;
  }
  cv::Mat gray;
  cv::cvtColor(workFrame, gray, cv::COLOR_RGB2GRAY);
  ```

- [x] **Step 4: Build to verify**

  ```bash
  ./gradlew :core:nativebridge:assembleDebug 2>&1 | tail -20
  ```
  Expected: `BUILD SUCCESSFUL`

- [x] **Step 5: Commit**

  ```bash
  git add core/nativebridge/src/main/cpp/include/MobileGS.h \
          core/nativebridge/src/main/cpp/MobileGS.cpp
  git commit -m "feat: wire LowLightEnhancer into MobileGS reloc loop with brightness gate"
  ```

---

## Task 5: Add JNI surface for LowLightEnhancer

**Files:**
- Modify: `core/nativebridge/src/main/cpp/GraffitiJNI.cpp`

Adds `nativeLoadLowLightEnhancer`, mirroring the existing `nativeLoadSuperPoint` function at line 434.

- [x] **Step 1: Add the JNI function to GraffitiJNI.cpp**

  Open `core/nativebridge/src/main/cpp/GraffitiJNI.cpp`. Find the closing brace of `nativeLoadSuperPoint` (around line 447). Add immediately after:

  ```cpp
  JNIEXPORT void JNICALL
  Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeLoadLowLightEnhancer(
          JNIEnv* env, jobject thiz, jobject assetManager) {
      if (!gSlamEngine) return;
      AAssetManager* mgr = AAssetManager_fromJava(env, assetManager);
      AAsset* asset = AAssetManager_open(mgr, "zerodce.onnx", AASSET_MODE_BUFFER);
      if (!asset) {
          __android_log_print(ANDROID_LOG_WARN, "GraffitiJNI", "zerodce.onnx not found in assets");
          return;
      }
      size_t size = (size_t)AAsset_getLength(asset);
      std::vector<uchar> buf(size);
      AAsset_read(asset, buf.data(), (off_t)size);
      AAsset_close(asset);
      gSlamEngine->loadLowLightEnhancer(buf);
  }
  ```

- [x] **Step 2: Build to verify**

  ```bash
  ./gradlew :core:nativebridge:assembleDebug 2>&1 | tail -20
  ```
  Expected: `BUILD SUCCESSFUL`

- [x] **Step 3: Commit**

  ```bash
  git add core/nativebridge/src/main/cpp/GraffitiJNI.cpp
  git commit -m "feat: add nativeLoadLowLightEnhancer JNI function"
  ```

---

## Task 6: Add Kotlin surface and write JVM tests

**Files:**
- Modify: `core/nativebridge/src/main/java/com/hereliesaz/graffitixr/nativebridge/SlamManager.kt`
- Create: `core/nativebridge/src/test/java/com/hereliesaz/graffitixr/nativebridge/SlamManagerModelLoadingTest.kt`

- [x] **Step 1: Write the failing test first**

  Create `core/nativebridge/src/test/java/com/hereliesaz/graffitixr/nativebridge/SlamManagerModelLoadingTest.kt`:

  ```kotlin
  package com.hereliesaz.graffitixr.nativebridge

  import android.content.res.AssetManager
  import io.mockk.mockk
  import io.mockk.verify
  import org.junit.Test

  class SlamManagerModelLoadingTest {

      private val slamManager: SlamManager = mockk(relaxed = true)
      private val assets: AssetManager = mockk(relaxed = true)

      @Test
      fun `loadSuperPoint is callable on SlamManager`() {
          slamManager.loadSuperPoint(assets)
          verify { slamManager.loadSuperPoint(assets) }
      }

      @Test
      fun `loadLowLightEnhancer is callable on SlamManager`() {
          slamManager.loadLowLightEnhancer(assets)
          verify { slamManager.loadLowLightEnhancer(assets) }
      }
  }
  ```

- [x] **Step 2: Run tests to confirm the second test fails**

  ```bash
  ./gradlew :core:nativebridge:testDebugUnitTest 2>&1 | grep -E "FAIL|PASS|ERROR|loadLowLight"
  ```
  Expected: `loadLowLightEnhancer is callable on SlamManager` → FAIL (method doesn't exist yet)

- [x] **Step 3: Add loadLowLightEnhancer to SlamManager.kt**

  Open `core/nativebridge/src/main/java/com/hereliesaz/graffitixr/nativebridge/SlamManager.kt`. Find line 175:

  ```kotlin
  fun loadSuperPoint(assetManager: AssetManager): Boolean = nativeLoadSuperPoint(assetManager)
  ```

  Add immediately after:

  ```kotlin
  fun loadLowLightEnhancer(assetManager: AssetManager) = nativeLoadLowLightEnhancer(assetManager)
  ```

  Find line 245 (the `nativeLoadSuperPoint` external declaration):

  ```kotlin
  private external fun nativeLoadSuperPoint(assetManager: AssetManager): Boolean
  ```

  Add immediately after:

  ```kotlin
  private external fun nativeLoadLowLightEnhancer(assetManager: AssetManager)
  ```

- [x] **Step 4: Run tests to confirm both pass**

  ```bash
  ./gradlew :core:nativebridge:testDebugUnitTest 2>&1 | grep -E "FAIL|PASS|ERROR|tests"
  ```
  Expected: All tests pass, including `SlamManagerModelLoadingTest`

- [x] **Step 5: Commit**

  ```bash
  git add core/nativebridge/src/main/java/com/hereliesaz/graffitixr/nativebridge/SlamManager.kt \
          core/nativebridge/src/test/java/com/hereliesaz/graffitixr/nativebridge/SlamManagerModelLoadingTest.kt
  git commit -m "feat: add loadLowLightEnhancer to SlamManager Kotlin surface"
  ```

---

## Task 7: Wire model loading in ArViewModel

**Files:**
- Modify: `feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/ArViewModel.kt:173-185`
- Modify: `feature/ar/src/test/java/com/hereliesaz/graffitixr/feature/ar/ArViewModelTest.kt`

`ArViewModel` has `appContext: Context` injected and `slamManager: SlamManager` injected. The `init` block is the right place to load models — it runs once at ViewModel creation, on a background coroutine so it doesn't block the main thread.

- [x] **Step 1: Write the failing test in ArViewModelTest.kt**

  Open `feature/ar/src/test/java/com/hereliesaz/graffitixr/feature/ar/ArViewModelTest.kt`. Add this test to the existing class (after the `@Before` block, before existing tests):

  ```kotlin
  @Test
  fun `init loads AI models via slamManager`() = runTest {
      testDispatcher.scheduler.advanceUntilIdle()
      verify { slamManager.loadSuperPoint(any()) }
      verify { slamManager.loadLowLightEnhancer(any()) }
  }
  ```

- [x] **Step 2: Run test to confirm it fails**

  ```bash
  ./gradlew :feature:ar:testDebugUnitTest --tests "*ArViewModelTest.init loads AI models*" 2>&1 | tail -15
  ```
  Expected: FAIL — `loadSuperPoint` and `loadLowLightEnhancer` are never called.

- [x] **Step 3: Add model loading to ArViewModel.init**

  Open `feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/ArViewModel.kt`. Find the `init` block at line 173:

  ```kotlin
  init {
      NativeLibLoader.loadAll()
      viewModelScope.launch {
          projectRepository.currentProject.collect { project ->
  ```

  Add the model-loading coroutine as the first launch inside `init`:

  ```kotlin
  init {
      NativeLibLoader.loadAll()
      viewModelScope.launch(Dispatchers.IO) {
          slamManager.loadSuperPoint(appContext.assets)
          slamManager.loadLowLightEnhancer(appContext.assets)
      }
      viewModelScope.launch {
          projectRepository.currentProject.collect { project ->
  ```

- [x] **Step 4: Run test to confirm it passes**

  ```bash
  ./gradlew :feature:ar:testDebugUnitTest --tests "*ArViewModelTest.init loads AI models*" 2>&1 | tail -10
  ```
  Expected: PASS

- [ ] **Step 5: Run full AR test suite to check for regressions**

  ```bash
  ./gradlew :feature:ar:testDebugUnitTest 2>&1 | grep -E "FAIL|ERROR|tests"
  ```
  Expected: All tests pass.

- [ ] **Step 6: Commit**

  ```bash
  git add feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/ArViewModel.kt \
          feature/ar/src/test/java/com/hereliesaz/graffitixr/feature/ar/ArViewModelTest.kt
  git commit -m "feat: load SuperPoint and Zero-DCE++ models at AR ViewModel init"
  ```

---

## Task 8: Full build and on-device verification

**Files:** None — verification only.

- [ ] **Step 1: Full clean build**

  ```bash
  ./gradlew clean assembleDebug 2>&1 | tail -20
  ```
  Expected: `BUILD SUCCESSFUL`

- [ ] **Step 2: Install on a test device**

  ```bash
  ./gradlew installDebug
  ```

- [ ] **Step 3: Launch the app and check logcat for model loading**

  ```bash
  adb logcat -s SuperPoint LowLightEnhancer GraffitiJNI 2>&1 | head -30
  ```

  Expected lines (order may vary):
  ```
  D SuperPoint: using OpenCL backend      (or: OpenCL unavailable, using CPU backend)
  D LowLightEnhancer: Using OpenCL backend  (or: using CPU backend)
  ```

  If `zerodce.onnx not found in assets` appears, Task 0 Step 2 was not completed.

- [ ] **Step 4: Test low-light behavior in the field**

  In AR mode, scan a wall in dim conditions (ARCore light estimate below 0.35 — the existing UI shows "too dark" hint at `lightLevel < 0.3f` as a reference point). Observe whether the map builds up confidence faster than without the changes.

  In logcat, watch for SuperPoint detection activity:
  ```bash
  adb logcat -s SuperPoint MobileGS 2>&1
  ```

- [ ] **Step 5: Run all unit tests one final time**

  ```bash
  ./gradlew test 2>&1 | grep -E "FAIL|ERROR|BUILD"
  ```
  Expected: `BUILD SUCCESSFUL` with no failures.

---

## Implementation Notes

**Descriptor type compatibility:** The existing `mWallDescriptors` can hold ORB descriptors (`CV_8U`) from previously saved projects. The reloc loop guards against type mismatch by checking `mWallDescriptors.type() == CV_32F` before using SuperPoint. Old fingerprints continue to work with ORB; new ones produced after this change will use SuperPoint.

**Enhancement threshold:** `kLowLightThreshold = 0.35f` matches ARCore's `LightEstimate.getPixelIntensity()` scale (0 = dark, 1 = bright). Tune this constant in `MobileGS.h` if needed — the existing UI already warns at `< 0.3f`, so 0.35f gives a small processing head start before the user notices degradation.

**Performance on mid-range:** Enhancement only runs when `mLightLevel < 0.35f`, so overhead in normal light is zero. If profiling on a mid-range device shows the 11ms combined inference budget is tight, reduce enhancement frequency: run it every other frame by gating on `mFrameCounter % 2 == 0` and caching the last enhanced frame.
