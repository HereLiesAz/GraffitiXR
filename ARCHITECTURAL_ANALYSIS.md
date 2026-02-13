# GraffitiXR Architectural Analysis (Post-Refactoring)

## Overview
The GraffitiXR project has successfully transitioned from a monolithic structure to a robust **Multi-Module Clean Architecture**. The initial issues regarding the "God Object" ViewModel and leaky module boundaries have been addressed.

## Architecture Status

### 1. ViewModel Decomposition
The monolithic `MainViewModel` has been successfully split into feature-specific ViewModels:
*   **`ArViewModel` (in `:feature:ar`):** Handles AR session state, `ArRenderer` communication, and mapping logic.
*   **`EditorViewModel` (in `:feature:editor`):** Manages image layers (`OverlayLayer`), adjustments (curves, opacity), and transformation logic.
*   **`DashboardViewModel` (in `:feature:dashboard`):** Handles project listing (`ProjectLibraryScreen`), settings, and onboarding flows.
*   **`MainViewModel` (in `:app`):** Now lean, handling only global app-level state like touch locking and high-level navigation events.

### 2. Module Boundaries
The dependency graph is now cleaner and enforces separation of concerns:
*   **`:core:common`:** Contains universal utilities (`LocationTracker`, `ImageProcessor`, `DispatcherProvider`) and shared models (`UiState`). It no longer depends on heavy AR libraries unnecessarily, though it still provides essential OpenCV helpers used across features.
*   **`:core:domain`:** Pure Kotlin module defining data models (`Project`, `GraffitiProject`) and repository interfaces (`ProjectRepository`). It has no Android framework dependencies, ensuring testability.
*   **`:core:data`:** Implements repositories (`ProjectRepositoryImpl`) and handles data persistence. It is the bridge between domain logic and file I/O or databases.
*   **`:core:design`:** Encapsulates the design system (`AzNavRail`, Theme, Components), ensuring UI consistency across features.
*   **`:core:native`:** Isolates C++ code (`MobileGS`, `GraffitiJNI`) and OpenCV integration. Features that need native power depend on this explicitly.

### 3. State Management
State is now managed locally within feature modules using `StateFlow`:
*   `ArUiState` encapsulates AR-specific data (tracking status, point cloud visibility).
*   `EditorUiState` manages the canvas state (layers, selected tool).
*   `DashboardUiState` holds the list of projects and settings.

This prevents unnecessary recompositions in unrelated parts of the app.

## Remaining Considerations

### 1. Native Code Documentation
The C++ codebase (`MobileGS.cpp`) is powerful but complex. Detailed Doxygen-style documentation is being added to ensure maintainability.

### 2. Testing
While the architecture now supports better testing, coverage needs to be expanded, particularly for the new, smaller ViewModels and the `core:data` repository implementations.

### 3. Documentation
KDocs are being systematically added to all Kotlin files to ensure that the code is self-documenting and accessible to new contributors.
