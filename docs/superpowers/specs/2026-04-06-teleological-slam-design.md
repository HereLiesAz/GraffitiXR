# Teleological SLAM — Architecture Refactor & Freeze Preview

**Date:** 2026-04-06
**Status:** Approved for implementation

---

## Problem Statement

The teleological SLAM pipeline has two concrete correctness bugs and one missing UX feature.

**Bug 1 — Wall fingerprint has empty 3D points.**
`generateFingerprintMasked` runs ORB on the wall capture image but never back-projects keypoints into world space. The resulting `Fingerprint.points3d` is always empty. `MobileGS::runPnPMatch` guards on `mTargetKeypoints3D.empty()` and returns immediately, so PnP-based anchor correction does nothing until the user bakes artwork layers — which may never happen on a given session.

**Bug 2 — `addLayerFeaturesToSLAM` silently no-ops without depth data.**
If the target was captured on a device without depth support, `ArViewModel.addLayerFeaturesToSLAM` logs a warning and returns. The user receives no feedback and teleological correction is silently inactive.

**Missing feature — Freeze preview.**
When the user freezes their layers in AR mode, they have no way to see what visual features the engine is matching against. The "target as CV sees it" — the artwork overlaid on the wall photo, annotated with ORB keypoints — is never shown.

**Architectural debt — Mixed fingerprint store.**
`mTargetDescriptors`/`mTargetKeypoints3D` holds wall features and artwork features concatenated together, with no semantic separation. `mArtworkDescriptors`/`mArtworkKeypoints3D` exists alongside it as a partial duplicate. The intended separation of wall localization features from artwork progress features was never employed.

---

## Design

### 1. Architecture Overview

Replace the single mixed fingerprint store with two explicit, purpose-separated stores throughout the stack.

**Current:**
```
mTargetDescriptors / mTargetKeypoints3D
  = wall ORB features (2D only, no 3D points)
  + artwork ORB features (3D back-projected, appended by addLayerFeatures)

mArtworkDescriptors / mArtworkKeypoints3D   ← partial duplicate, artwork only
```

**New:**
```
mWallDescriptors / mWallKeypoints3D         ← wall ORB features + real 3D points, set at capture time
mArtworkDescriptors / mArtworkKeypoints3D   ← artwork ORB features + 3D points, set at bake time
```

- `runPnPMatch` uses **wall descriptors** for anchor localization — stable, permanent, describe the physical surface.
- Painting progress uses **artwork descriptors** matched against the current frame — unchanged.
- `tryUpdateFingerprint` (continuous wall refinement, every 600 frames) writes to **wall store** only.
- No mixing. No appending across stores. Each store has one clear owner.

---

### 2. Native Layer — MobileGS

**Member variable changes (`MobileGS.h`):**
- Rename `mTargetDescriptors` / `mTargetKeypoints3D` → `mWallDescriptors` / `mWallKeypoints3D`.
- `mArtworkDescriptors` / `mArtworkKeypoints3D` already exist — they become the sole artwork store. No layout change.

**Public API changes:**
- Remove `setTargetFingerprint(const cv::Mat& descriptors, const std::vector<cv::Point3f>& points3d)`.
- Add `setWallFingerprint(const cv::Mat& colorRgba, const cv::Mat& mask, const uint8_t* depthData, int depthW, int depthH, int depthStride, const float* intrinsics4, const float* viewMat16)` — runs ORB with optional mask, back-projects each keypoint to world space using depth+intrinsics+viewMatrix, writes to `mWallDescriptors`/`mWallKeypoints3D`. Returns `bool` — false if ORB finds no features.
- Add `restoreWallFingerprint(const cv::Mat& descriptors, const std::vector<cv::Point3f>& points3d)` — restores `mWallDescriptors`/`mWallKeypoints3D` from serialized data on session resume. Same body as the old `setTargetFingerprint`.
- Rename `addLayerFeatures(...)` → `setArtworkFingerprint(...)`. Same signature and logic. Writes only to `mArtworkDescriptors`/`mArtworkKeypoints3D` — removes the `vconcat`-into-target appending.

**`runPnPMatch` changes:**
- Guard: `if (mWallDescriptors.empty() || mWallKeypoints3D.empty()) return`.
- Reads from `mWallDescriptors`/`mWallKeypoints3D` for PnP.
- Painting progress block unchanged — uses `mArtworkDescriptors`.

**`tryUpdateFingerprint` changes:**
- Writes peripheral keypoints to `mWallDescriptors`/`mWallKeypoints3D` only.

**`clearMap` changes:**
- Clears both `mWallDescriptors`/`mWallKeypoints3D` and `mArtworkDescriptors`/`mArtworkKeypoints3D`.

---

### 3. JNI Layer — GraffitiJNI.cpp

**Removed:**
- `nativeSetTargetFingerprint(descArray, rows, cols, type, ptsArray)`
- `nativeGenerateFingerprint(bitmap)`
- `nativeGenerateFingerprintMasked(bitmap, maskBitmap)`
- `buildFingerprintObject` helper (deleted once confirmed unused)

**Added:**
- `nativeSetWallFingerprint(bitmap, maskBitmap, depthBuffer, depthW, depthH, depthStride, intrinsicsArray, viewMatArray)` — calls `gSlamEngine->setWallFingerprint(...)`, returns a populated `jobject Fingerprint` (with real 3D points and 2D keypoints) on success, `nullptr` on failure (no features found). `buildFingerprintObject` is kept and reused here.
- `nativeRestoreWallFingerprint(descArray, rows, cols, type, ptsArray)` — same signature as the old `nativeSetTargetFingerprint`, calls `gSlamEngine->restoreWallFingerprint(...)`.

**Renamed:**
- `nativeAddLayerFeatures(...)` → `nativeSetArtworkFingerprint(...)`. Same parameters, calls `gSlamEngine->setArtworkFingerprint(...)`.

**Unchanged:**
- `nativeAnnotateKeypoints(bitmap)` — used for both target capture review and freeze preview.
- `buildFingerprintObject` — kept; reused by `nativeSetWallFingerprint`.

---

### 4. Kotlin Bridge & Domain Model

**SlamManager.kt:**
- Remove `generateFingerprint(bitmap)`, `generateFingerprintMasked(bitmap, mask)`, `setTargetFingerprint(...)`.
- Add `setWallFingerprint(bitmap, mask, depthBuffer, depthW, depthH, depthStride, intrinsics, viewMatrix): Fingerprint?` — returns null if no features found.
- Add `restoreWallFingerprint(descriptorsData, rows, cols, type, points3d)` — replaces old `setTargetFingerprint` call in `loadFingerprintIfExists`.
- Rename `addLayerFeatures(...)` → `setArtworkFingerprint(...)`.

**`Fingerprint` domain model — preserved.**
- `Fingerprint.points3d` is now populated with real 3D world-space coordinates at capture time.
- `GraffitiProject.fingerprint`, `ProjectRepository.updateTargetFingerprint`, serialization — all unchanged.

**`MainViewModel.onConfirmTargetCreation`:**
- Was: `generateFingerprintMasked(sensorBmp, mask)` → null check → `setTargetFingerprint(fp.descriptorsData, ...)` → `saveProject(fingerprint = fp)`
- Becomes: `slamManager.setWallFingerprint(sensorBmp, mask, depthBuffer, depthW, depthH, depthStride, intrinsics, viewMatrix)` → null check → `saveProject(fingerprint = fp)`.
- **Callback signature change:** `TargetCreationUi.onConfirm` expands from `(Bitmap?, Bitmap?)` to `(Bitmap?, Bitmap?, ByteBuffer?, Int, Int, Int, FloatArray?, FloatArray?)` to carry depth buffer + dimensions + stride + intrinsics + viewMatrix. At the `MainActivity` call site, these values are read from `arUiState` (already stored there since target capture) and passed through alongside the bitmap and mask.

**`ArViewModel.addLayerFeaturesToSLAM` → renamed `setArtworkFingerprintFromComposite`:**
- Calls `slamManager.setArtworkFingerprint(...)`. Logic unchanged.

**`ArViewModel.loadFingerprintIfExists`:**
- Calls `slamManager.restoreWallFingerprint(fp.descriptorsData, fp.descriptorsRows, fp.descriptorsCols, fp.descriptorsType, fp.points3d.toFloatArray())` instead of old `setTargetFingerprint`. Otherwise unchanged.

**`isAnchorEstablished` gate — unchanged.**
- Still gates on `project.fingerprint != null`.

---

### 5. Freeze Preview UI

**`ArUiState` additions:**
- `freezePreviewBitmap: Bitmap? = null` — non-null means `FreezePreviewScreen` is visible.
- `freezeDepthWarning: Boolean = false` — true when depth data was absent at target capture; shown as a banner inside the preview.

**Freeze tap flow:**
`MainActivity` rail "Freeze" tap calls both:
1. `editorViewModel.toggleImageLock()` — locks active layer transform (existing behavior).
2. `arViewModel.onFreezeRequested(visibleLayers)` — new. Receives `editorUiState.layers.filter { it.isVisible && it.bitmap != null }`.

`ArViewModel.onFreezeRequested(layers)`:
- Composites visible layers over `ArUiState.tempCaptureBitmap` (the wall capture photo) at 2048×2048 using the same `compositeLayersForAr` logic.
- Calls `slamManager.annotateKeypoints(merged)` on the result.
- Sets `ArUiState.freezePreviewBitmap = annotated`, `freezeDepthWarning = (targetDepthBuffer == null || targetDepthBuffer.capacity() == 0)`.

**`FreezePreviewScreen` composable (new file `feature/ar/FreezePreviewScreen.kt`):**
- Shown when `arUiState.freezePreviewBitmap != null`.
- Fullscreen overlay, dark-scrim style consistent with `FeatureSelectionReview`.
- Shows annotated image (wall + artwork + green ORB blobs).
- Optional depth warning banner when `freezeDepthWarning` is true: "No depth data from target capture — teleological correction may be reduced."
- Two buttons:
  - **Got it** — clears `freezePreviewBitmap` (dismiss). Layers remain frozen.
  - **Unfreeze** — emits `onUnfreezeRequested` event (a `SharedFlow<Unit>` on `ArViewModel`); `MainActivity` observes this and calls `editorViewModel.toggleImageLock()` to undo the lock. Clears `freezePreviewBitmap`.

---

### 6. Data Flow — Updated End to End

```
TARGET CREATION
  User taps capture
  → ArRenderer grabs frame: bitmap + depth16 + intrinsics + viewMatrix
  → ArViewModel.onTargetCaptured: stores all in ArUiState, annotates for review
  → User brushes include/exclude, confirms
  → MainViewModel.onConfirmTargetCreation:
      slamManager.setWallFingerprint(sensorBmp, mask, depth, intrinsics, viewMatrix)
      → native: ORB + 3D back-projection → mWallDescriptors / mWallKeypoints3D populated
      → returns Fingerprint (with real points3d)
      → saveProject(fingerprint = fp)
  → ArViewModel: isAnchorEstablished = true

ARTWORK BAKE (reactive, auto, unchanged trigger)
  MainScreen: LaunchedEffect(visibleLayers, isAnchorEstablished)
  → compositeLayersForAr(visibleLayers)
  → ArViewModel.setArtworkFingerprintFromComposite(composite)
      → slamManager.setArtworkFingerprint(composite, depth, intrinsics, viewMatrix)
      → native: ORB + 3D back-projection → mArtworkDescriptors / mArtworkKeypoints3D

FREEZE (user action, AR mode)
  Rail tap → toggleImageLock() + onFreezeRequested(visibleLayers)
  → composite(visibleLayers over tempCaptureBitmap)
  → annotateKeypoints(merged)
  → ArUiState.freezePreviewBitmap = annotated
  → FreezePreviewScreen shown: user sees ORB blobs on wall + artwork
  → Got it: dismiss. Unfreeze: toggleImageLock() undo + dismiss.

PAINTING LOOP (~15 Hz)
  feedYuvFrame → scheduleRelocCheck → relocThread.runPnPMatch:
    mWallDescriptors + mWallKeypoints3D → PnP → anchor correction
    mArtworkDescriptors vs current frame → paintingProgress [0,1]

CONTINUOUS WALL REFINEMENT (~every 20s)
  processDepthFrame → mFingerprintRequested → tryUpdateFingerprint:
    peripheral keypoints back-projected → appended to mWallDescriptors / mWallKeypoints3D

SESSION RESUME
  loadFingerprintIfExists → slamManager.restoreWallFingerprint(fp.descriptorsData, fp.points3d)
  → mWallDescriptors / mWallKeypoints3D restored immediately
  → PnP correction active from first reloc tick
```

---

### 7. Testing

**`MainViewModelTest`:**
- Mock `slamManager.setWallFingerprint(...)` returning a `Fingerprint` with non-empty `points3d`. Verify `saveProject` receives it.
- Mock returning `null`. Verify toast shown, `saveProject` not called.

**`ArViewModelTest`:**
- `onFreezeRequested sets freezePreviewBitmap`: mock `slamManager.annotateKeypoints(any()) returns mockBitmap`; assert `uiState.freezePreviewBitmap == mockBitmap`.
- Confirm clears `freezePreviewBitmap`.
- Unfreeze emits `onUnfreezeRequested` SharedFlow event.
- `loadFingerprintIfExists` calls `restoreWallFingerprint` (not old `setTargetFingerprint`).

**`EditorViewModelTest`:**
- `toggleImageLock updates state` — unchanged.

**`TeleologicalTrackerTest` (`:feature:ar`):**
- Re-enable currently commented-out tests. Update any API references to `setWallFingerprint`/`setArtworkFingerprint` naming.

**Native:**
- No automated runner. Use `DEBUG_COLORS` in `MobileGS.cpp` to visually verify wall features drive anchor correction independently of artwork features.

---

## Files Changed

| File | Change |
|---|---|
| `core/nativebridge/src/main/cpp/include/MobileGS.h` | Rename member vars; update public API |
| `core/nativebridge/src/main/cpp/MobileGS.cpp` | Implement `setWallFingerprint`, `restoreWallFingerprint`, `setArtworkFingerprint`; update `runPnPMatch`, `tryUpdateFingerprint`, `clearMap` |
| `core/nativebridge/src/main/cpp/GraffitiJNI.cpp` | Replace JNI methods per Section 3 |
| `core/nativebridge/src/main/java/.../SlamManager.kt` | Replace Kotlin bridge methods per Section 4 |
| `core/common/src/main/java/.../model/UiState.kt` | Add `freezePreviewBitmap`, `freezeDepthWarning` to `ArUiState` |
| `feature/ar/src/main/java/.../ArViewModel.kt` | Add `onFreezeRequested`, `onUnfreezeRequested` SharedFlow; rename `addLayerFeaturesToSLAM`; update `loadFingerprintIfExists` |
| `feature/ar/src/main/java/.../FreezePreviewScreen.kt` | New composable |
| `app/src/main/java/.../MainViewModel.kt` | Update `onConfirmTargetCreation` |
| `app/src/main/java/.../MainActivity.kt` | Wire Freeze rail tap to both `toggleImageLock` and `onFreezeRequested`; observe `onUnfreezeRequested` |
| `app/src/main/java/.../MainScreen.kt` | Show `FreezePreviewScreen` when `freezePreviewBitmap != null` |
| `feature/ar/src/test/.../ArViewModelTest.kt` | New freeze tests |
| `app/src/test/.../MainViewModelTest.kt` | Update mock expectations |
| `feature/ar/src/test/.../TeleologicalTrackerTest.kt` | Re-enable and update |
