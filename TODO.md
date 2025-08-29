# GraffitiXR To-Do List

This file tracks the remaining tasks and potential improvements for the GraffitiXR application.

## High Priority
- [ ] **Implement Core Feature Gaps:**
    - [ ] Implement the `onClearMarkers` function in `AppNavRail.kt` to allow users to clear placed markers.
    - [ ] Implement dynamic detection of predefined marker images as described in the README. The current implementation uses hardcoded poses.
    - [ ] Implement dynamic loading of mural textures and marker images.
- [ ] **Improve Robustness and Performance:**
    - [ ] Add a loading indicator while background removal is processing.
    - [ ] Ensure image processing runs on a background thread to avoid UI jank.
    - [ ] Add user-facing error messages for background removal failures.
    - [ ] Add user guidance/error message if no AR planes are detected.

## Medium Priority
- [ ] **Implement Advanced Mural Projection:**
    - [ ] Research and implement a method to create a custom 3D mesh from user-placed markers.
    - [ ] Apply the selected image as a texture to the custom mesh with perspective correction.
    - [ ] This will likely require using a lower-level graphics API like OpenGL ES or finding a more advanced Compose-compatible AR library.

## Low Priority
- [ ] **Enhance User Experience (UX):**
    - [ ] Create an onboarding flow or tooltips for first-time users.
    - [ ] Refine the UI for adjustment sliders (e.g., integrate into the navigation rail's expanded menu instead of a popup).
