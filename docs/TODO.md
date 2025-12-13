# GraffitiXR Project Roadmap & To-Do

This document tracks the development status, future enhancements, and identified gaps in the GraffitiXR application.

---

## **V1.11 Documentation Overhaul (Current)**

-   **[x] Comprehensive Documentation:**
    -   [x] Create `docs/` folder structure.
    -   [x] Create `AGENT_GUIDE.md`, `UI_UX.md`, `auth.md`, `conduct.md`, `data_layer.md`, `fauxpas.md`, `misc.md`, `performance.md`, `screens.md`, `task_flow.md`, `testing.md`, `workflow.md`.
    -   [x] Update `AGENTS.md` with index and strict rules.
    -   [x] Add KDocs to critical files (`MainViewModel`, `UiState`, `ArRenderer`, `MainActivity`, `ProjectManager`, `ArView`).

---

## **V1.6 Enhancements (Completed)**

-   **[x] UI Layout Reorganization:**
    -   [x] Reorganized Navigation Rail: Moved adjustment items to "Image", created "Target" host for target-related items.
    -   [x] Consolidate controls: Adjustments (Opacity, Contrast, Saturation) are now grouped in a bottom panel.
-   **[x] Improved Adjustments UI:**
    -   [x] Replaced adjustment sliders with rotatable Knobs.
    -   [x] Implemented a transparent `AdjustmentsPanel` at the bottom of the screen.
    -   [x] Added persistent Undo/Redo buttons alongside the adjustments.
    -   [x] Ensure controls do not obscure the image (transparent background).

## **V1.5 Completed Features**

-   **[x] Refactor from Vuforia Engine to ARCore:**
    -   [x] Removed all Vuforia dependencies and code.
    -   [x] Integrate the ARCore SDK.
    -   [x] Implement runtime Image Target creation using the device camera.
-   **[x] Mock-up Mode:**
    -   [x] Users can select a static background image.
    -   [x] A four-point transformation UI allows users to warp the overlay image.
    -   [x] Two-finger gestures for scaling and rotating the overlay are implemented.
-   **[x] On-the-Go Mode (Non-AR Camera):**
    -   [x] A fallback mode overlays the image on the live camera feed for non-AR devices.
-   **[x] Core UI and Image Adjustments:**
    -   [x] UI for selecting overlay and background images.
    -   [x] Functional sliders for opacity, contrast, and saturation.
    -   [x] Background removal for the overlay image.
-   **[x] Code & Project Documentation:**
    -   [x] Added comprehensive KDocs to all classes, methods, and properties.
    -   [x] Rewrote `README.md`, `AGENTS.md`, and `BLUEPRINT.md` to align with the project vision.
-   **[x] Robustness and Error Handling:**
    -   [x] Implemented error handling for background removal failures.
    -   [x] Implemented user guidance for AR plane detection failures.
-   **[x] Automated Tests:**
    -   [x] Added a suite of unit tests for the `MainViewModel`.
-   **[x] UI Refinements:**
     -   [x] Refined the Adjustment Slider UI into an integrated, animated panel.
-   **[x] Enhance User Experience (UX):**
    -   [x] **Create User Onboarding:** Design and implement a tutorial or onboarding flow to explain the two different modes (Mock-up and On-the-Go) to new users.
    -   [x] **Add Gesture Feedback:** Provide visual feedback in Mock-up mode when a scale or rotation gesture is active.
-   **[x] Keep track of the real-world image's progress as the original fingerprint is eventually covered by it completely.
-   **[x] Undo/Redo Functionality:**
    -   [x] Implement undo and redo buttons to revert or reapply image adjustments.
-   **[x] Refine Gesture Feedback UI:**
    -   [x] Replace the full-screen gesture feedback with a more subtle, non-intrusive indicator.
-   **[x] Optimize Progress Calculation Performance:**
    -   [x] Refactor the progress calculation logic to avoid recalculating the entire bitmap on every update.
-   **[x] Add "Save/Export" Feature:**
    -   [x] Allow users to save or export the final composed image from any of the modes.
    -   [x] Allow users to save the marks or griding "fingerprint" and overlay location, size, and orientation.
    -   [x] Saving the project includes the fingerprint and undo/redo history.
-   **[x] Implement Advanced Image Editing:**
    -   [x] Add more advanced image adjustment tools like color balance or blending modes.
    -   [x] **Curves Adjustment:** Implement a user interface for adjusting the image's tonal range using curves.
-   **[x] Create a Project Library:**
    -   [x] Implement functionality to save, load, and manage different mock-up projects (background, overlay, settings).
-   **[x] Improve UX Flow and Navigation:**
    -   [x] Reorganize navigation rail items into logical groups (Project, Mode, Image, Adjustments).
    -   [x] Implement a multi-step onboarding tutorial in HelpScreen.

---

## **V1.7 Features**

-   **[x] Settings Screen:**
    -   [x] Added a centralized Settings screen accessible from the Navigation Rail.
    -   [x] Displays App Version and Permissions status.
    -   [x] Implemented GitHub update check for experimental releases.

## V1.10 Features (Current)

-   **[x] Critical Bug Fixes & UX Enhancements:**
    -   [x] **Fix AR Drag:** Implemented single-touch drag to place anchor in AR mode.
    -   [x] **Two-Finger Drag:** Implemented global two-finger drag (pan) to move AR anchor.
    -   [x] **Fix Camera Lag:** Resolved camera resource conflict when switching from Overlay to AR mode.
    -   [x] **Fix Grid Creation:** Resolved black screen issue in Refinement step by fixing FileProvider paths.
    -   [x] **Robust Auto-Save:** Implemented periodic auto-save to persist application state.
    -   [x] **Grid Orientation:** Fixed sideways fingerprint grid issue by rotating captured frames.
    -   [x] **Flashlight:** Added flashlight toggle for AR and Overlay modes.
    -   [x] **Magic Align:** Added button to flatten image rotation.
    -   [x] **UI Polish:** Updated Settings navigation, Undo/Redo styling, and Refinement visualization.

## V1.9 Features (Completed)

-   **[x] Robust Bug Reporting:**
    -   [x] Implement a global crash handler to catch uncaught exceptions.
    -   [x] Create a `CrashActivity` to display error details to the user.
    -   [x] Integrate with GitHub Issues to automatically pre-fill bug reports.
    -   [x] Ensure bug reports trigger the "Jules" AI agent for automatic handling.

## **Project Backlog**

All items completed. Ready for the next phase of development.
