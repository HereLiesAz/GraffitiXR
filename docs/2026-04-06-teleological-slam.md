# Teleological SLAM — Architecture Refactor & Freeze Preview Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Separate wall and artwork fingerprint stores in the native engine, fix 3D back-projection for wall features so PnP correction works from the moment a target is created, and add a Freeze preview screen that shows the user what CV is looking for.

**Architecture:** The C++ engine's single mixed fingerprint store (`mTargetDescriptors`/`mTargetKeypoints3D`) is split into `mWallDescriptors`/`mWallKeypoints3D` (localization, fed from target capture with real depth-back-projected 3D points) and `mArtworkDescriptors`/`mArtworkKeypoints3D` (painting progress, fed from layer bake — unchanged). The JNI layer gains `nativeSetWallFingerprint` which runs ORB and 3D back-projection inline then returns a populated `Fingerprint` object. The Kotlin side gains `ArViewModel.onFreezeRequested` which composites layers over the wall capture photo, annotates with ORB blobs, and surfaces the result in a new `FreezePreviewScreen`.

**Tech Stack:** C++17 (OpenCV 4, ORB), JNI (Android NDK), Kotlin (Coroutines, StateFlow, Hilt), Jetpack Compose, MockK for JVM tests.

---

## File Map

| File | Change |
|---|---|
| `core/nativebridge/src/main/cpp/include/MobileGS.h` | Rename `mTargetDescriptors`/`mTargetKeypoints3D` → `mWall*`; remove `setTargetFingerprint`; add `restoreWallFingerprint`; rename `addLayerFeatures` → `setArtworkFingerprint` |
| `core/nativebridge/src/main/cpp/MobileGS.cpp` | Rename all `mTarget*` refs; update `runPnPMatch`, `clearMap`, `tryUpdateFingerprint`; implement `restoreWallFingerprint`, `setArtworkFingerprint` (remove mixed append) |
| `core/nativebridge/src/main/cpp/GraffitiJNI.cpp` | Update `buildFingerprintObject` for 3D points; add `nativeSetWallFingerprint`, `nativeRestoreWallFingerprint`; rename `nativeAddLayerFeatures` → `nativeSetArtworkFingerprint`; remove `nativeGenerateFingerprint`, `nativeGenerateFingerprintMasked`, `nativeSetTargetFingerprint` |
| `core/nativebridge/src/main/java/com/hereliesaz/graffitixr/nativebridge/SlamManager.kt` | Add `setWallFingerprint`, `restoreWallFingerprint`, `setArtworkFingerprint`; remove old methods |
| `core/common/src/main/java/com/hereliesaz/graffitixr/common/model/UiState.kt` | Add `freezePreviewBitmap: Bitmap?` and `freezeDepthWarning: Boolean` to `ArUiState` |
| `feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/ArViewModel.kt` | Add `onFreezeRequested`, `onFreezeDismissed`, `onUnfreezeRequested`, `unfreezeRequested: SharedFlow`; rename `addLayerFeaturesToSLAM` → `setArtworkFingerprintFromComposite`; make `loadFingerprintIfExists` internal; call `restoreWallFingerprint` instead of `setTargetFingerprint` |
| `feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/FreezePreviewScreen.kt` | New composable |
| `feature/ar/src/test/java/com/hereliesaz/graffitixr/feature/ar/TeleologicalSlamTest.kt` | New test file for freeze + fingerprint restore tests |
| `app/src/main/java/com/hereliesaz/graffitixr/MainViewModel.kt` | Update `onConfirmTargetCreation` to use `setWallFingerprint` |
| `app/src/main/java/com/hereliesaz/graffitixr/MainActivity.kt` | Wire Freeze rail to `onFreezeRequested`; make `compositeLayersForAr` callable from here |
| `app/src/main/java/com/hereliesaz/graffitixr/MainScreen.kt` | Make `compositeLayersForAr` `internal`; show `FreezePreviewScreen` when `freezePreviewBitmap != null` |
| `app/src/test/java/com/hereliesaz/graffitixr/MainViewModelTest.kt` | Add `onConfirmTargetCreation` tests for new `setWallFingerprint` path |

---

## Task 1: Rename native fingerprint stores in MobileGS.h

**Files:**
- Modify: `core/nativebridge/src/main/cpp/include/MobileGS.h`

- [ ] **Step 1: Rename member variables and update API in the header**

In `MobileGS.h`, make these changes:

Remove:
```cpp
void setTargetFingerprint(const cv::Mat& descriptors, const std::vector<cv::Point3f>& points3d);
void addLayerFeatures(const cv::Mat& composite,
                      const uint8_t* depthData, int depthW, int depthH, int depthStride,
                      const float* intrinsics4,
                      const float* viewMat16);
```
```cpp
cv::Mat mTargetDescriptors;
std::vector<cv::Point3f> mTargetKeypoints3D;
```

Add:
```cpp
void restoreWallFingerprint(const cv::Mat& descriptors, const std::vector<cv::Point3f>& points3d);
void setArtworkFingerprint(const cv::Mat& composite,
                           const uint8_t* depthData, int depthW, int depthH, int depthStride,
                           const float* intrinsics4,
                           const float* viewMat16);
```
```cpp
cv::Mat mWallDescriptors;
std::vector<cv::Point3f> mWallKeypoints3D;
```

The `mArtworkDescriptors` / `mArtworkKeypoints3D` members stay as-is.

- [ ] **Step 2: Build to confirm header compiles**

```bash
./gradlew :core:nativebridge:assembleDebug 2>&1 | tail -20
```

Expected: Errors about `mTargetDescriptors` undefined in MobileGS.cpp (the .cpp still references the old names). That's expected — fix in next task.

---

## Task 2: Update MobileGS.cpp — rename refs + implement restoreWallFingerprint + setArtworkFingerprint

**Files:**
- Modify: `core/nativebridge/src/main/cpp/MobileGS.cpp`

- [ ] **Step 1: Rename all `mTargetDescriptors` → `mWallDescriptors` and `mTargetKeypoints3D` → `mWallKeypoints3D`**

These references appear in `runPnPMatch`, `clearMap`, `tryUpdateFingerprint`, `setTargetFingerprint`, and `addLayerFeatures`. Use find-and-replace. After replacing, verify no `mTargetDescriptors` or `mTargetKeypoints3D` remain:

```bash
grep -n "mTargetDescriptors\|mTargetKeypoints3D" core/nativebridge/src/main/cpp/MobileGS.cpp
```

Expected: no output.

- [ ] **Step 2: Rename `setTargetFingerprint` → `restoreWallFingerprint`**

Find the existing implementation:
```cpp
void MobileGS::setTargetFingerprint(const cv::Mat& descriptors, const std::vector<cv::Point3f>& points3d) {
    std::lock_guard<std::mutex> lock(mMutex);
    mTargetDescriptors = descriptors.clone();
    mTargetKeypoints3D = points3d;
}
```

Replace with (after the rename in Step 1, `mWallDescriptors`/`mWallKeypoints3D` are already in place):
```cpp
void MobileGS::restoreWallFingerprint(const cv::Mat& descriptors, const std::vector<cv::Point3f>& points3d) {
    std::lock_guard<std::mutex> lock(mMutex);
    mWallDescriptors = descriptors.clone();
    mWallKeypoints3D = points3d;
}
```

- [ ] **Step 3: Rename `addLayerFeatures` → `setArtworkFingerprint` and remove the mixed append**

Find the existing `addLayerFeatures` implementation (around line 1138). Replace the entire method body after `if (newPts.empty()) return;` with the clean version that writes only to artwork store:

```cpp
void MobileGS::setArtworkFingerprint(const cv::Mat& composite,
                                     const uint8_t* depthData, int depthW, int depthH, int depthStride,
                                     const float* intrinsics4,
                                     const float* viewMat16) {
    if (composite.empty() || !depthData) return;

    cv::Mat gray;
    if (composite.channels() == 4) {
        cv::cvtColor(composite, gray, cv::COLOR_RGBA2GRAY);
    } else if (composite.channels() == 3) {
        cv::cvtColor(composite, gray, cv::COLOR_RGB2GRAY);
    } else {
        gray = composite;
    }

    std::vector<cv::KeyPoint> kps;
    cv::Mat descs;
    cv::ORB::create(500)->detectAndCompute(gray, cv::noArray(), kps, descs);
    if (descs.empty()) return;

    const float fx = intrinsics4[0], fy = intrinsics4[1];
    const float cx = intrinsics4[2], cy = intrinsics4[3];
    const float scaleX = (float)depthW / composite.cols;
    const float scaleY = (float)depthH / composite.rows;

    std::vector<cv::Point3f> newPts;
    cv::Mat newDescs;

    for (int i = 0; i < (int)kps.size(); ++i) {
        int du = (int)(kps[i].pt.x * scaleX);
        int dv = (int)(kps[i].pt.y * scaleY);
        if (du < 0 || du >= depthW || dv < 0 || dv >= depthH) continue;

        const uint16_t raw = *reinterpret_cast<const uint16_t*>(
                depthData + dv * depthStride + du * 2);
        const uint16_t depthMm = raw & 0x1FFFu;
        if (depthMm == 0) continue;

        float d = depthMm / 1000.0f;
        if (d > 5.0f) continue;

        float u = kps[i].pt.x;
        float v = kps[i].pt.y;
        float xc = (u - cx) * d / fx;
        float yc = -(v - cy) * d / fy;
        float zc = -d;

        float xw, yw, zw;
        camToWorld(viewMat16, xc, yc, zc, xw, yw, zw);
        newPts.push_back(cv::Point3f(xw, yw, zw));
        newDescs.push_back(descs.row(i));
    }

    if (newPts.empty()) return;

    std::lock_guard<std::mutex> lock(mMutex);
    mArtworkDescriptors = newDescs.clone();
    mArtworkKeypoints3D = newPts;
    mPaintingProgress.store(0.0f, std::memory_order_relaxed);
}
```

- [ ] **Step 4: Update `clearMap` to clear both stores**

Find `clearMap` (around line 286). Ensure it clears both wall and artwork stores:
```cpp
void MobileGS::clearMap() {
    std::lock_guard<std::mutex> lock(mMutex);
    splatData.clear();
    mVoxelGrid.clear();
    mPointCount = 0;
    mWallDescriptors = cv::Mat();
    mWallKeypoints3D.clear();
    mArtworkDescriptors = cv::Mat();
    mArtworkKeypoints3D.clear();
    // ... rest of existing clearMap body
}
```

- [ ] **Step 5: Update `runPnPMatch` guard**

Find the guard at the top of `runPnPMatch` (around line 532):
```cpp
if (mWallDescriptors.empty() || mWallKeypoints3D.empty()) return;
targetDesc = mWallDescriptors.clone();
targetPts  = mWallKeypoints3D;
```

- [ ] **Step 6: Build the native module**

```bash
./gradlew :core:nativebridge:assembleDebug 2>&1 | grep -E "error:|warning:|BUILD"
```

Expected: `BUILD SUCCESSFUL`. Fix any compilation errors before continuing.

- [ ] **Step 7: Commit**

```bash
git add core/nativebridge/src/main/cpp/include/MobileGS.h \
        core/nativebridge/src/main/cpp/MobileGS.cpp
git commit -m "refactor(native): separate wall and artwork fingerprint stores in MobileGS"
```

---

## Task 3: Update GraffitiJNI.cpp

**Files:**
- Modify: `core/nativebridge/src/main/cpp/GraffitiJNI.cpp`

- [ ] **Step 1: Update `buildFingerprintObject` to accept and populate 3D points**

Replace the existing `buildFingerprintObject` function with this version that populates `ptsList` from a `std::vector<cv::Point3f>`:

```cpp
static jobject buildFingerprintObject(JNIEnv* env,
                                      const std::vector<cv::KeyPoint>& kps,
                                      const cv::Mat& descs,
                                      const std::vector<cv::Point3f>& pts3d = {}) {
    if (descs.empty()) return nullptr;

    jclass fpClass   = env->FindClass("com/hereliesaz/graffitixr/common/model/Fingerprint");
    jmethodID fpCtor = env->GetMethodID(fpClass, "<init>", "(Ljava/util/List;Ljava/util/List;[BIII)V");

    jclass kpClass   = env->FindClass("org/opencv/core/KeyPoint");
    jmethodID kpCtor = env->GetMethodID(kpClass, "<init>", "(FFFFFII)V");

    jclass listClass    = env->FindClass("java/util/ArrayList");
    jmethodID listCtor  = env->GetMethodID(listClass, "<init>", "(I)V");
    jmethodID addMethod = env->GetMethodID(listClass, "add", "(Ljava/lang/Object;)Z");

    jobject kpList = env->NewObject(listClass, listCtor, (jint)kps.size());
    for (const auto& kp : kps) {
        jobject jkp = env->NewObject(kpClass, kpCtor,
                                     kp.pt.x, kp.pt.y, kp.size, kp.angle, kp.response,
                                     (jint)kp.octave, (jint)kp.class_id);
        env->CallBooleanMethod(kpList, addMethod, jkp);
        env->DeleteLocalRef(jkp);
    }

    jclass floatClass  = env->FindClass("java/lang/Float");
    jmethodID floatValueOf = env->GetStaticMethodID(floatClass, "valueOf", "(F)Ljava/lang/Float;");
    jobject ptsList = env->NewObject(listClass, listCtor, (jint)(pts3d.size() * 3));
    for (const auto& p : pts3d) {
        for (float v : {p.x, p.y, p.z}) {
            jobject jf = env->CallStaticObjectMethod(floatClass, floatValueOf, v);
            env->CallBooleanMethod(ptsList, addMethod, jf);
            env->DeleteLocalRef(jf);
        }
    }

    jsize descSize = descs.total() * descs.elemSize();
    jbyteArray jDescArray = env->NewByteArray(descSize);
    env->SetByteArrayRegion(jDescArray, 0, descSize, (const jbyte*)descs.data);

    return env->NewObject(fpClass, fpCtor,
                          kpList, ptsList, jDescArray,
                          descs.rows, descs.cols, descs.type());
}
```

- [ ] **Step 2: Remove `nativeGenerateFingerprint`, `nativeGenerateFingerprintMasked`, `nativeSetTargetFingerprint`**

Delete these three JNI functions entirely:
- `Java_..._nativeGenerateFingerprint`
- `Java_..._nativeGenerateFingerprintMasked`
- `Java_..._nativeSetTargetFingerprint`

- [ ] **Step 3: Add `nativeSetWallFingerprint`**

This function runs ORB + depth back-projection on the wall capture image, stores the result via `restoreWallFingerprint`, and returns a populated Fingerprint object:

```cpp
JNIEXPORT jobject JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeSetWallFingerprint(
        JNIEnv* env, jobject thiz,
        jobject bitmap, jobject maskBitmap,
        jobject depthBuffer, jint depthW, jint depthH, jint depthStride,
        jfloatArray intrinsicsArray, jfloatArray viewMatArray) {

    if (!gSlamEngine) return nullptr;

    cv::Mat frame;
    bitmapToMat(env, bitmap, frame);
    if (frame.empty()) return nullptr;

    cv::Mat gray;
    cv::cvtColor(frame, gray, cv::COLOR_RGBA2GRAY);

    cv::Mat orbMask;
    if (maskBitmap != nullptr) {
        cv::Mat maskRgba;
        bitmapToMat(env, maskBitmap, maskRgba);
        if (!maskRgba.empty()) {
            cv::cvtColor(maskRgba, orbMask, cv::COLOR_RGBA2GRAY);
            if (orbMask.size() != gray.size()) {
                cv::resize(orbMask, orbMask, gray.size(), 0, 0, cv::INTER_NEAREST);
            }
        }
    }

    std::vector<cv::KeyPoint> kps;
    cv::Mat descs;
    cv::ORB::create(500)->detectAndCompute(gray, orbMask, kps, descs);
    if (descs.empty()) return nullptr;

    auto* rawDepth = static_cast<const uint8_t*>(env->GetDirectBufferAddress(depthBuffer));
    if (!rawDepth) return nullptr;

    jfloat* intr = env->GetFloatArrayElements(intrinsicsArray, nullptr);
    jfloat* view = env->GetFloatArrayElements(viewMatArray, nullptr);
    const float fx = intr[0], fy = intr[1], cx_f = intr[2], cy_f = intr[3];
    const float scaleX = (float)depthW / frame.cols;
    const float scaleY = (float)depthH / frame.rows;

    std::vector<cv::Point3f> wallPts;
    cv::Mat wallDescs;

    for (int i = 0; i < (int)kps.size(); ++i) {
        int du = (int)(kps[i].pt.x * scaleX);
        int dv = (int)(kps[i].pt.y * scaleY);
        if (du < 0 || du >= depthW || dv < 0 || dv >= depthH) continue;

        const uint16_t raw = *reinterpret_cast<const uint16_t*>(
                rawDepth + dv * depthStride + du * 2);
        const uint16_t depthMm = raw & 0x1FFFu;
        if (depthMm == 0) continue;

        float d = depthMm / 1000.0f;
        if (d > 5.0f) continue;

        float u = kps[i].pt.x;
        float v = kps[i].pt.y;
        float xc = (u - cx_f) * d / fx;
        float yc = -(v - cy_f) * d / fy;
        float zc = -d;

        // camToWorld: view is column-major, camera-to-world transform
        float xw = view[0]*xc + view[4]*yc + view[8]*zc  + view[12];
        float yw = view[1]*xc + view[5]*yc + view[9]*zc  + view[13];
        float zw = view[2]*xc + view[6]*yc + view[10]*zc + view[14];

        wallPts.push_back(cv::Point3f(xw, yw, zw));
        wallDescs.push_back(descs.row(i));
    }

    env->ReleaseFloatArrayElements(intrinsicsArray, intr, JNI_ABORT);
    env->ReleaseFloatArrayElements(viewMatArray, view, JNI_ABORT);

    if (wallPts.empty()) return nullptr;

    gSlamEngine->restoreWallFingerprint(wallDescs, wallPts);

    return buildFingerprintObject(env, kps, wallDescs, wallPts);
}
```

**Note on `camToWorld`:** The view matrix passed here is the camera-to-world pose matrix (inverse of the GL view matrix). The formula above transforms camera-space (xc, yc, zc) to world-space directly using it as a 4x4 column-major matrix. This matches the convention already used in the rest of the engine.

- [ ] **Step 4: Add `nativeRestoreWallFingerprint`**

This is the session-resume path — just stores pre-computed descriptors and 3D points:

```cpp
JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_nativeRestoreWallFingerprint(
        JNIEnv* env, jobject thiz, jbyteArray descArray, jint rows, jint cols, jint type, jfloatArray ptsArray) {
    if (gSlamEngine) {
        jbyte* descData = env->GetByteArrayElements(descArray, nullptr);
        cv::Mat descriptors(rows, cols, type, descData);

        jsize ptsLen = env->GetArrayLength(ptsArray);
        jfloat* ptsData = env->GetFloatArrayElements(ptsArray, nullptr);

        std::vector<cv::Point3f> points3d;
        for (int i = 0; i < ptsLen; i += 3) {
            points3d.push_back(cv::Point3f(ptsData[i], ptsData[i+1], ptsData[i+2]));
        }

        gSlamEngine->restoreWallFingerprint(descriptors, points3d);

        env->ReleaseByteArrayElements(descArray, descData, JNI_ABORT);
        env->ReleaseFloatArrayElements(ptsArray, ptsData, JNI_ABORT);
    }
}
```

- [ ] **Step 5: Rename `nativeAddLayerFeatures` → `nativeSetArtworkFingerprint`**

Find the existing `Java_..._nativeAddLayerFeatures` function. Rename the JNI function name to `Java_..._nativeSetArtworkFingerprint` and update the internal call from `gSlamEngine->addLayerFeatures(...)` to `gSlamEngine->setArtworkFingerprint(...)`.

- [ ] **Step 6: Build**

```bash
./gradlew :core:nativebridge:assembleDebug 2>&1 | grep -E "error:|BUILD"
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit**

```bash
git add core/nativebridge/src/main/cpp/GraffitiJNI.cpp
git commit -m "refactor(jni): add nativeSetWallFingerprint with 3D back-projection; rename artwork fingerprint path"
```

---

## Task 4: Update SlamManager.kt

**Files:**
- Modify: `core/nativebridge/src/main/java/com/hereliesaz/graffitixr/nativebridge/SlamManager.kt`

- [ ] **Step 1: Remove old methods and their external declarations**

Remove these Kotlin methods and their corresponding `private external fun` declarations:
- `generateFingerprint(bitmap: Bitmap): Fingerprint?`
- `generateFingerprintMasked(bitmap: Bitmap, mask: Bitmap?): Fingerprint?`
- `setTargetFingerprint(descriptorsData: ByteArray, rows: Int, cols: Int, type: Int, points3d: FloatArray)`
- `private external fun nativeGenerateFingerprint(bitmap: Bitmap): Fingerprint?`
- `private external fun nativeGenerateFingerprintMasked(bitmap: Bitmap, mask: Bitmap): Fingerprint?`
- `private external fun nativeSetTargetFingerprint(descriptorsData: ByteArray, rows: Int, cols: Int, type: Int, points3d: FloatArray)`

- [ ] **Step 2: Add new methods**

Add these methods and their external declarations:

```kotlin
fun setWallFingerprint(
    bitmap: Bitmap,
    mask: Bitmap?,
    depthBuffer: ByteBuffer,
    depthW: Int, depthH: Int, depthStride: Int,
    intrinsics: FloatArray,
    viewMatrix: FloatArray
): Fingerprint? {
    if (!depthBuffer.isDirect) return null
    return nativeSetWallFingerprint(bitmap, mask, depthBuffer, depthW, depthH, depthStride, intrinsics, viewMatrix)
}

fun restoreWallFingerprint(descriptorsData: ByteArray, rows: Int, cols: Int, type: Int, points3d: FloatArray) {
    nativeRestoreWallFingerprint(descriptorsData, rows, cols, type, points3d)
}
```

- [ ] **Step 3: Rename `addLayerFeatures` → `setArtworkFingerprint`**

Rename the Kotlin wrapper method and its `private external fun` declaration:

```kotlin
fun setArtworkFingerprint(
    bitmap: Bitmap,
    depthBuffer: ByteBuffer,
    depthW: Int, depthH: Int, depthStride: Int,
    intrinsics: FloatArray,
    viewMatrix: FloatArray
) {
    if (depthBuffer.isDirect) {
        nativeSetArtworkFingerprint(bitmap, depthBuffer, depthW, depthH, depthStride, intrinsics, viewMatrix)
    }
}
```

External declarations to add:
```kotlin
private external fun nativeSetWallFingerprint(
    bitmap: Bitmap, mask: Bitmap?,
    depthBuffer: ByteBuffer,
    depthW: Int, depthH: Int, depthStride: Int,
    intrinsics: FloatArray, viewMatrix: FloatArray
): Fingerprint?

private external fun nativeRestoreWallFingerprint(
    descriptorsData: ByteArray, rows: Int, cols: Int, type: Int, points3d: FloatArray
)

private external fun nativeSetArtworkFingerprint(
    bitmap: Bitmap, depthBuffer: ByteBuffer,
    depthW: Int, depthH: Int, depthStride: Int,
    intrinsics: FloatArray, viewMatrix: FloatArray
)
```

- [ ] **Step 4: Build**

```bash
./gradlew :core:nativebridge:assembleDebug 2>&1 | grep -E "error:|BUILD"
```

Expected: `BUILD SUCCESSFUL`. If you see `Unresolved reference` errors in other modules — those modules still call the old methods. Fix in subsequent tasks.

- [ ] **Step 5: Commit**

```bash
git add core/nativebridge/src/main/java/com/hereliesaz/graffitixr/nativebridge/SlamManager.kt
git commit -m "refactor(slam-manager): replace fingerprint API with setWallFingerprint / restoreWallFingerprint / setArtworkFingerprint"
```

---

## Task 5: Update MainViewModel — onConfirmTargetCreation (TDD)

**Files:**
- Modify: `app/src/test/java/com/hereliesaz/graffitixr/MainViewModelTest.kt`
- Modify: `app/src/main/java/com/hereliesaz/graffitixr/MainViewModel.kt`

- [ ] **Step 1: Write failing tests**

Add to `MainViewModelTest.kt`:

```kotlin
import android.graphics.Bitmap
import com.hereliesaz.graffitixr.common.model.Fingerprint
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import java.nio.ByteBuffer
import org.junit.Assert.assertNotNull

@Test
fun `onConfirmTargetCreation calls setWallFingerprint and saves project`() = runTest {
    val mockBitmap = mockk<Bitmap>(relaxed = true) {
        every { width } returns 1920
        every { height } returns 1080
        every { copy(any(), any()) } returns this
    }
    val mockFp = mockk<Fingerprint>(relaxed = true) {
        every { points3d } returns listOf(1f, 2f, 3f)
    }
    val depthBuffer = ByteBuffer.allocateDirect(100)
    val intrinsics = floatArrayOf(1000f, 1000f, 960f, 540f)
    val viewMatrix = FloatArray(16) { 0f }.also {
        it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f
    }
    every { projectRepository.currentProject } returns MutableStateFlow(
        mockk(relaxed = true) { every { id } returns "proj-1" }
    )
    every { slamManager.setWallFingerprint(any(), any(), any(), any(), any(), any(), any(), any()) } returns mockFp

    viewModel.onConfirmTargetCreation(
        bitmap = mockBitmap,
        selectionMask = null,
        depthBuffer = depthBuffer,
        depthW = 10, depthH = 10, depthStride = 20,
        intrinsics = intrinsics,
        viewMatrix = viewMatrix
    )
    testDispatcher.scheduler.advanceUntilIdle()

    verify { slamManager.setWallFingerprint(any(), null, depthBuffer, 10, 10, 20, intrinsics, viewMatrix) }
    coVerify { projectManager.saveProject(any(), any(), any()) }
}

@Test
fun `onConfirmTargetCreation shows toast when no wall features found`() = runTest {
    val mockBitmap = mockk<Bitmap>(relaxed = true) {
        every { width } returns 1920
        every { height } returns 1080
        every { copy(any(), any()) } returns this
    }
    every { projectRepository.currentProject } returns MutableStateFlow(
        mockk(relaxed = true) { every { id } returns "proj-1" }
    )
    every { slamManager.setWallFingerprint(any(), any(), any(), any(), any(), any(), any(), any()) } returns null

    viewModel.onConfirmTargetCreation(
        bitmap = mockBitmap, selectionMask = null,
        depthBuffer = ByteBuffer.allocateDirect(100),
        depthW = 10, depthH = 10, depthStride = 20,
        intrinsics = floatArrayOf(1000f, 1000f, 960f, 540f),
        viewMatrix = FloatArray(16)
    )
    testDispatcher.scheduler.advanceUntilIdle()

    coVerify(exactly = 0) { projectManager.saveProject(any(), any(), any()) }
}
```

- [ ] **Step 2: Run tests to confirm they fail**

```bash
./gradlew :app:testDebugUnitTest --tests "com.hereliesaz.graffitixr.MainViewModelTest.onConfirmTargetCreation*" 2>&1 | tail -20
```

Expected: compilation error — `onConfirmTargetCreation` doesn't accept the new parameters yet.

- [ ] **Step 3: Update `MainViewModel.onConfirmTargetCreation`**

Replace the current signature and body in `MainViewModel.kt`:

```kotlin
fun onConfirmTargetCreation(
    bitmap: Bitmap? = null,
    selectionMask: Bitmap? = null,
    depthBuffer: ByteBuffer? = null,
    depthW: Int = 0,
    depthH: Int = 0,
    depthStride: Int = 0,
    intrinsics: FloatArray? = null,
    viewMatrix: FloatArray? = null
) {
    _uiState.update {
        it.copy(isCapturingTarget = false, captureStep = CaptureStep.NONE, planeConfirmationPending = true)
    }
    bitmap ?: return
    val safeDepth = depthBuffer ?: return
    val safeIntr = intrinsics ?: return
    val safeView = viewMatrix ?: return

    viewModelScope.launch(Dispatchers.IO) {
        val currentProject = projectRepository.currentProject.value ?: return@launch

        val isRotatedForUi = bitmap.height > bitmap.width
        val sensorBmp = if (isRotatedForUi) {
            val matrix = android.graphics.Matrix().apply { postRotate(-90f) }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } else {
            bitmap
        }

        val sensorMask = if (isRotatedForUi && selectionMask != null) {
            val matrix = android.graphics.Matrix().apply { postRotate(-90f) }
            Bitmap.createBitmap(selectionMask, 0, 0, selectionMask.width, selectionMask.height, matrix, true)
        } else {
            selectionMask
        }

        val fp = slamManager.setWallFingerprint(
            sensorBmp, sensorMask, safeDepth,
            depthW, depthH, depthStride,
            safeIntr, safeView
        )

        if (fp == null) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Target lacks visual detail. Please use a surface with more contrast.", Toast.LENGTH_LONG).show()
            }
            return@launch
        }

        projectManager.saveProject(
            context = context,
            projectData = currentProject.copy(fingerprint = fp),
            targetImages = listOf(bitmap)
        )

        projectRepository.loadProject(currentProject.id)

        withContext(Dispatchers.Main) {
            Toast.makeText(context, "Target Saved & Locked", Toast.LENGTH_SHORT).show()
        }
    }
}
```

- [ ] **Step 4: Run tests**

```bash
./gradlew :app:testDebugUnitTest --tests "com.hereliesaz.graffitixr.MainViewModelTest.onConfirmTargetCreation*" 2>&1 | tail -20
```

Expected: both tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/hereliesaz/graffitixr/MainViewModel.kt \
        app/src/test/java/com/hereliesaz/graffitixr/MainViewModelTest.kt
git commit -m "feat: update onConfirmTargetCreation to use setWallFingerprint with depth back-projection"
```

---

## Task 6: Update TargetCreationFlow callback signature

**Files:**
- Modify: `feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/TargetCreationFlow.kt`

The `onConfirm` callback in `TargetCreationUi` must carry depth + intrinsics + viewMatrix alongside the bitmap and mask, so `MainActivity` can forward them to `MainViewModel.onConfirmTargetCreation`.

- [ ] **Step 1: Update `TargetCreationUi` signature**

In `TargetCreationFlow.kt`, change the `onConfirm` parameter type:

```kotlin
// OLD:
onConfirm: (Bitmap?, Bitmap?) -> Unit,

// NEW:
onConfirm: (bitmap: Bitmap?, mask: Bitmap?, depthBuffer: java.nio.ByteBuffer?, depthW: Int, depthH: Int, depthStride: Int, intrinsics: FloatArray?, viewMatrix: FloatArray?) -> Unit,
```

- [ ] **Step 2: Update the `FeatureSelectionReview` confirm button call site**

In `FeatureSelectionReview`, the confirm FAB calls `onConfirm(mask)`. This needs to expand. But `FeatureSelectionReview` doesn't have access to depth data — it only produces the mask. Pass depth data through from `TargetCreationUi` to `FeatureSelectionReview`.

Add parameters to `FeatureSelectionReview`:
```kotlin
private fun FeatureSelectionReview(
    annotatedBitmap: Bitmap?,
    rawBitmap: Bitmap?,
    depthBuffer: java.nio.ByteBuffer?,
    depthW: Int, depthH: Int, depthStride: Int,
    intrinsics: FloatArray?,
    viewMatrix: FloatArray?,
    isAnnotating: Boolean,
    canUndo: Boolean,
    canRedo: Boolean,
    onConfirm: (mask: Bitmap?, depthBuffer: java.nio.ByteBuffer?, depthW: Int, depthH: Int, depthStride: Int, intrinsics: FloatArray?, viewMatrix: FloatArray?) -> Unit,
    // ... rest of params unchanged
)
```

Update the confirm FAB inside `FeatureSelectionReview`:
```kotlin
FloatingActionButton(
    onClick = {
        val mask = if (strokes.isEmpty()) null
                   else rasterizeStrokes(strokes, rawBitmap?.width ?: 512, rawBitmap?.height ?: 512)
        onConfirm(mask, depthBuffer, depthW, depthH, depthStride, intrinsics, viewMatrix)
    },
    containerColor = MaterialTheme.colorScheme.primaryContainer
) {
    Icon(Icons.Default.Check, contentDescription = "Confirm")
}
```

Update the `TargetCreationUi` `CaptureStep.REVIEW` block to pass depth data into `FeatureSelectionReview`:
```kotlin
CaptureStep.REVIEW -> {
    FeatureSelectionReview(
        annotatedBitmap = uiState.annotatedCaptureBitmap,
        rawBitmap = uiState.tempCaptureBitmap,
        depthBuffer = uiState.targetDepthBuffer,
        depthW = uiState.targetDepthBufferWidth,
        depthH = uiState.targetDepthBufferHeight,
        depthStride = uiState.targetDepthStride,
        intrinsics = uiState.targetIntrinsics,
        viewMatrix = uiState.targetCaptureViewMatrix,
        isAnnotating = uiState.annotatedCaptureBitmap == null && uiState.tempCaptureBitmap != null,
        canUndo = uiState.canUndoErase,
        canRedo = uiState.canRedoErase,
        onConfirm = { mask, depth, dw, dh, ds, intr, view ->
            onConfirm(uiState.tempCaptureBitmap, mask, depth, dw, dh, ds, intr, view)
        },
        onRetake = onRetake,
        onBeginErase = onBeginErase,
        onEraseAtPoint = onEraseAtPoint,
        onUndoErase = onUndoErase,
        onRedoErase = onRedoErase
    )
}
```

- [ ] **Step 3: Update the call site in `MainActivity`**

Find where `onConfirm` is passed to `TargetCreationUi`. Update the lambda to forward the new parameters to `mainViewModel.onConfirmTargetCreation`:

```kotlin
onConfirm = { bitmap, mask, depth, dw, dh, ds, intr, view ->
    mainViewModel.onConfirmTargetCreation(bitmap, mask, depth, dw, dh, ds, intr, view)
},
```

- [ ] **Step 4: Build**

```bash
./gradlew assembleDebug 2>&1 | grep -E "error:|BUILD"
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/TargetCreationFlow.kt \
        app/src/main/java/com/hereliesaz/graffitixr/MainActivity.kt
git commit -m "feat: pass depth buffer through TargetCreationFlow onConfirm to MainViewModel"
```

---

## Task 7: Update ArViewModel — rename addLayerFeaturesToSLAM + update loadFingerprintIfExists (TDD)

**Files:**
- Modify: `feature/ar/src/test/java/com/hereliesaz/graffitixr/feature/ar/ArViewModelTest.kt`
- Modify: `feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/ArViewModel.kt`

- [ ] **Step 1: Write failing test for `loadFingerprintIfExists`**

Add to `ArViewModelTest.kt`:

```kotlin
import com.hereliesaz.graffitixr.common.model.Fingerprint
import com.hereliesaz.graffitixr.domain.model.GraffitiProject
import io.mockk.coVerify
import io.mockk.verify

@Test
fun `loadFingerprintIfExists calls restoreWallFingerprint when project has fingerprint`() = runTest {
    val fp = Fingerprint(
        keypoints = emptyList(),
        points3d = listOf(1f, 2f, 3f),
        descriptorsData = byteArrayOf(1, 2, 3),
        descriptorsRows = 1, descriptorsCols = 3, descriptorsType = 0
    )
    val project = mockk<GraffitiProject>(relaxed = true) {
        every { fingerprint } returns fp
        every { id } returns "test-id"
    }
    every { projectRepository.currentProject } returns MutableStateFlow(project)

    // Re-create viewModel so init block fires with the new project
    viewModel = ArViewModel(slamManager, stereoProvider, projectRepository, settingsRepository, context)
    testDispatcher.scheduler.advanceUntilIdle()

    // loadFingerprintIfExists is called from resumeArSessionInternal (session-gated).
    // Call it directly via the internal accessor.
    viewModel.loadFingerprintIfExists()
    testDispatcher.scheduler.advanceUntilIdle()

    verify {
        slamManager.restoreWallFingerprint(
            fp.descriptorsData, fp.descriptorsRows, fp.descriptorsCols,
            fp.descriptorsType, fp.points3d.toFloatArray()
        )
    }
}
```

- [ ] **Step 2: Run test to confirm it fails**

```bash
./gradlew :feature:ar:testDebugUnitTest --tests "*.ArViewModelTest.loadFingerprintIfExists*" 2>&1 | tail -20
```

Expected: compilation error — `loadFingerprintIfExists()` not accessible (it's private) and `restoreWallFingerprint` doesn't exist on mock yet.

- [ ] **Step 3: Update ArViewModel**

In `ArViewModel.kt`:

**a) Make `loadFingerprintIfExists` internal:**
```kotlin
internal fun loadFingerprintIfExists() {
    val fp = projectRepository.currentProject.value?.fingerprint ?: return
    viewModelScope.launch(Dispatchers.IO) {
        slamManager.restoreWallFingerprint(
            fp.descriptorsData,
            fp.descriptorsRows,
            fp.descriptorsCols,
            fp.descriptorsType,
            fp.points3d.toFloatArray()
        )
    }
}
```

**b) Rename `addLayerFeaturesToSLAM` → `setArtworkFingerprintFromComposite`** and update the `slamManager` call:
```kotlin
private fun setArtworkFingerprintFromComposite(bitmap: Bitmap) {
    val state = _uiState.value
    val viewMat = state.targetCaptureViewMatrix ?: return
    val depthBuffer = state.targetDepthBuffer
    if (depthBuffer == null || depthBuffer.capacity() == 0) {
        Timber.w("setArtworkFingerprintFromComposite: no depth data available; skipping feature baking")
        return
    }
    val depthW = state.targetDepthBufferWidth
    val depthH = state.targetDepthBufferHeight
    val depthStride = state.targetDepthStride
    val intrinsics = state.targetIntrinsics ?: FloatArray(0)

    viewModelScope.launch(Dispatchers.IO) {
        slamManager.setArtworkFingerprint(
            bitmap = bitmap,
            depthBuffer = depthBuffer,
            depthW = depthW,
            depthH = depthH,
            depthStride = depthStride,
            intrinsics = intrinsics,
            viewMatrix = viewMat
        )
    }
}
```

**c) Update `updatePaintingGuide` to call the renamed method:**
```kotlin
fun updatePaintingGuide(composite: android.graphics.Bitmap) {
    setArtworkFingerprintFromComposite(composite)
}
```

- [ ] **Step 4: Run tests**

```bash
./gradlew :feature:ar:testDebugUnitTest --tests "*.ArViewModelTest.loadFingerprintIfExists*" 2>&1 | tail -20
```

Expected: PASS.

- [ ] **Step 5: Build full project**

```bash
./gradlew assembleDebug 2>&1 | grep -E "error:|BUILD"
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/ArViewModel.kt \
        feature/ar/src/test/java/com/hereliesaz/graffitixr/feature/ar/ArViewModelTest.kt
git commit -m "refactor: rename addLayerFeaturesToSLAM; use restoreWallFingerprint in loadFingerprintIfExists"
```

---

## Task 8: Add ArUiState fields + ArViewModel freeze methods (TDD)

**Files:**
- Modify: `core/common/src/main/java/com/hereliesaz/graffitixr/common/model/UiState.kt`
- Create: `feature/ar/src/test/java/com/hereliesaz/graffitixr/feature/ar/TeleologicalSlamTest.kt`
- Modify: `feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/ArViewModel.kt`

- [ ] **Step 1: Add fields to `ArUiState`**

In `UiState.kt`, add to `ArUiState` data class:
```kotlin
// Freeze preview — non-null while FreezePreviewScreen is shown
val freezePreviewBitmap: Bitmap? = null,
// True when target was captured without depth data; shown as banner in FreezePreviewScreen
val freezeDepthWarning: Boolean = false,
```

Import `android.graphics.Bitmap` at the top of the file if not already present.

- [ ] **Step 2: Write failing freeze tests**

Create `feature/ar/src/test/java/com/hereliesaz/graffitixr/feature/ar/TeleologicalSlamTest.kt`:

```kotlin
package com.hereliesaz.graffitixr.feature.ar

import android.content.Context
import android.graphics.Bitmap
import com.hereliesaz.graffitixr.common.model.ArScanMode
import com.hereliesaz.graffitixr.domain.repository.ProjectRepository
import com.hereliesaz.graffitixr.domain.repository.SettingsRepository
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import com.hereliesaz.graffitixr.nativebridge.depth.StereoDepthProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class TeleologicalSlamTest {

    private lateinit var viewModel: ArViewModel
    private val slamManager: SlamManager = mockk(relaxed = true)
    private val stereoProvider: StereoDepthProvider = mockk(relaxed = true)
    private val projectRepository: ProjectRepository = mockk(relaxed = true)
    private val settingsRepository: SettingsRepository = mockk(relaxed = true)
    private val context: Context = mockk(relaxed = true)
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        every { settingsRepository.arScanMode } returns flowOf(ArScanMode.CLOUD_POINTS)
        every { settingsRepository.isRightHanded } returns flowOf(true)
        every { settingsRepository.showAnchorBoundary } returns flowOf(false)
        every { settingsRepository.isImperialUnits } returns flowOf(false)
        every { projectRepository.currentProject } returns MutableStateFlow(null)
        every { context.filesDir } returns File("/tmp")
        viewModel = ArViewModel(slamManager, stereoProvider, projectRepository, settingsRepository, context)
    }

    @After
    fun tearDown() {
        viewModel.viewModelScope.cancel()
        Thread.sleep(100)
        Dispatchers.resetMain()
    }

    @Test
    fun `onFreezeRequested sets freezePreviewBitmap after annotating`() = runTest {
        val mockComposite = mockk<Bitmap>(relaxed = true)
        val mockAnnotated = mockk<Bitmap>(relaxed = true)
        every { slamManager.annotateKeypoints(any()) } returns mockAnnotated

        viewModel.onFreezeRequested(mockComposite)
        advanceUntilIdle()

        assertEquals(mockAnnotated, viewModel.uiState.value.freezePreviewBitmap)
    }

    @Test
    fun `onFreezeRequested sets freezeDepthWarning when no depth data`() = runTest {
        val mockComposite = mockk<Bitmap>(relaxed = true)
        every { slamManager.annotateKeypoints(any()) } returns mockk(relaxed = true)
        // targetDepthBuffer is null by default in ArUiState

        viewModel.onFreezeRequested(mockComposite)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.freezeDepthWarning)
    }

    @Test
    fun `onFreezeDismissed clears freezePreviewBitmap`() = runTest {
        val mockComposite = mockk<Bitmap>(relaxed = true)
        every { slamManager.annotateKeypoints(any()) } returns mockk(relaxed = true)
        viewModel.onFreezeRequested(mockComposite)
        advanceUntilIdle()
        assertNotNull(viewModel.uiState.value.freezePreviewBitmap)

        viewModel.onFreezeDismissed()

        assertNull(viewModel.uiState.value.freezePreviewBitmap)
    }

    @Test
    fun `onUnfreezeRequested emits event and clears freezePreviewBitmap`() = runTest {
        val events = mutableListOf<Unit>()
        val job = launch { viewModel.unfreezeRequested.collect { events.add(it) } }

        val mockComposite = mockk<Bitmap>(relaxed = true)
        every { slamManager.annotateKeypoints(any()) } returns mockk(relaxed = true)
        viewModel.onFreezeRequested(mockComposite)
        advanceUntilIdle()

        viewModel.onUnfreezeRequested()
        advanceUntilIdle()

        assertTrue(events.isNotEmpty())
        assertNull(viewModel.uiState.value.freezePreviewBitmap)
        job.cancel()
    }
}
```

- [ ] **Step 3: Run tests to confirm they fail**

```bash
./gradlew :feature:ar:testDebugUnitTest --tests "*.TeleologicalSlamTest" 2>&1 | tail -20
```

Expected: compilation errors — `onFreezeRequested`, `onFreezeDismissed`, `onUnfreezeRequested`, `unfreezeRequested` don't exist yet.

- [ ] **Step 4: Implement freeze methods in ArViewModel**

Add to `ArViewModel.kt`:

```kotlin
// Emits Unit when the user taps Unfreeze — observed by MainActivity to call toggleImageLock()
val unfreezeRequested: SharedFlow<Unit> = MutableSharedFlow<Unit>().also { _unfreezeRequested = it }
private lateinit var _unfreezeRequested: MutableSharedFlow<Unit>
```

Actually use this pattern to avoid lateinit:
```kotlin
private val _unfreezeRequested = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
val unfreezeRequested: SharedFlow<Unit> = _unfreezeRequested.asSharedFlow()
```

Add the methods:

```kotlin
fun onFreezeRequested(composite: Bitmap) {
    val state = _uiState.value
    val depthWarning = state.targetDepthBuffer == null || state.targetDepthBuffer.capacity() == 0

    viewModelScope.launch(Dispatchers.Default) {
        val annotated = slamManager.annotateKeypoints(composite)
        _uiState.update { it.copy(freezePreviewBitmap = annotated, freezeDepthWarning = depthWarning) }
    }
}

fun onFreezeDismissed() {
    _uiState.update { it.copy(freezePreviewBitmap = null) }
}

fun onUnfreezeRequested() {
    _uiState.update { it.copy(freezePreviewBitmap = null) }
    viewModelScope.launch { _unfreezeRequested.emit(Unit) }
}
```

Add missing imports:
```kotlin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
```

- [ ] **Step 5: Run tests**

```bash
./gradlew :feature:ar:testDebugUnitTest --tests "*.TeleologicalSlamTest" 2>&1 | tail -20
```

Expected: all 4 tests pass.

- [ ] **Step 6: Run full AR test suite**

```bash
./gradlew :feature:ar:testDebugUnitTest 2>&1 | tail -20
```

Expected: all tests pass.

- [ ] **Step 7: Commit**

```bash
git add core/common/src/main/java/com/hereliesaz/graffitixr/common/model/UiState.kt \
        feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/ArViewModel.kt \
        feature/ar/src/test/java/com/hereliesaz/graffitixr/feature/ar/TeleologicalSlamTest.kt
git commit -m "feat: add freeze preview state and ArViewModel freeze methods"
```

---

## Task 9: Create FreezePreviewScreen composable

**Files:**
- Create: `feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/FreezePreviewScreen.kt`

- [ ] **Step 1: Create the composable**

```kotlin
// FILE: feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/FreezePreviewScreen.kt
package com.hereliesaz.graffitixr.feature.ar

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Fullscreen overlay shown after the user taps Freeze.
 * Displays the annotated composite (wall + artwork + ORB feature blobs) so the
 * user can see exactly what the teleological SLAM engine will match against.
 *
 * [onDismiss] — user tapped "Got it", stays frozen.
 * [onUnfreeze] — user tapped "Unfreeze", undo layer lock and return to editing.
 */
@Composable
fun FreezePreviewScreen(
    annotatedBitmap: Bitmap,
    showDepthWarning: Boolean,
    onDismiss: () -> Unit,
    onUnfreeze: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {

        // Full-screen annotated image
        Image(
            bitmap = annotatedBitmap.asImageBitmap(),
            contentDescription = "Teleological SLAM target",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit
        )

        // Dark scrim so blobs pop
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
        )

        // Re-draw image over scrim
        Image(
            bitmap = annotatedBitmap.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit
        )

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 24.dp, start = 16.dp, end = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Teleological SLAM Target",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "Green blobs are the features the engine will match against in real time.",
                color = Color.White.copy(alpha = 0.8f),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center
            )

            if (showDepthWarning) {
                Box(
                    modifier = Modifier
                        .background(Color(0xEECC4400), RoundedCornerShape(8.dp))
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "No depth data from target capture — teleological correction may be reduced.",
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(32.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            FloatingActionButton(
                onClick = onUnfreeze,
                containerColor = MaterialTheme.colorScheme.errorContainer
            ) {
                Text("Unfreeze")
            }
            FloatingActionButton(
                onClick = onDismiss,
                containerColor = MaterialTheme.colorScheme.primaryContainer
            ) {
                Text("Got it")
            }
        }
    }
}
```

- [ ] **Step 2: Build**

```bash
./gradlew :feature:ar:assembleDebug 2>&1 | grep -E "error:|BUILD"
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/FreezePreviewScreen.kt
git commit -m "feat: add FreezePreviewScreen composable"
```

---

## Task 10: Wire Freeze rail + show FreezePreviewScreen

**Files:**
- Modify: `app/src/main/java/com/hereliesaz/graffitixr/MainScreen.kt`
- Modify: `app/src/main/java/com/hereliesaz/graffitixr/MainActivity.kt`

- [ ] **Step 1: Make `compositeLayersForAr` internal in MainScreen.kt**

Change its visibility from `private` to `internal`:
```kotlin
internal fun compositeLayersForAr(layers: List<Layer>): AndroidBitmap {
```

- [ ] **Step 2: Show `FreezePreviewScreen` in `MainScreen.kt`**

In the `ArViewport` composable (or wherever `ArUiState` is rendered in `MainScreen.kt`), add after the `AndroidView` for the GL surface:

```kotlin
// Freeze preview — shown when user freezes layers
arUiState.freezePreviewBitmap?.let { annotated ->
    FreezePreviewScreen(
        annotatedBitmap = annotated,
        showDepthWarning = arUiState.freezeDepthWarning,
        onDismiss = { arViewModel.onFreezeDismissed() },
        onUnfreeze = { arViewModel.onUnfreezeRequested() }
    )
}
```

Add the import: `import com.hereliesaz.graffitixr.feature.ar.FreezePreviewScreen`

- [ ] **Step 3: Wire the Freeze rail tap in `MainActivity.kt`**

Find the freeze rail item block (around line 1328):

```kotlin
val lockText = if (editorUiState.editorMode == EditorMode.TRACE) "Lock" else "Freeze"
val lockAction: () -> Unit = if (editorUiState.editorMode == EditorMode.TRACE) {
    { mainViewModel.setTouchLocked(true) }
} else {
    { editorViewModel.toggleImageLock() }
}
```

Replace the `else` branch with both actions:
```kotlin
val lockAction: () -> Unit = if (editorUiState.editorMode == EditorMode.TRACE) {
    { mainViewModel.setTouchLocked(true) }
} else {
    {
        editorViewModel.toggleImageLock()
        val visibleLayers = editorUiState.layers.filter { it.isVisible && it.bitmap != null }
        if (visibleLayers.isNotEmpty()) {
            val composite = compositeLayersForAr(visibleLayers)
            arViewModel.onFreezeRequested(composite)
        }
    }
}
```

- [ ] **Step 4: Observe `unfreezeRequested` in `MainActivity.kt`**

In `MainActivity`'s `LaunchedEffect` or `SideEffect` block (wherever other ViewModel events are observed), add:

```kotlin
LaunchedEffect(Unit) {
    arViewModel.unfreezeRequested.collect {
        editorViewModel.toggleImageLock()
    }
}
```

This undoes the layer lock when the user taps Unfreeze in the preview.

- [ ] **Step 5: Build**

```bash
./gradlew assembleDebug 2>&1 | grep -E "error:|BUILD"
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Run all unit tests**

```bash
./gradlew testDebugUnitTest 2>&1 | tail -30
```

Expected: all tests pass.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/hereliesaz/graffitixr/MainScreen.kt \
        app/src/main/java/com/hereliesaz/graffitixr/MainActivity.kt
git commit -m "feat: wire Freeze rail to onFreezeRequested; show FreezePreviewScreen in AR viewport"
```

---

## Task 11: Full build + test verification

- [ ] **Step 1: Run all module unit tests**

```bash
./gradlew testDebugUnitTest 2>&1 | grep -E "tests were|FAILED|error" | tail -20
```

Expected: all tests pass, no failures.

- [ ] **Step 2: Build debug APK**

```bash
./gradlew assembleDebug 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Run lint**

```bash
./gradlew lintDebug 2>&1 | grep -E "error:|warning:" | head -20
```

Fix any new lint errors introduced by this work.

- [ ] **Step 4: Final commit**

```bash
git add -p  # review any remaining unstaged changes
git commit -m "chore: post-refactor lint fixes and build verification"
```

---

## Native Verification (manual, device required)

After installing the debug APK:

1. Create a new project, scan a wall, capture a target on a depth-capable device.
2. In the native log (adb logcat), confirm `GraffitiJNI: nativeSetWallFingerprint` produces 3D points (non-zero `wallPts` count).
3. Confirm `runPnPMatch` no longer returns early — look for anchor interpolation messages.
4. Switch to the editor, add a layer, go back to AR. Tap Freeze. Confirm the `FreezePreviewScreen` appears with green ORB blobs on wall + artwork.
5. Tap "Got it". Confirm the preview dismisses and the AR view returns.
6. Tap Freeze again, then "Unfreeze". Confirm the layer lock is undone.
