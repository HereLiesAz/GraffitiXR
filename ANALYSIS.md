# Codebase Analysis: The Autopsy

**Date:** 2026-02-12
**Status:** Functional but Fragile

## The Good
1.  **Directory Structure:** The flattening of the module hierarchy (removing the deep nesting of `features`) was successful. The physical layout mirrors the logical architecture.
2.  **Native Separation:** The `core:native` module correctly isolates the C++ horror from the Kotlin UI. The JNI bridge (`SlamManager`) is clean and uses Coroutines for state flow.
3.  **UI System:** `AzNavRail` is consistently implemented. The "thumb-first" philosophy is evident in the Compose hierarchy.

## The Bad (Technical Debt)
### 1. The Monolith Lives (`MainViewModel`)
Despite the refactoring strategy, `MainViewModel` remains a "God Object."
* **Evidence:** It imports `ArRenderer`, manages `MainUiState` (which duplicates flags from feature states), and handles permission logic.
* **Risk:** Any change to the AR flow requires recompiling the App module. It breaks the isolation of the `feature:ar` module.

### 2. State Duplication
We have `ArUiState` (in `common`) and `EditorUiState` (in `common`).
* **Issue:** The `UiState` is defined in `core:common`, but `MainViewModel` *also* maintains a local `MainUiState` for things like `isTouchLocked`.
* **Consequence:** There are two sources of truth for "Is the app busy?".

### 3. Native Volatility
The `MobileGS` engine is raw C++.
* **Memory:** There is no automatic lifecycle management for the C++ heap. If `GraffitiApplication` dies unexpectedly, or if `ArRenderer` isn't disposed correctly, we leak OpenGL contexts.
* **Thread Safety:** `SlamManager` calls native methods from `Dispatchers.IO`, but the C++ engine likely accesses shared state (`mChunks`) without sufficient mutex locking in the render loop.

## The Ugly (UX Gaps)
* **Fingerprint Aging:** The code creates fingerprints (ORB descriptors) but never updates them. If a user paints over a wall, the app will fail to relocalize, with no UI feedback to "Rescan."
* **Error Handling:** The `CrashHandler` exists, but it's a catch-all. Specific errors in the AR session (e.g., "Camera in use") often result in a silent black screen rather than a helpful toast.

## Recommendations
1.  **Kill MainViewModel:** Move `isTouchLocked` to a `GlobalUiState` in `core:common` and let feature ViewModels observe it.
2.  **Mutex the Splats:** Ensure `MobileGS.cpp` uses `std::mutex` around the voxel map during both `update()` (Write) and `draw()` (Read).
3.  **Implement Aging:** Add a timestamp to `Fingerprint`. If `CurrentTime - FingerprintTime > 30 Days`, prompt user to Rescan.