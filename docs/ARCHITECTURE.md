# Architecture & State Management

## The Unified State Pattern

To resolve circular dependencies between feature modules (e.g., `feature:editor` needing AR data and `feature:ar` needing Editor context), we pulled all state definitions into **`:core:common`**.

### Source of Truth
The `UiState.kt` file in `:core:common` is the single source of truth for the application's data flow.

#### 1. `EditorUiState`
Controls the artistic workspace.
* **Layers:** A list of `Layer` objects containing bitmap data, transforms (scale/rotation), and blending modes.
* **Mode:** The active operational mode (`AR`, `STATIC`, `TRACE`).
* **Background:** The reference image (`backgroundBitmap` or `mapPath` for 3D).

#### 2. `ArUiState`
Controls the computer vision session.
* **Tracking:** Point cloud density and plane detection status.
* **Target Creation:** Buffers for `tempCaptureBitmap` and the rectification flow (`capturedTargetUris`).

## Data Flow

1.  **Events:** UI Components (in `:feature`) fire events to their ViewModel.
2.  **Mutation:** ViewModels (`MainViewModel`, `EditorViewModel`) mutate the StateFlow.
3.  **Observation:** The UI observes the Unified State from `:core:common` and recomposes.

## Module Graph
# Architecture

## Module Graph

```mermaid
graph TD
    App --> Feature_AR
    App --> Feature_Editor
    App --> Feature_Dashboard
    
    Feature_AR --> Core_Common
    Feature_Editor --> Core_Common
    Feature_Dashboard --> Core_Common
    
    Core_Common --> Core_Domain
    Core_Domain --> Core_Data
```

The "Unified State" Pattern
We do not use isolated states per feature. We use a monolithic state object split by domain, residing in :core:common.

UiState.kt
EditorUiState:

layers: List of bitmaps and transforms.

editorMode: Controls the view strategy (AR, STATIC, TRACE).

mapPath: (New) Path to the 3D map/splat file for the 3D Mockup mode.

backgroundBitmap: Fallback 2D background.

ArUiState:

isScanning: (New) Controls the SLAM mapping session state.

pointCloudCount: Debug metric for SLAM quality.

capturedTargetUris: For the Surveyor flow.

Key Components
:feature:ar
ArRenderer: Manages the GL surface and ARCore session.

SlamManager: (New) Handles keyframe extraction and point cloud generation.

:feature:editor
GsViewer: (New) A dedicated surface for rendering Gaussian Splats.

MockupScreen: Switches between 2D Image composables and the 3D GsViewer based on EditorUiState.mapPath.
