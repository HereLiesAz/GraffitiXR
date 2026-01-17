# Application Screens Documentation

## **1. Main Screen (`MainScreen.kt`)**
-   **Role:** The top-level orchestrator and container.
-   **Functionality:**
    -   Manages the `AzNavRail` for navigation between modes (AR, Overlay, Mockup, Trace).
    -   Handles global gestures and input events.
    -   Displays the `AdjustmentsPanel` for layer properties.
    -   Integrates `ProjectLibraryScreen` and `SettingsScreen` as overlays.
    -   Manages the Target Creation Flow and Neural Scan interactions.

## **2. AR Screen (`ArView.kt`)**
-   **Type:** `AndroidView` wrapping `GLSurfaceView`.
-   **Renderer:** `ArRenderer.kt`.
-   **Functionality:**
    -   Renders the camera feed via ARCore.
    -   Visualizes AR planes and Point Clouds.
    -   Renders multiple `OverlayLayer`s mapped to AR anchors.
    -   Supports Cloud Anchor hosting and resolving.
    -   Handles "Magic Align" for instant target matching.

## **3. Mapping Screen (`MappingScreen.kt`)**
-   **Context:** "Surveyor Mode" / Neural Scan.
-   **Functionality:**
    -   Specialized AR view for environmental mapping.
    -   Displays `MiniMap` (top-down view of the session).
    -   Provides real-time feedback on Mapping Quality (`SlamManager`).
    -   Allows finalizing and hosting a map for persistence.

## **4. Trace/Overlay Screen (`OverlayScreen.kt`)**
-   **Type:** standard Composable with `CameraPreview` (CameraX).
-   **Functionality:**
    -   Shows a simple camera feed without tracking.
    -   Overlays the ACTIVE layer as a screen-locked 2D image.
    -   Used for physical tracing ("Ghost Mode").
    -   Supports opacity, contrast, and color balance adjustments.

## **5. Mockup Screen (`MockupScreen.kt`)**
-   **Type:** 2D Image Canvas.
-   **Functionality:**
    -   Displays a user-selected static background image (e.g., photo of a wall).
    -   Renders all `OverlayLayer`s on top.
    -   Allows testing blending modes and scale relative to a static reference.

## **6. Target Refinement Screen (`TargetRefinementScreen.kt`)**
-   **Context:** Part of the AR Target Creation flow.
-   **Functionality:**
    -   Shows the captured target candidate image.
    -   Visualizes the auto-generated mask (ML Kit).
    -   Allows user to refine the mask (Eraser/Restore tools).
    -   Visualizes OpenCV keypoints (`Fingerprint`) to verify trackability.

## **7. Target Creation Overlay (`TargetCreationOverlay.kt`)**
-   **Context:** Overlaid on `ArView` during target creation.
-   **Functionality:**
    -   Guides the user through capturing a high-quality AR target.
    -   **Modes:**
        -   **Capture:** Standard image capture.
        -   **Rectify:** Planar target rectification.
        -   **Guided Grid:** Grid alignment for large murals.
        -   **Guided Points:** 4-point calibration.
        -   **Multi-Point Calibration:** Advanced sensor fusion calibration.

## **8. Settings Screen (`SettingsScreen.kt`)**
-   **Access:** Via Navigation Rail -> Project -> Settings.
-   **Content:**
    -   App Version and Updates.
    -   Permission Management.
    -   Preferences.
