# GraffitiXR Architecture

GraffitiXR uses a **Multi-Module Clean Architecture** designed for scalability, build performance, and separation of concerns.

## High-Level Overview

The application is divided into three layers:

1.  **App Layer (`:app`):** The integration point. It contains the `MainActivity`, dependency injection (Hilt) setup, and the global Navigation Graph.
2.  **Feature Layer (`:feature`):** Contains the screens and business logic for specific user flows. Features do *not* depend on each other directly.
3.  **Core Layer (`:core`):** Contains shared code, data models, design system, and native libraries.

## Module Graph

```mermaid
graph TD
    app --> feature:ar
    app --> feature:editor
    app --> feature:dashboard

    feature:ar --> core:common
    feature:ar --> core:domain
    feature:ar --> core:design
    feature:ar --> core:native

    feature:editor --> core:common
    feature:editor --> core:domain
    feature:editor --> core:design
    feature:editor --> core:native

    feature:dashboard --> core:common
    feature:dashboard --> core:domain
    feature:dashboard --> core:design
    feature:dashboard --> core:data

    core:data --> core:domain
    core:data --> core:common

    core:native --> core:domain
    core:native --> core:common (Optional)
    
    core:design --> core:common
    
    core:domain --> core:common
```

## Module Descriptions

### `:app`
*   **Role:** Application Entry Point.
*   **Key Components:** `GraffitiApplication`, `MainActivity`, `MainViewModel` (Global State), `NavGraph`.

### `:feature:ar`
*   **Role:** Augmented Reality experience.
*   **Key Components:** `ArView` (Composable), `ArRenderer` (GLSurfaceView), `ArViewModel`, `TargetCreationFlow`.
*   **Dependencies:** ARCore, OpenGL, OpenCV (via Native).

### `:feature:editor`
*   **Role:** Image manipulation and composition.
*   **Key Components:** `EditorUi` (Composable), `EditorViewModel`, `ImageProcessor` (OpenCV).
*   **Screens:** Mockup, Overlay, Trace.

### `:feature:dashboard`
*   **Role:** Project management and settings.
*   **Key Components:** `ProjectLibraryScreen`, `SettingsScreen`, `DashboardViewModel`.
*   **Dependencies:** `core:data` (for Persistence).

### `:core:data`
*   **Role:** Data persistence and repository implementation.
*   **Key Components:** `ProjectRepositoryImpl`, `ProjectManager` (File I/O).

### `:core:domain`
*   **Role:** Pure business logic and data models.
*   **Key Components:** `Project` (Model), `GraffitiProject` (Model), `ProjectRepository` (Interface).

### `:core:common`
*   **Role:** Universal utilities.
*   **Key Components:** `LocationTracker`, `DispatcherProvider`, `UiState` (Shared Model).

### `:core:design`
*   **Role:** Design System.
*   **Key Components:** `AzNavRail`, `Theme`, `Typography`, Shared Components (`Knob`, `InfoDialog`).

### `:core:native`
*   **Role:** High-performance C++ code.
*   **Key Components:** `MobileGS` (Engine), `GraffitiJNI` (Bridge).

## State Management

Each feature manages its own state using `StateFlow` in a `ViewModel`.
*   **Unidirectional Data Flow:** UI events -> ViewModel -> State Update -> UI Recomposition.
*   **Persistence:** `EditorViewModel` and `DashboardViewModel` interact with `ProjectRepository` to save/load data.

## Native Integration

The `MobileGS` engine is written in C++17 and accessed via JNI.
*   **Rendering:** Uses OpenGL ES 3.0 directly.
*   **Mapping:** Performs SLAM and Gaussian Splatting logic.
*   **Data Transfer:** Uses direct `ByteBuffer` passing for efficiency.
