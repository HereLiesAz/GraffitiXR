# File Descriptions

This document provides a comprehensive catalog of the files in the GraffitiXR repository, detailing their purpose, responsibilities, and key relationships.

## **Root Directory**

### `AGENTS.md`
**Critical.** The primary instruction manual for AI agents. It defines the project's "laws of physics," including strict commit rules, architectural overviews, and the index for this documentation folder. **Must be read first.**

### `README.md`
The user-facing documentation. Provides a high-level overview of the app, installation instructions, and feature highlights.

### `build.gradle.kts`
The root-level Gradle build script. Configures the buildscript classpath (Android Gradle Plugin, Kotlin plugin) and clean tasks.

### `settings.gradle.kts`
Defines the project structure and included modules (`:app`). Configures plugin repositories (Google, Maven Central, Gradle Plugin Portal).

### `gradle.properties`
Global Gradle configuration properties (e.g., JVM args, caching, AndroidX usage).

### `version.properties`
**Dynamic Versioning.** Stores the `major` and `minor` version numbers. The build script reads this and calculates the `patch` (commits since last tag) and `build` (total commits) numbers dynamically.

### `setup_ndk.sh`
A utility script to help configure the Android NDK environment variable `ANDROID_NDK_HOME` if it's missing.

## **Application Module (`app/`)**

### `app/build.gradle.kts`
The app-module build script.
-   **Plugins:** `com.android.application`, `kotlin-android`, `kotlin-parcelize`, `kotlinx-serialization`.
-   **Config:** `minSdk`, `targetSdk`, `versionCode`/`versionName` calculation.
-   **Dependencies:** Jetpack Compose, ARCore, CameraX, OpenCV, Coil, ML Kit, Coroutines.

### `app/google-services.json.template`
**Secret Injection.** A template file for the Google Services configuration. The CI/CD pipeline uses `envsubst` to replace placeholders (e.g., `${GOOGLE_SERVICES_API_KEY}`) with real secrets before building.

### `app/proguard-rules.pro`
ProGuard/R8 configuration rules. Essential for preserving code that is accessed via reflection or JNI (like OpenCV and ARCore classes) during release builds.

## **Source Code (`app/src/main/java/com/hereliesaz/graffitixr/`)**

### **Root Package**

#### `MainActivity.kt`
The single `Activity` entry point.
-   **Responsibilities:** Initializes OpenCV, handles runtime permissions (Camera), manages the global `MainViewModel`, and hosts the Compose UI content. It also sets up the Jetpack Navigation `NavController` and `NavHost`.
-   **Key Features:** Implements a hidden "Unlock" mechanism via volume keys.

#### `MainViewModel.kt`
**Core Logic.** The brain of the application.
-   **State:** Manages `UiState` via `StateFlow`.
-   **Logic:** Handles AR target creation, image processing triggers, auto-saving, project management, and user intents.

#### `MainViewModelFactory.kt`
Boilerplate factory for dependency injection of the `Application` context into `MainViewModel`.

#### `UiState.kt`
**Source of Truth.** An immutable, Parcelable data class that holds the entire state of the UI (slider values, modes, AR status, file URIs).

#### `ArRenderer.kt`
**AR Engine.** A custom `GLSurfaceView.Renderer`.
-   **Responsibilities:** Manages the ARCore `Session`, handles the render loop (`onDrawFrame`), performs background analysis (OpenCV ORB), and renders 3D content (planes, point clouds, overlay images).

#### `ArView.kt`
**Compose Bridge.** A Composable that wraps `GLSurfaceView` and `ArRenderer`. It connects the Compose UI gestures (tap, pan, zoom) to the OpenGL renderer.

#### `ArState.kt`
Enum defining the high-level AR session state: `SEARCHING` (looking for planes), `LOCKED` (target found), `PLACED` (anchor set).

#### `EditorMode.kt`
Enum defining the application's operational modes: `STATIC` (Mockup), `OVERLAY` (Trace), `AR` (Augmented Reality), `HELP`, etc.

#### `GraffitiApplication.kt`
The `Application` subclass. Initializes global components like the `CrashHandler`.

#### `CrashHandler.kt` & `CrashActivity.kt`
**Error Reporting.** A custom uncaught exception handler that launches a dedicated Activity to display stack traces and facilitate GitHub issue reporting.

### **`composables/` Package**

#### `MainScreen.kt`
The top-level Composable for the main UI. Switches content based on `uiState.editorMode`.

#### `ARScreen.kt` (Placeholder/Wrapper)
Likely wraps `ArView` or handles the AR-specific UI overlay.

#### `OverlayScreen.kt`
Implements the "Trace" mode using CameraX (`CameraPreview`). Allows 2D image overlay on a live camera feed without AR tracking.

#### `MockupScreen.kt`
Implements the "Mockup" mode. Displays a static background image and allows 4-point perspective warping of the overlay.

#### `SettingsScreen.kt`
Displays app version, permissions, and handles the "Check for Updates" logic (GitHub API).

#### `HelpScreen.kt`
A pager-based onboarding tutorial explaining the app's features.

#### `TargetRefinementScreen.kt`
The UI for creating/editing the AR target mask. Visualizes OpenCV keypoints and allows painting/erasing the mask.

#### `UnwarpScreen.kt`
UI for the "Rectify Image" feature. Allows the user to drag 4 corners on a captured image to define a planar surface, providing a magnifier loop for precision.

#### `AdjustmentsPanel.kt`
The bottom sheet containing image controls (Opacity, Contrast, etc.). Uses custom `Knob` components.

#### `Knob.kt`
A custom rotary control Composable that mimics physical audio knobs.

### **`data/` Package**

#### `ProjectData.kt`
**Persistence Model.** The Serializable data class used for saving/loading projects. Mirrors the persistent fields of `UiState`.

#### `Fingerprint.kt`
**AR Identity.** Stores the OpenCV `KeyPoint`s and `Descriptor`s that uniquely identify an AR target.

#### `Serializers.kt`
Custom `KSerializer` implementations for third-party types (OpenCV `Mat`, Android `Bitmap`, `BlendMode`) to support `kotlinx.serialization`.

#### `GithubRelease.kt`
Data model for parsing GitHub API responses during update checks.

### **`rendering/` Package**

#### `BackgroundRenderer.kt`
Renders the texture from the device camera onto a full-screen quad using a custom GLSL shader. Handles the `GL_TEXTURE_EXTERNAL_OES` target.

#### `PlaneRenderer.kt`
Visualizes detected AR planes as a grid of triangles. Essential for user feedback during the scanning phase.

#### `PointCloudRenderer.kt`
Visualizes raw 3D feature points detected by ARCore. Helpful for debugging tracking quality.

#### `SimpleQuadRenderer.kt`
Renders the user's overlay image in 3D space. Handles transparency, color adjustments (brightness, contrast), and blending.

#### `ShaderUtil.kt`
Helper utility for loading GLSL shader code, compiling shaders, and linking programs.

#### `AugmentedImageRenderer.kt`
Handles the rendering logic specifically for ARCore's `AugmentedImage` trackables (centering the image on the physical target).

### **`utils/` Package**

#### `ProjectManager.kt`
**I/O Handler.** Manages reading/writing project JSON files and ZIP archives. Handles the export process.

#### `BitmapUtils.kt`
Utilities for loading, scaling, rotating, and saving Bitmaps.

#### `ImageUtils.kt`
Advanced image processing helper (applying masks, cropping).

#### `BackgroundRemover.kt`
Wrapper around ML Kit's Subject Segmentation API to remove backgrounds from images.

#### `YuvToRgbConverter.kt`
Converts camera `Image` objects (YUV_420_888) to standard Android `Bitmap`s (ARGB_8888).

#### `DisplayRotationHelper.kt`
Helper class to track device display rotation and keep the ARCore session synced.

## **Resources (`app/src/main/res/`)**

### `xml/provider_paths.xml`
Defines the file paths that `FileProvider` is allowed to share (e.g., `cache/images/`). Critical for sharing images to other apps or the internal crash reporter.

### `values/strings.xml`
Contains all user-facing text strings.

### `raw/` or `assets/shaders/`
Contains the GLSL shader source code (`.glsl`) for the renderers.
