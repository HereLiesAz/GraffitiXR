# Application Screens Documentation

## **1. Main AR Screen (`ARScreen.kt`)**
-   **Type:** `AndroidView` wrapping `GLSurfaceView`.
-   **Renderer:** `ArRenderer.kt`.
-   **Functionality:**
    -   Renders the camera feed.
    -   Visualizes AR planes (polygons).
    -   Renders the user's overlay image (quad) mapped to the AR target or anchor.
    -   Handles gestures (drag, scale, rotate) via Compose `pointerInput`.
-   **Key Logic:** `ARCoreManager` handles the session lifecycle.

## **2. Trace/Overlay Screen (`OverlayScreen.kt`)**
-   **Type:** standard Composable with `CameraPreview`.
-   **Functionality:**
    -   Uses CameraX to show a simple camera feed.
    -   Overlays the user's image as a simple 2D Composable (`Image` or `Canvas`).
    -   Supports opacity adjustment for "tracing" (seeing the hand through the phone).
    -   No AR tracking; the image is screen-locked.

## **3. Mockup Screen (`MockupScreen.kt`)**
-   **Type:** 2D Image Manipulation Canvas.
-   **Functionality:**
    -   Displays a static background image (picked by user).
    -   Overlays the art image.
    -   Allows perspective warping (4-corner distortion) to match the perspective of the wall in the photo.

## **4. Settings Screen (`SettingsScreen.kt`)**
-   **Access:** Via Navigation Rail -> Host Item.
-   **Content:**
    -   App Version.
    -   Permission Status (Camera, Location, Notifications).
    -   "Check for Updates" button (GitHub integration).
    -   Links to Privacy Policy.

## **5. Target Refinement Screen (`TargetRefinementScreen.kt`)**
-   **Context:** Part of the AR Target Creation flow.
-   **Functionality:**
    -   Shows the captured frame.
    -   Visualizes the auto-generated mask (ML Kit).
    -   Allows user to add/subtract from the mask using touch.
    -   Visualizes OpenCV keypoints to show trackability.

## **6. Help/Onboarding (`HelpScreen.kt`)**
-   **Type:** Full-screen Pager.
-   **Content:** Step-by-step tutorial images and text explaining the app's modes.

## **7. Target Creation Overlay (`TargetCreationOverlay.kt`)**
-   **Context:** Overlaid on `ARScreen` during target creation.
-   **Functionality:**
    -   Guides the user through target creation steps.
    -   **Modes:**
        -   **Capture:** Standard 5-angle capture.
        -   **Guided Grid:** User configures a grid (Rows x Cols) and aligns it with wall marks.
        -   **Guided Points:** User aligns with 4 reference points.
    -   Visualizes steps and instructions.
