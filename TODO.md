# GraffitiXR To-Do List

This file tracks the current state of the GraffitiXR application and outlines potential future enhancements.

## Completed Features (v1.0)
- [x] **Implement AR Marker-Based Projection:**
    - [x] User can place four markers on detected real-world surfaces.
    - [x] A custom 3D mesh is generated from the markers.
    - [x] The selected image is applied as a texture with perspective correction.
- [x] **Implement Mock-up Mode:**
    - [x] User can select a static background image.
    - [x] A four-point transformation UI allows the user to warp the overlay image.
    - [x] Two-finger gestures for scaling and rotating the overlay are implemented.
- [x] **Implement Non-AR Camera Mode:**
    - [x] A fallback mode overlays the image on the live camera feed for non-AR devices.
- [x] **Implement Core UI and Image Adjustments:**
    - [x] UI for selecting overlay and background images.
    - [x] Functional sliders for opacity, contrast, and saturation.
    - [x] Background removal for the overlay image.

## High Priority
- [ ] **Improve Robustness and Performance:**
    - [ ] Ensure all image processing (background removal, bitmap loading) runs on a background thread to prevent UI jank.
    - [ ] Add more robust user-facing error messages (e.g., for background removal failures, image loading issues).
    - [ ] Add user guidance if no AR planes are detected for a prolonged period.

## Medium Priority
- [ ] **Enhance User Experience (UX):**
    - [ ] Create a simple onboarding flow or a series of tooltips to explain the three different modes to first-time users.
    - [ ] Refine the UI for adjustment sliders (e.g., integrate into an expandable panel instead of a full-screen popup).
    - [ ] Add visual feedback when a gesture (scale/rotation) is active in Mock-up mode.

## Low Priority / Future Ideas
- [ ] **Add "Save Mock-up" Feature:**
    - [ ] Allow users to save their creations from the Mock-up mode as a flattened JPEG or PNG file to their gallery.
- [ ] **Advanced Image Editing:**
    - [ ] Add more advanced image adjustment tools (e.g., blending modes, color balance).
- [ ] **Project Library:**
    - [ ] Create a simple library within the app to save and manage different projects (a combination of background, overlay, and transformation settings).
- [ ] **Video Support:**
    - [ ] Allow users to select a video file as an overlay texture.