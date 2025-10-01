# GraffitiXR Project Roadmap & To-Do

This document tracks the development status, future enhancements, and identified gaps in the GraffitiXR application.

---

## **V1.0 Completed Features**

-   **[x] AR Marker-Based Projection:**
    -   [x] Users can place four markers on detected real-world surfaces.
    -   [x] A custom 3D mesh is generated from these markers.
    -   [x] The selected image is applied as a texture with perspective correction.
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

---

## **Project Backlog**

### **High Priority**

-   **[ ] Improve Code & Project Documentation:**
    -   [ ] **Exhaustive KDoc Documentation:** Add comprehensive KDocs to all classes, methods, and properties across the entire codebase.
    -   [ ] **Refine `README.md`:** Rewrite for a human audience (developers), including sections on tech stack, features, and build instructions.
    -   [ ] **Refine `AGENTS.md`:** Create a dedicated file with instructions for AI agents, outlining project structure and development conventions.
-   **[ ] Enhance Robustness and Performance:**
    -   [ ] **Add Robust Error Handling:** Implement detailed, user-facing error messages. For instance, if background removal fails, guide the user on how to proceed.
    -   [ ] **Add AR Plane Detection Guidance:** Implement a mechanism to guide the user if the AR system fails to detect a surface (e.g., "Move your phone slowly to scan the area").
    -   [ ] **Add Automated Tests:** Create a suite of unit and instrumentation tests to ensure code quality and prevent regressions.

### **Medium Priority**

-   **[ ] Enhance User Experience (UX):**
    -   [ ] **Create User Onboarding:** Design and implement a tutorial or onboarding flow to explain the three different modes (AR, Mock-up, On-the-Go) to new users.
    -   [ ] **Refine Adjustment Slider UI:** Integrate the sliders into an expandable panel on the main screen instead of a full-screen popup.
    -   [ ] **Add Gesture Feedback:** Provide visual feedback in Mock-up mode when a scale or rotation gesture is active.

### **Low Priority / Future Ideas**

-   **[ ] Add "Save/Export" Feature:**
    -   [ ] Allow users to save or export the final composed image from any of the modes.
-   **[ ] Implement Advanced Image Editing:**
    -   [ ] Add more advanced image adjustment tools like color balance or blending modes.
-   **[ ] Create a Project Library:**
    -   [ ] Implement functionality to save, load, and manage different mock-up projects (background, overlay, settings).
-   **[ ] Add Video Overlay Support:**
    -   [ ] Allow users to select video files as overlay textures.