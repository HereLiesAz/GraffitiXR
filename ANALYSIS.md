# GraffitiXR Codebase Analysis

## Overview
The GraffitiXR project aims for a robust, local-first AR application using a multi-module architecture. While the directory structure reflects this intent, the actual code implementation suffers from significant coupling and architectural "leaks."

## Key Issues

### 1. Monolithic `MainViewModel` (The God Object)
The `MainViewModel` in the `:app` module violates the separation of concerns. It currently handles:
-   **AR State & Logic:** Directly references `ArRenderer` and manages `ArState`.
-   **Editor Actions:** Implements `EditorActions` interface and handles image manipulation.
-   **Project Management:** Handles loading, saving, and sorting projects.
-   **GPS/Location:** Manages location updates.
-   **Global UI State:** Maintains a massive `UiState` data class that mixes AR, Editor, and Dashboard states.

**Impact:** This makes the `app` module heavy and dependent on logic that should reside in feature modules. It prevents true modular isolation.

### 2. "Leaky" `core:common` Module
The `core:common` module, intended for universal utilities, contains heavy feature dependencies:
-   **ARCore:** Included in `build.gradle.kts` but seemingly unused in code.
-   **OpenCV:** Included and used for `ensureOpenCVLoaded`.
-   **Location:** Includes `LocationTracker`.

**Impact:** Any module depending on `core:common` (which is all of them) inherits these heavy dependencies, increasing build times and blurring boundaries. `core:domain` (pure data) should not depend on ARCore or OpenCV implementations.

### 3. Mixed Responsibilities in `UiState`
The `UiState` class (likely in `core:common` or `core:domain`) acts as a global bucket.
-   It mixes `layers` (Editor) with `arState` (AR) and `availableProjects` (Dashboard).
-   Changes to AR state trigger recompositions in Editor UI and vice-versa.

### 4. Testing Gaps
The testing suite is minimal. The monolithic nature of `MainViewModel` makes it difficult to unit test specific features in isolation.

## Refactoring Plan (The "Ralph" Loop)

### Phase 1: ViewModel Splitting
We will decompose `MainViewModel` into feature-specific ViewModels:
1.  **`ArViewModel` (in `:feature:ar`):** Handles AR session, `ArRenderer` bridge, mapping logic, and point cloud toggles.
2.  **`EditorViewModel` (in `:feature:editor`):** Handles layer manipulation, image adjustments, and transformation logic.
3.  **`DashboardViewModel` (in `:feature:dashboard`):** Handles project listing, creation, and settings.

### Phase 2: State Decomposition
We will split `UiState` into:
-   `ArUiState`
-   `EditorUiState`
-   `DashboardUiState`

These will be exposed by their respective ViewModels. The `MainScreen` will either coordinate them or, preferably, the Navigation Graph will instantiate them only for the relevant screens.

### Phase 3: Core Cleanup
-   Remove `libs.arcore.client` from `:core:common`.
-   Evaluate if `LocationTracker` belongs in `:core:data` or a specific feature.
-   Ensure `:core:domain` remains pure.

### Phase 4: Documentation & Testing
-   Update `architecture.md` to reflect the strict boundaries.
-   Add unit tests for the new, smaller ViewModels.
