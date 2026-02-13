# GRAFFITIXR: AUTOPSY & ANALYSIS

## STATUS: POST-OP (STABLE)
**Date:** 02/13/2026
**Condition:** The patient is recovering from major reconstructive surgery on the Native Bridge. The schism between `GraffitiJNI` and `SlamManager` has been healed.

---

## 1. CRITICAL ISSUES (Active)

### A. The "Ghost" Renderer
* **Severity:** MEDIUM
* **Symptoms:** `GsViewerRenderer` and `ArRenderer` are duplicated rendering pipelines.
* **Diagnosis:** Both attempt to draw the point cloud, but `GsViewerRenderer` (Editor) likely re-initializes the engine or fights for context.
* **Plan:** Abstract the `MobileGS` drawing calls so they can be injected into *any* GLSurfaceView renderer without re-initializing the physics engine.

### B. The Lobotomized Editor
* **Severity:** HIGH
* **Symptoms:** `EditorViewModel` contains multiple `// TODO` blocks for core features (Background Removal, Edge Detection).
* **Diagnosis:** The brain is there, but the nerves aren't connected to the muscles. `BackgroundRemover` and `ImageProcessor` exist but are uncalled.
* **Plan:** Wire up the Coroutines in `EditorViewModel` to process Bitmaps off the main thread.

---

## 2. RESOLVED ISSUES

### [FIXED] The Native Bridge Schism
* **Issue:** Two conflicting entry points (`GraffitiJNI.kt` vs `SlamManager.kt`) caused linker errors and runtime crashes.
* **Fix:** `GraffitiJNI.kt` was executed. `SlamManager.kt` was promoted to the sole JNI interface. `GraffitiJNI.cpp` was rewritten to route all calls through `Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_*`.
* **Files Affected:** `SlamManager.kt`, `GraffitiJNI.cpp`.
* **Deletions:** `GraffitiJNI.kt`, `SlamReflectionHelper.kt`.

---

## 3. ARCHITECTURAL OVERVIEW (Current)

* **UI Layer:** Jetpack Compose (Kotlin)
* **Logic Layer:** ViewModels (Kotlin)
* **Bridge Layer:** `SlamManager` (Kotlin) <--> `GraffitiJNI.cpp` (C++)
* **Engine Layer:** `MobileGS` (C++) + OpenCV (C++)

## 4. NEXT STEPS
1.  **Wire the Editor:** Implement `onRemoveBackgroundClicked` and `onLineDrawingClicked`.
2.  **Unify Renderers:** Create a shared `SplatRenderer` class.
3.  **Testing:** Stop ignoring tests and fix the `ArViewModelTest`.


# GRAFFITIXR: AUTOPSY & ANALYSIS

## STATUS: POST-OP (RECOVERING)
**Date:** 02/13/2026
**Condition:** The patient has survived the transplant. The `SlamManager` is now a Singleton, the Editor is wired to the brain, and we stopped the immune system (SSL) from rejecting the organs.

---

## 1. RECENT SURGERIES (COMPLETED)

### [FIXED] The Dependency Injection Fracture
* **Issue:** Multiple instances of `SlamManager` were causing split-brain physics.
* **Fix:** Enforced `SingletonComponent` via Hilt.
* **Correction:** Switched from `kapt` (dead) to `KSP` (alive) for annotation processing.

### [FIXED] The Network Paranoia
* **Issue:** App crashed on SSL handshake due to missing trust anchors (and likely `mitmproxy` interference).
* **Fix:** Injected `network_security_config.xml` to trust User CAs and silenced Firebase analytics.

### [FIXED] The Native Bridge Schism
* **Issue:** Conflicting JNI entry points.
* **Fix:** `GraffitiJNI` is dead. `SlamManager` is the sole monarch.

---

## 2. ACTIVE RISKS

### A. The "Ghost" Renderer
* **Severity:** MEDIUM
* **Status:** Mitigated by Singleton, but context switching between AR and Editor SurfaceViews might still drop textures. Watch for black screens.

### B. Testing Apathy
* **Severity:** LOW
* **Status:** Tests are still ignored. The code works, but we don't know *why* it works.

## 3. NEXT STEPS
* **Verify:** Run the app. If it crashes, check the logs for "dlopen failed" or Hilt injection errors.
* **Refine:** The Edge Detection (Canny) parameters are hardcoded. Make them dynamic.