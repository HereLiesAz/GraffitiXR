# TODO: Transform "Graffiti XR" into "Cat-themed Calculator"

This document outlines the steps required to transform the existing "Graffiti XR" augmented reality application into a "Cat-themed Calculator" application, as per the user's request.

## 1. Project Restructuring and Renaming

- **Change App Name:** Modify `app/src/main/res/values/strings.xml` to change the `app_name` from "GraffitiXR" to "Catculator" or a similar cat-themed name.
- **Change Package Name:** Refactor the package name from `com.hereliesaz.graffitixr` to a more appropriate name like `com.company.catculator`. This will require updating `build.gradle.kts` files and the `AndroidManifest.xml`.
- **Change Theme:** Rename the theme from `GraffitiXRTheme` to `CatculatorTheme` in `app/src/main/java/com/hereliesaz/graffitixr/ui/theme/Theme.kt` and update its usage.

## 2. UI Overhaul

- **Remove AR Components:** Delete all UI components related to AR and camera functionality from `MainActivity.kt`, including `XrScene`, `CameraPreview`, and the associated layouts and composables.
- **Create Calculator UI:** Design and implement a new UI for the calculator using Jetpack Compose. This will include:
    - A display to show the current input and result.
    - A grid of buttons for numbers (0-9).
    - Buttons for basic arithmetic operations (+, -, *, /).
    - A button for the equals sign (=).
    - A clear (C) and clear entry (CE) button.
- **Incorporate Cat Theme:**
    - Add a cute cat illustration to the main screen.
    - Use a cat-themed color palette.
    - Consider using a custom font that fits the theme.

## 3. Logic Replacement

- **Remove AR Logic:** Delete all backend logic related to ARCore, image processing, and pose tracking from `MainViewModel.kt` and other related files.
- **Implement Calculator Logic:**
    - Create a new ViewModel for the calculator.
    - Implement the logic to handle button presses, build the input string, and perform calculations.
    - Handle edge cases like division by zero.
- **Implement History:**
    - Create a mechanism to store a history of recent calculations.
    - Add a UI element (e.g., a separate screen or a bottom sheet) to display the calculation history.

## 4. Dependency and Asset Management

- **Remove Unused Dependencies:** In the `app/build.gradle.kts` file, remove all dependencies related to AR, such as `androidx.xr.compose.material`, `androidx.xr.arcore`, and `com.google.ar.core`.
- **Remove Unused Assets:** Delete the 3D models from the `app/src/main/assets/models` directory.
- **Add New Assets:** Add any new assets required for the cat theme, such as images and fonts, to the `res` and `assets` directories.

## 5. Manifest and Permissions Cleanup

- **Remove Permissions:** In `app/src/main/AndroidManifest.xml`, remove the `<uses-permission android:name="android.permission.CAMERA" />` and `<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />` permissions.
- **Remove AR Feature Requirement:** Remove the `<uses-feature android:name="android.hardware.camera.any" ... />` tag from the manifest.
- **Update Activity:** Ensure the `MainActivity` is still the main launcher activity and that its theme is updated.
