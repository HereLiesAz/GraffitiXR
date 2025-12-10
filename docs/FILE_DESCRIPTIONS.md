# File Descriptions

This file provides a brief but thorough description of what each non-ignored file in the project is supposed to do.

## `AGENTS.md`

Provides guidance for AI agents working with the codebase.

## `README.md`

The main README for the project.

## `app/CMakeLists.txt`

CMake script for building the native C++ code in the app module.

## `app/build.gradle.kts`

Gradle build script for the main application module.

## `app/google-services.json.template`

Template for Google Services configuration.

## `app/libs/vuforia.aar`

The Vuforia Augmented Reality library.

## `app/proguard-rules.pro`

Configuration for ProGuard to shrink, obfuscate, and optimize the app.

## `app/src/main/AndroidManifest.xml`

The Android application manifest file.

## `app/src/main/assets/Astronaut.jpg`

An image asset of an astronaut.

## `app/src/main/assets/Lander.jpg`

An image asset of a lander.

## `app/src/main/assets/shaders/fragment_shader.glsl`

The fragment shader for OpenGL rendering.

## `app/src/main/assets/shaders/vertex_shader.glsl`

The vertex shader for OpenGL rendering.

## `app/src/main/cpp/GraffitiJNI.cpp`

The JNI implementation for the native functions.

## `app/src/main/ic_launcher-playstore.png`

The application icon for the Google Play Store.

## `app/src/main/java/com/hereliesaz/graffitixr/ARCameraScreen.kt`

Screen for the AR camera view.

## `app/src/main/java/com/hereliesaz/graffitixr/ArRenderer.kt`

Renderer for AR content.

## `app/src/main/java/com/hereliesaz/graffitixr/ArState.kt`

Defines the state for the AR components of the application.

## `app/src/main/java/com/hereliesaz/graffitixr/ArView.kt`

View component for AR.

## `app/src/main/java/com/hereliesaz/graffitixr/EditorMode.kt`

Enum defining editor modes.

## `app/src/main/java/com/hereliesaz/graffitixr/MainActivity.kt`

The main activity of the application.

## `app/src/main/java/com/hereliesaz/graffitixr/MainScreen.kt`

The main Jetpack Compose screen for the application.

## `app/src/main/java/com/hereliesaz/graffitixr/MainViewModel.kt`

The main ViewModel for the application, handling business logic and state.

## `app/src/main/java/com/hereliesaz/graffitixr/MainViewModelFactory.kt`

A factory for creating instances of the MainViewModel.

## `app/src/main/java/com/hereliesaz/graffitixr/Parcelers.kt`

Parcelable implementations.

## `app/src/main/java/com/hereliesaz/graffitixr/RotationAxis.kt`

Defines the possible axes of rotation for gestures.

## `app/src/main/java/com/hereliesaz/graffitixr/TapFeedback.kt`

Feedback for tap gestures.

## `app/src/main/java/com/hereliesaz/graffitixr/UiState.kt`

Defines the UI state for the application.

## `app/src/main/java/com/hereliesaz/graffitixr/composables/AdjustmentsPanel.kt`

UI panel for image adjustments.

## `app/src/main/java/com/hereliesaz/graffitixr/composables/CurvesAdjustment.kt`

UI for curves adjustment.

## `app/src/main/java/com/hereliesaz/graffitixr/composables/DrawingCanvas.kt`

Canvas for drawing.

## `app/src/main/java/com/hereliesaz/graffitixr/composables/GestureFeedback.kt`

Visual feedback for gestures.

## `app/src/main/java/com/hereliesaz/graffitixr/composables/GhostScreen.kt`

Ghost screen composable.

## `app/src/main/java/com/hereliesaz/graffitixr/composables/HelpScreen.kt`

Help and tutorial screen.

## `app/src/main/java/com/hereliesaz/graffitixr/composables/Knob.kt`

Rotary knob UI component.

## `app/src/main/java/com/hereliesaz/graffitixr/composables/MockupScreen.kt`

A composable screen for creating mockups on static images.

## `app/src/main/java/com/hereliesaz/graffitixr/composables/OnboardingScreen.kt`

Screen for onboarding.

## `app/src/main/java/com/hereliesaz/graffitixr/composables/ProgressIndicator.kt`

A composable for displaying a progress indicator.

## `app/src/main/java/com/hereliesaz/graffitixr/composables/ProjectLibraryScreen.kt`

Screen for managing projects.

## `app/src/main/java/com/hereliesaz/graffitixr/composables/RefinementScreen.kt`

Screen for refining selection.

## `app/src/main/java/com/hereliesaz/graffitixr/composables/RotationAxisFeedback.kt`

A composable to provide visual feedback for the current rotation axis.

## `app/src/main/java/com/hereliesaz/graffitixr/composables/SettingsScreen.kt`

A composable screen for application settings.

## `app/src/main/java/com/hereliesaz/graffitixr/composables/TapFeedbackEffect.kt`

A composable that provides visual feedback for tap gestures.

## `app/src/main/java/com/hereliesaz/graffitixr/composables/TitleOverlay.kt`

A composable for overlaying a title on the screen.

## `app/src/main/java/com/hereliesaz/graffitixr/composables/TraceScreen.kt`

Screen for tracing mode.

## `app/src/main/java/com/hereliesaz/graffitixr/data/BlendModeSerializer.kt`

Serializer for blend modes.

## `app/src/main/java/com/hereliesaz/graffitixr/data/Fingerprint.kt`

Data class for AR fingerprint.

## `app/src/main/java/com/hereliesaz/graffitixr/data/FingerprintSerializer.kt`

Serializer for fingerprint.

## `app/src/main/java/com/hereliesaz/graffitixr/data/GithubRelease.kt`

Data class for GitHub release info.

## `app/src/main/java/com/hereliesaz/graffitixr/data/KeyPointSerializer.kt`

Serializer for OpenCV KeyPoints.

## `app/src/main/java/com/hereliesaz/graffitixr/data/MatSerializer.kt`

Serializer for OpenCV Mat.

## `app/src/main/java/com/hereliesaz/graffitixr/data/OffsetSerializer.kt`

Serializer for Offset.

## `app/src/main/java/com/hereliesaz/graffitixr/data/ProjectData.kt`

A data class representing the state of a user's project.

## `app/src/main/java/com/hereliesaz/graffitixr/data/Serializers.kt`

Custom serializers for data classes used in the application.

## `app/src/main/java/com/hereliesaz/graffitixr/dialogs/AdjustmentSliderDialog.kt`

A dialog for adjusting image properties with a slider.

## `app/src/main/java/com/hereliesaz/graffitixr/dialogs/ColorBalanceDialog.kt`

A dialog for adjusting the color balance of an image.

## `app/src/main/java/com/hereliesaz/graffitixr/dialogs/CurvesDialog.kt`

Dialog for curves adjustment.

## `app/src/main/java/com/hereliesaz/graffitixr/dialogs/DoubleTapHintDialog.kt`

A dialog to hint to the user to double tap.

## `app/src/main/java/com/hereliesaz/graffitixr/dialogs/OnboardingDialog.kt`

A dialog for the application's onboarding flow.

## `app/src/main/java/com/hereliesaz/graffitixr/dialogs/SaveProjectDialog.kt`

Dialog for saving project.

## `app/src/main/java/com/hereliesaz/graffitixr/rendering/AugmentedImageRenderer.kt`

Renders augmented images in AR.

## `app/src/main/java/com/hereliesaz/graffitixr/rendering/BackgroundRenderer.kt`

Renders the camera background.

## `app/src/main/java/com/hereliesaz/graffitixr/rendering/HomographyHelper.kt`

Helper for homography calculations.

## `app/src/main/java/com/hereliesaz/graffitixr/rendering/PlaneRenderer.kt`

Renders AR planes.

## `app/src/main/java/com/hereliesaz/graffitixr/rendering/PointCloudRenderer.kt`

Renders AR point clouds.

## `app/src/main/java/com/hereliesaz/graffitixr/rendering/ProjectedImageRenderer.kt`

Renders the projected image.

## `app/src/main/java/com/hereliesaz/graffitixr/rendering/ShaderUtil.kt`

Utilities for shader management.

## `app/src/main/java/com/hereliesaz/graffitixr/rendering/SimpleQuadRenderer.kt`

Simple quad renderer.

## `app/src/main/java/com/hereliesaz/graffitixr/ui/theme/Color.kt`

Defines the color palette for the application's theme.

## `app/src/main/java/com/hereliesaz/graffitixr/ui/theme/Theme.kt`

Defines the overall theme for the application.

## `app/src/main/java/com/hereliesaz/graffitixr/ui/theme/Typography.kt`

Defines the typography for the application's theme.

## `app/src/main/java/com/hereliesaz/graffitixr/utils/BitmapUtils.kt`

Utilities for bitmap manipulation.

## `app/src/main/java/com/hereliesaz/graffitixr/utils/BlendModeParceler.kt`

Parceler for blend modes.

## `app/src/main/java/com/hereliesaz/graffitixr/utils/Capture.kt`

Utility functions for capturing the screen.

## `app/src/main/java/com/hereliesaz/graffitixr/utils/CurvesUtil.kt`

Utilities for curve calculations.

## `app/src/main/java/com/hereliesaz/graffitixr/utils/DisplayRotationHelper.kt`

Helper for display rotation.

## `app/src/main/java/com/hereliesaz/graffitixr/utils/ImageUtils.kt`

Utility functions for working with images.

## `app/src/main/java/com/hereliesaz/graffitixr/utils/MultiGestureDetector.kt`

A custom gesture detector for handling multiple gestures simultaneously.

## `app/src/main/java/com/hereliesaz/graffitixr/utils/OnboardingManager.kt`

Manages the application's onboarding process.

## `app/src/main/java/com/hereliesaz/graffitixr/utils/Parcelers.kt`

Custom parcelers for data classes used in the application.

## `app/src/main/java/com/hereliesaz/graffitixr/utils/ProgressCalculator.kt`

Calculates progress of image tracing.

## `app/src/main/java/com/hereliesaz/graffitixr/utils/ProjectManager.kt`

Manages project loading and saving.

## `app/src/main/java/com/hereliesaz/graffitixr/utils/RotationGestureDetector.kt`

A custom gesture detector for handling rotation gestures.

## `app/src/main/java/com/hereliesaz/graffitixr/utils/Texture.kt`

Utility functions for working with OpenGL textures.

## `app/src/main/java/com/hereliesaz/graffitixr/utils/Utils.kt`

General utility functions for the application.

## `app/src/main/java/com/hereliesaz/graffitixr/utils/YuvToRgbConverter.kt`

Converts YUV to RGB.

## `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`

Application resource file.

## `app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml`

Application resource file.

## `app/src/main/res/mipmap-hdpi/ic_launcher.webp`

Application resource file.

## `app/src/main/res/mipmap-hdpi/ic_launcher_foreground.webp`

Application resource file.

## `app/src/main/res/mipmap-hdpi/ic_launcher_monochrome.webp`

Application resource file.

## `app/src/main/res/mipmap-hdpi/ic_launcher_round.webp`

Application resource file.

## `app/src/main/res/mipmap-mdpi/ic_launcher.webp`

Application resource file.

## `app/src/main/res/mipmap-mdpi/ic_launcher_foreground.webp`

Application resource file.

## `app/src/main/res/mipmap-mdpi/ic_launcher_monochrome.webp`

Application resource file.

## `app/src/main/res/mipmap-mdpi/ic_launcher_round.webp`

Application resource file.

## `app/src/main/res/mipmap-xhdpi/ic_launcher.webp`

Application resource file.

## `app/src/main/res/mipmap-xhdpi/ic_launcher_foreground.webp`

Application resource file.

## `app/src/main/res/mipmap-xhdpi/ic_launcher_monochrome.webp`

Application resource file.

## `app/src/main/res/mipmap-xhdpi/ic_launcher_round.webp`

Application resource file.

## `app/src/main/res/mipmap-xxhdpi/ic_launcher.webp`

Application resource file.

## `app/src/main/res/mipmap-xxhdpi/ic_launcher_foreground.webp`

Application resource file.

## `app/src/main/res/mipmap-xxhdpi/ic_launcher_monochrome.webp`

Application resource file.

## `app/src/main/res/mipmap-xxhdpi/ic_launcher_round.webp`

Application resource file.

## `app/src/main/res/mipmap-xxxhdpi/ic_launcher.webp`

Application resource file.

## `app/src/main/res/mipmap-xxxhdpi/ic_launcher_foreground.webp`

Application resource file.

## `app/src/main/res/mipmap-xxxhdpi/ic_launcher_monochrome.webp`

Application resource file.

## `app/src/main/res/mipmap-xxxhdpi/ic_launcher_round.webp`

Application resource file.

## `app/src/main/res/values/ic_launcher_background.xml`

Application resource file.

## `app/src/main/res/values/strings.xml`

Application resource file.

## `app/src/main/res/values/themes.xml`

Application resource file.

## `app/src/main/res/xml/backup_rules.xml`

Application resource file.

## `app/src/main/res/xml/data_extraction_rules.xml`

Application resource file.

## `app/src/main/res/xml/provider_paths.xml`

Application resource file.

## `app/src/test/java/com/hereliesaz/graffitixr/MainViewModelTest.kt`

Unit tests for MainViewModel.

## `build.gradle.kts`

The root Gradle build script for the project.

## `docs/BLUEPRINT.md`

Technical blueprint document.

## `docs/FILE_DESCRIPTIONS.md`

This file.

## `docs/TODO.md`

Project roadmap and todo list.

## `gradle.properties`

Project-wide Gradle settings.

## `gradlew`

The Gradle wrapper script for Linux and macOS.

## `gradlew.bat`

The Gradle wrapper script for Windows.

## `settings.gradle.kts`

The Gradle settings script for the project.

## `setup_ndk.sh`

A script for setting up the Android NDK.

## `version.properties`

Version configuration properties.


