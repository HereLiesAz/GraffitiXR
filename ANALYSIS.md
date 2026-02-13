### 2. `ANALYSIS.md`
*Changes: Updated to reflect the discovery of the fractured native engine and the plan to unify it.*

# Codebase Analysis: The Autopsy

**Date:** 2026-02-12
**Status:** In-Progress (Critical Fixes Underway)

## The Good
1.  **Teleological SLAM:** The `MobileGS` engine (in `core/nativebridge`) implements the "foreknowledge" concept using OpenCV features to match against a target image.
2.  **Gaussian Splatting:** The `MobileGS` engine (in `core/nativebridge1`) implements a point cloud renderer using `GL_POINTS` and a chunk-based spatial hash map.
3.  **Clean Architecture:** The Kotlin layers (`Domain`, `Data`, `Feature`) are well-separated and follow Hilt patterns correctly.
4.  **MainViewModel:** Contrary to previous reports, `MainViewModel` is lean and handles only high-level app state (Touch Lock, Capture Flow). The complexity is correctly delegated to `ArViewModel` and `EditorViewModel`.

## The Bad (Critical Issues)
### 1. Fractured Native Engine (The Schism)
*   **Status:** **CRITICAL**.
*   **Issue:** The native C++ implementation is split between two directories:
    *   `core/nativebridge`: Contains the **Teleological SLAM** logic (OpenCV). This is the module linked in `settings.gradle.kts`.
    *   `core/nativebridge1`: Contains the **Gaussian Splatting** logic (OpenGL). This module is **dead code** (unlinked) but contains essential functionality (`feedDepthData`, `draw`).
*   **Consequence:** The app currently cannot do both target tracking and point cloud rendering. Calls to `SlamManager` (which expects Splatting features) will fail or crash because the JNI bindings are missing in the linked `core/nativebridge` library.

### 2. Missing JNI Bindings
*   **Status:** **CRITICAL**.
*   **Issue:** `SlamManager.kt` defines `external` functions (`feedDepthDataJni`, `drawJni`) whose C++ implementations reside in the unlinked `core/nativebridge1`. `GraffitiJNI.kt` defines `external` functions whose implementations are in `core/nativebridge`.
*   **Fix:** We must merge the C++ code from `core/nativebridge1` into `core/nativebridge` and export all JNI functions from a single shared library.

## The Ugly (Fixed/In Progress)
*   ~~**Monolithic MainViewModel:**~~ *FIXED.* Re-evaluation shows standard MVVM usage.
*   **Incomplete Documentation:** Native code interactions were poorly documented, leading to this split. We are rectifying this by adding comprehensive KDoc to all modules.

## Action Plan
1.  **Merge Native Code:** Combine `MobileGS.h/cpp` and `GraffitiJNI.cpp` from both locations into `core/nativebridge`.
2.  **Link Libraries:** Ensure `CMakeLists.txt` links both GLESv3 (for Splatting) and OpenCV (for Teleology).
3.  **Delete Dead Code:** Remove `core/nativebridge1`.
4.  **Document:** Add KDoc to all Kotlin files to prevent future architectural drift.
