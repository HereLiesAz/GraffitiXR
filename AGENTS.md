# AGENT INSTRUCTIONS for GraffitiXR

This document provides technical guidance for AI agents working on the GraffitiXR Android application.

---

## **1. Conceptual Overview**

### **What it is**
This is an Android application that uses augmented reality (AR) to project an image onto a wall or other surface. It allows the user to see how a mural or other artwork would look in a real-world environment before it is created.

### **What it's for**
The main purpose of this application is to help artists, designers, and homeowners visualize how a mural or other large-scale artwork will look in a specific location. It can be used to test different images, sizes, and placements without the need for physical mockups.

### **How it works**
If AR is enabled, the application uses the device's camera and the Vuforia Engine to track the environment and detect surfaces. When a surface is detected, the user can select an image from their device to project onto it. The app uses a custom rendering engine to render the image on the surface, with controls for opacity, contrast, and saturation. If AR is not enabled, then the device will need to be placed on a tripod, and simply overlays the image onto the camera view, with the same adjustable settings for the image.

### **How the user interacts with it**
The user interacts with the application through a simple user interface. The main screen shows the camera view with the AR overlay. There are buttons to select an image and adjust the properties of the projected image.

1) The user points the camera at a wall or other surface.
2) The user selects an image from their device.
3) The app projects the image onto the camera feed.
4) The user can then adjust the opacity, contrast, and saturation of the image to see how it looks in the environment.

---

## **2. Technical Details**

-   **Package Name:** `com.hereliesaz.graffitixr`
-   **Architecture:** MVVM (Model-View-ViewModel) using Jetpack Compose and a single Activity (`MainActivity.kt`).
-   **State Management:** All UI state is held in the immutable `UiState.kt` data class and managed via a `StateFlow` in `MainViewModel.kt`.

### **Key Files**
-   `MainActivity.kt`: The single activity entry point. It handles permissions and hosts the `MainScreen` composable, which acts as a router for the different editor modes.
-   `MainViewModel.kt`: The central logic hub. It manages all state changes and user events.
-   `VuforiaCameraScreen.kt`: The composable for the AR experience.
-   `VuforiaRenderer.kt`: The renderer for the AR experience.
-   `NonArModeScreen.kt`: The composable for the simple camera overlay mode.
-   `StaticImageEditor.kt`: The composable for the mock-up mode on a static background image.

### **Development Guidelines**
-   **State:** All state changes MUST be initiated via a function call on the `MainViewModel`.
-   **Documentation:** All new public code MUST be documented with exhaustive KDocs.
-   **Testing:** New features should be accompanied by corresponding unit tests in `app/src/test/`.

---

## **3. Current Project Goals**

Refer to `TODO.md` for the up-to-date project backlog. The next high-priority task is to refactor the application from ARCore to the Vuforia Engine and implement runtime Image Target creation.

---

