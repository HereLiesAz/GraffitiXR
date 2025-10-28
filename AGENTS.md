# AGENT INSTRUCTIONS for GraffitiXR

This document provides technical guidance for AI agents working on the GraffitiXR Android application.

---

## **1. Conceptual Overview**

### **What it is**
This is an Android application that uses augmented reality (AR) to project an image onto a wall or other surface. It allows the user to see how a mural or other artwork would look in a real-world environment before it is created.

### **What it's for**
The main purpose of this application is to help artists, designers, and homeowners visualize how a mural or other large-scale artwork will look in a specific location. It can be used to test different images, sizes, and placements without the need for physical mockups.

### **How it works**
If AR is enabled, the application uses the device's camera and ARCore. The user can create an "Image Target" at runtime by pointing the camera at a real-world object or surface and tapping a button. This captures the camera view and uses it to create a target that ARCore can recognize and track. The user can then select an image from their device to project onto this newly created target. The app uses a custom rendering engine to render the image on the target, with controls for opacity, contrast, and saturation. If AR is not enabled, the device will need to be placed on a tripod, and simply overlays the image onto the camera view, with the same adjustable settings for the image.

### **How the user interacts with it**
The user interacts with the application through a simple user interface. The main screen shows the camera view with the AR overlay. There are buttons to select an image and adjust the properties of the projected image.

1) The user points the camera at a wall or other surface.
2) The user taps "Create Target" to generate a trackable AR target from the camera view.
3) The user selects an image from their device.
4) The app projects the image onto the AR target.
5) The user can then adjust the opacity, contrast, and saturation of the image to see how it looks in the environment.

---

## **2. Technical Details**

-   **Package Name:** `com.hereliesaz.graffitixr`
-   **Architecture:** MVVM (Model-View-ViewModel) using Jetpack Compose and a single Activity (`MainActivity.kt`).
-   **State Management:** All UI state is held in the immutable `UiState.kt` data class and managed via a `StateFlow` in `MainViewModel.kt`.

### **Key Files**
-   `MainActivity.kt`: The single activity entry point. It handles permissions, initializes the ARCore session, and hosts the `MainScreen` composable.
-   `MainViewModel.kt`: The central logic hub. It manages all state changes and user events.
-   `ARScreen.kt`: The composable for the AR experience.
-   `ARCoreRenderer.kt`: The renderer for the AR experience.
-   `ImageTraceScreen.kt`: The composable for the simple camera overlay mode (non-AR).
-   `MockupScreen.kt`: The composable for the mock-up mode on a static background image.

### **Development Guidelines**
-   **State:** All state changes MUST be initiated via a function call on the `MainViewModel`.
-   **Documentation:** All new public code MUST be documented with exhaustive KDocs.
-   **Testing:** New features should be accompanied by corresponding unit tests in `app/src/test/`.
-   **Critical Dependencies:** The OpenCV dependency is **critical** for the AR fingerprinting feature and **must not be removed**.

### **AR Persistence (Fingerprinting)**
A core feature is the ability to save an AR project and have the digital overlay reappear in the correct physical location when the project is reloaded. This is mission-critical for the app's professional use case.

-   **How it Works:** When an AR target is created, the app uses OpenCV's ORB feature detector to extract a unique "fingerprint" (keypoints and descriptors) from the target image.
-   **Serialization:** This fingerprint data, which consists of non-standard OpenCV types, is serialized into a JSON string using custom serializers (`KeyPointSerializer`, `MatSerializer`) and saved within the project file.
-   **Reloading:** When a project is loaded, the app reloads the original target image `Bitmap` and uses that to reconstruct the `AugmentedImageDatabase` for live ARCore tracking. The fingerprint's purpose is for stable, persistent storage of the target's identity, not for live tracking.
-   **Do Not Remove:** The entire OpenCV-based fingerprinting and serialization pipeline is essential. Do not modify or remove it without a full understanding of the persistence architecture.

---

## **3. Current Project Goals**

Refer to `TODO.md` for the up-to-date project backlog. The next high-priority task is to enhance the user experience by creating an onboarding flow and adding more visual feedback for gestures.

---
