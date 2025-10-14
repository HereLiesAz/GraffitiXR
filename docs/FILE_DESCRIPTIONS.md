# File Descriptions

This file provides a brief but thorough description of what each non-ignored file in the project is supposed to do.

## `.gitignore`

Specifies intentionally untracked files to ignore.

## `AGENTS.md`

Provides guidance for AI agents working with the codebase.

## `BLUEPRINT.md`

A technical specification document for the project.

## `README.md`

The main README for the project.

## `TODO.md`

A roadmap and list of future work for the project.

## `app/CMakeLists.txt`

CMake script for building the native C++ code in the app module.

## `app/build.gradle.kts`

Gradle build script for the main application module.

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

## `app/src/main/java/com/hereliesaz/graffitixr/ArState.kt`

Defines the state for the AR components of the application.

## `app/src/main/java/com/hereliesaz/graffitixr/MainActivity.kt`

The main activity of the application.

## `app/src/main/java/com/hereliesaz/graffitixr/MainScreen.kt`

The main Jetpack Compose screen for the application.

## `app/src/main/java/com/hereliesaz/graffitixr/MainViewModel.kt`

The main ViewModel for the application, handling business logic and state.

## `app/src/main/java/com/hereliesaz/graffitixr/MainViewModelFactory.kt`

A factory for creating instances of the MainViewModel.

## `app/src/main/java/com/hereliesaz/graffitixr/RotationAxis.kt`

Defines the possible axes of rotation for gestures.

## `app/src/main/java/com/hereliesaz/graffitixr/UiState.kt`

Defines the UI state for the application.

## `app/src/main/java/com/hereliesaz/graffitixr/VuforiaJNI.kt`

The JNI bridge for communicating with the native Vuforia C++ code.

## `app/src/main/java/com/hereliesaz/graffitixr/VuforiaManager.kt`

Manages the lifecycle and state of the Vuforia engine.

## `app/src/main/java/com/hereliesaz/graffitixr/composables/ImageTraceScreen.kt`

A composable screen for tracing images in non-AR mode.

## `app/src/main/java/com/hereliesaz/graffitixr/composables/MockupScreen.kt`

A composable screen for creating mockups on static images.

## `app/src/main/java/com/hereliesaz/graffitixr/composables/ProgressIndicator.kt`

A composable for displaying a progress indicator.

## `app/src/main/java/com/hereliesaz/graffitixr/composables/RotationAxisFeedback.kt`

A composable to provide visual feedback for the current rotation axis.

## `app/src/main/java/com/hereliesaz/graffitixr/composables/SettingsScreen.kt`

A composable screen for application settings.

## `app/src/main/java/com/hereliesaz/graffitixr/composables/TapFeedbackEffect.kt`

A composable that provides visual feedback for tap gestures.

## `app/src/main/java/com/hereliesaz/graffitixr/composables/TitleOverlay.kt`

A composable for overlaying a title on the screen.

## `app/src/main/java/com/hereliesaz/graffitixr/composables/VuforiaCameraScreen.kt`

A composable that displays the camera feed from Vuforia.

## `app/src/main/java/com/hereliesaz/graffitixr/composables/VuforiaRenderer.kt`

A composable responsible for rendering the Vuforia camera view.

## `app/src/main/java/com/hereliesaz/graffitixr/data/ProjectData.kt`

A data class representing the state of a user's project.

## `app/src/main/java/com/hereliesaz/graffitixr/data/Serializers.kt`

Custom serializers for data classes used in the application.

## `app/src/main/java/com/hereliesaz/graffitixr/dialogs/AdjustmentSliderDialog.kt`

A dialog for adjusting image properties with a slider.

## `app/src/main/java/com/hereliesaz/graffitixr/dialogs/ColorBalanceDialog.kt`

A dialog for adjusting the color balance of an image.

## `app/src/main/java/com/hereliesaz/graffitixr/dialogs/DoubleTapHintDialog.kt`

A dialog to hint to the user to double tap.

## `app/src/main/java/com/hereliesaz/graffitixr/dialogs/OnboardingDialog.kt`

A dialog for the application's onboarding flow.

## `app/src/main/java/com/hereliesaz/graffitixr/ui/theme/Color.kt`

Defines the color palette for the application's theme.

## `app/src/main/java/com/hereliesaz/graffitixr/ui/theme/Theme.kt`

Defines the overall theme for the application.

## `app/src/main/java/com/hereliesaz/graffitixr/ui/theme/Typography.kt`

Defines the typography for the application's theme.

## `app/src/main/java/com/hereliesaz/graffitixr/utils/Capture.kt`

Utility functions for capturing the screen.

## `app/src/main/java/com/hereliesaz/graffitixr/utils/ImageUtils.kt`

Utility functions for working with images.

## `app/src/main/java/com/hereliesaz/graffitixr/utils/MultiGestureDetector.kt`

A custom gesture detector for handling multiple gestures simultaneously.

## `app/src/main/java/com/hereliesaz/graffitixr/utils/OnboardingManager.kt`

Manages the application's onboarding process.

## `app/src/main/java/com/hereliesaz/graffitixr/utils/Parcelers.kt`

Custom parcelers for data classes used in the application.

## `app/src/main/java/com/hereliesaz/graffitixr/utils/RotationGestureDetector.kt`

A custom gesture detector for handling rotation gestures.

## `app/src/main/java/com/hereliesaz/graffitixr/utils/Texture.kt`

Utility functions for working with OpenGL textures.

## `app/src/main/java/com/hereliesaz/graffitixr/utils/Utils.kt`

General utility functions for the application.

## `app/src/main/res/drawable/graffitixr_logo.webp`

The logo for the application.

## `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`

The adaptive launcher icon for the application.

## `app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml`

The round adaptive launcher icon for the application.

## `app/src/main/res/mipmap-hdpi/ic_launcher.webp`

The high-density launcher icon.

## `app/src/main/res/mipmap-hdpi/ic_launcher_foreground.webp`

The foreground for the high-density launcher icon.

## `app/src/main/res/mipmap-hdpi/ic_launcher_monochrome.webp`

The monochrome high-density launcher icon.

## `app/src/main/res/mipmap-hdpi/ic_launcher_round.webp`

The high-density round launcher icon.

## `app/src/main/res/mipmap-mdpi/ic_launcher.webp`

The medium-density launcher icon.

## `app/src/main/res/mipmap-mdpi/ic_launcher_foreground.webp`

The foreground for the medium-density launcher icon.

## `app/src/main/res/mipmap-mdpi/ic_launcher_monochrome.webp`

The monochrome medium-density launcher icon.

## `app/src/main/res/mipmap-mdpi/ic_launcher_round.webp`

The medium-density round launcher icon.

## `app/src/main/res/mipmap-xhdpi/ic_launcher.webp`

The extra-high-density launcher icon.

## `app/src/main/res/mipmap-xhdpi/ic_launcher_foreground.webp`

The foreground for the extra-high-density launcher icon.

## `app/src/main/res/mipmap-xhdpi/ic_launcher_monochrome.webp`

The monochrome extra-high-density launcher icon.

## `app/src/main/res/mipmap-xhdpi/ic_launcher_round.webp`

The extra-high-density round launcher icon.

## `app/src/main/res/mipmap-xxhdpi/ic_launcher.webp`

The extra-extra-high-density launcher icon.

## `app/src/main/res/mipmap-xxhdpi/ic_launcher_foreground.webp`

The foreground for the extra-extra-high-density launcher icon.

## `app/src/main/res/mipmap-xxhdpi/ic_launcher_monochrome.webp`

The monochrome extra-extra-high-density launcher icon.

## `app/src/main/res/mipmap-xxhdpi/ic_launcher_round.webp`

The extra-extra-high-density round launcher icon.

## `app/src/main/res/mipmap-xxxhdpi/ic_launcher.webp`

The extra-extra-extra-high-density launcher icon.

## `app/src/main/res/mipmap-xxxhdpi/ic_launcher_foreground.webp`

The foreground for the extra-extra-extra-high-density launcher icon.

## `app/src/main/res/mipmap-xxxhdpi/ic_launcher_monochrome.webp`

The monochrome extra-extra-extra-high-density launcher icon.

## `app/src/main/res/mipmap-xxxhdpi/ic_launcher_round.webp`

The extra-extra-extra-high-density round launcher icon.

## `app/src/main/res/values/ic_launcher_background.xml`

The background color for the launcher icon.

## `app/src/main/res/values/strings.xml`

The string resources for the application.

## `app/src/main/res/values/themes.xml`

The theme resources for the application.

## `app/src/main/res/xml/backup_rules.xml`

The backup rules for the application.

## `app/src/main/res/xml/data_extraction_rules.xml`

The data extraction rules for the application.

## `app/src/main/res/xml/provider_paths.xml`

The provider paths for the application.

## `build.gradle.kts`

The root Gradle build script for the project.

## `docs/FILE_DESCRIPTIONS.md`

This file.

## `gradle.properties`

Project-wide Gradle settings.

## `gradle/libs.versions.toml`

The Gradle version catalog for managing dependencies.

## `gradle/wrapper/gradle-wrapper.jar`

The Gradle wrapper JAR file.

## `gradle/wrapper/gradle-wrapper.properties`

The Gradle wrapper properties file.

## `gradlew`

The Gradle wrapper script for Linux and macOS.

## `gradlew.bat`

The Gradle wrapper script for Windows.

## `settings.gradle.kts`

The Gradle settings script for the project.

## `setup_ndk.sh`

A script for setting up the Android NDK.

## `vuforia.zip`

A zip file containing the Vuforia SDK.

## `vuforia/CMakeLists.txt`

CMake script for building the native C++ code in the vuforia module.

## `vuforia/build.gradle.kts`

Gradle build script for the vuforia module.

## `vuforia/build`

This directory and its contents are build artifacts for the vuforia module.

## `vuforia/licenses`

This directory and its contents are the Vuforia license.

## `vuforia/samples`

This directory and its contents are sample code for the Vuforia SDK.

## `vuforia/sdk`

This directory and its contents are the Vuforia SDK.
