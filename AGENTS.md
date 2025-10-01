# AGENT INSTRUCTIONS for GraffitiXR

This document provides technical guidance for AI agents working on the GraffitiXR Android application.

---

## **1. Project Overview**

-   **Objective:** GraffitiXR is an Android app that allows users to overlay a digital image onto a real-world view through their device's camera. It supports AR, a mock-up mode on a static image, and a simple camera overlay.
-   **Package Name:** `com.hereliesaz.graffitixr`
-   **Core Technologies:**
    -   **UI:** Jetpack Compose
    -   **AR:** ARCore with `androidx.xr` libraries (`compose`, `scenecore`, `runtime`).
    -   **Camera:** CameraX
    -   **Image Loading:** Coil
    -   **Background Removal:** Google ML Kit (Selfie Segmentation)
    -   **Architecture:** MVVM (Model-View-ViewModel)

---

## **2. Project Structure & Key Files**

The project follows a standard Android structure. All relevant source code is located in `app/src/main/java/com/hereliesaz/graffitixr/`.

-   `MainActivity.kt`: The single activity entry point. It sets up the main UI, handles permissions, and observes the `MainViewModel`.
-   `MainViewModel.kt`: The brain of the application. It holds the `UiState`, manages all business logic, and exposes functions for user interactions. **All state changes must be initiated from here.**
-   `UiState.kt`: A Kotlin data class that represents the entire state of the UI at any given moment. It is a single source of truth, modeled as an immutable `StateFlow`.
-   `ImageProcessor.kt`: A singleton object responsible for compute-intensive image operations, primarily the `removeBackground` function. All functions here should be called from a background thread (`Dispatchers.IO`).
-   `StaticImageEditor.kt`: A composable that handles the UI for the "Mock-up Mode," allowing the user to warp an image by dragging its corners.
-   `SettingsScreen.kt`: The composable for the app's settings UI.
-   `AppNavRail.kt`: A custom navigation rail composable used for the main UI actions.

---

## **3. Development Guidelines & Conventions**

### **State Management**

-   **Immutability is Key:** The `UiState` is immutable. To change the state, you must call a function on the `MainViewModel`, which then uses the `.update` method on the `_uiState` `MutableStateFlow` with a `copy()` of the new state. Do not attempt to modify the state directly from the UI.
-   **Use `viewModelScope`:** All coroutines initiated from the `MainViewModel` must use `viewModelScope.launch`.
-   **Background Work:** For any long-running or intensive operations (like file I/O or image processing), use `withContext(Dispatchers.IO)` within the `viewModelScope` to switch to a background thread.

### **Code Style & Documentation**

-   **KDoc is Mandatory:** All new public classes, methods, and complex properties **must** be documented with comprehensive KDocs. The goal is exhaustive documentation.
-   **Follow Kotlin Conventions:** Adhere to the standard Kotlin coding conventions for formatting, naming, and style.

### **Error Handling**

-   **Log Everything:** When catching exceptions, always log the full exception using `android.util.Log` (e.g., `Log.e("YourTag", "An error occurred", exception)`).
-   **User-Friendly Messages:** Provide clear, actionable error messages to the user via the `snackbarMessage` property in the `UiState`. Avoid showing technical jargon or stack traces in the UI.

### **Building and Testing**

-   **Build Command:** To build the project from the root directory, run `./gradlew build`.
-   **Testing:** The project currently lacks a test suite. When adding new features, consider adding corresponding unit tests in `app/src/test/` and instrumentation tests in `app/src/androidTest/`.

---

## **4. Current Project Goals**

Refer to `TODO.md` for a complete and up-to-date list of project tasks and priorities. The current high-priority items involve enhancing documentation, improving error handling, and adding automated tests.