# GraffitiXR Project Roadmap & To-Do

This document tracks the development status, future enhancements, and identified gaps in the GraffitiXR application.

---

## **V1.3 Completed Features**

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
    -   [x] Rewrote `README.md` and `AGENTS.md` to align with the project vision.
-   **[x] Robustness and Error Handling:**
    -   [x] Implemented error handling for background removal failures.
    -   [x] Implemented user guidance for AR plane detection failures.
-   **[x] Automated Tests:**
    -   [x] Added a suite of unit tests for the `MainViewModel`.
-   **[x] AR Core Functionality:**
    -   [x] Enabled Marker Placement: Users can tap to place up to four markers.
    -   [x] Implemented AR Image Projection: Renders the selected image onto the 3D mesh created by the markers.
-   **[x] UI Refinements:**
     -   [x] Refined the Adjustment Slider UI into an integrated, animated panel.

---

## **Project Backlog**

### **High Priority**

-   **[ ] Enhance User Experience (UX):**
    -   [ ] **Create User Onboarding:** Design and implement a tutorial or onboarding flow to explain the three different modes (AR, Mock-up, On-the-Go) to new users.
    -   [ ] **Add Gesture Feedback:** Provide visual feedback in Mock-up mode when a scale or rotation gesture is active.
    **[ ] Keep track of the real-world image's progress as the original fingerprint is eventually covered by it completely.

### **Medium Priority**

-   **[ ] Add "Save/Export" Feature:**
    -   [ ] Allow users to save or export the final composed image from any of the modes.
    -   [ ] Allow users to save the marks or griding "fingerprint" and overlay location, size, and orientation.
    -   [ ] Saving the project includes the fingerprint history.
-   **[ ] Implement Advanced Image Editing:**
    -   [ ] Add more advanced image adjustment tools like color balance or blending modes.

### **Low Priority / Future Ideas**
-   **[ ] Create a Project Library:**
    -   [ ] Implement functionality to save, load, and manage different mock-up projects (background, overlay, settings).
