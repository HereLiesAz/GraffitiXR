### 4. `docs/file_descriptions.md`
**Updates:**
* Added `app/src/main/java/com/hereliesaz/graffitixr/data/Events.kt`.
* Added `app/src/main/java/com/hereliesaz/graffitixr/MappingActivity.kt`.
* Added `app/src/main/java/com/hereliesaz/graffitixr/MappingScreen.kt`.

```markdown
# File Descriptions

## Root
* `build.gradle.kts`: Project-level build config.
* `local.properties`: SDK paths (not in git).

## app/src/main/java/com/hereliesaz/graffitixr/
* `MainActivity.kt`: Entry point. Permission handling and Event observation.
* `GraffitiApplication.kt`: App context, loads OpenCV/Native libs.
* `MainViewModel.kt`: Global state holder (Decoupled from Renderer).
* `ArRenderer.kt`: GLSurfaceView renderer, ARCore lifecycle.
* `SlamManager.kt`: JNI Bridge to C++.
* `MappingActivity.kt`: Specialized activity for surveyor/mapping mode.
* `MappingScreen.kt`: UI composable for the mapping workflow.
* `MainScreen.kt`: Main UI composable using AzNavRail.

## app/src/main/java/com/hereliesaz/graffitixr/data/
* `Events.kt`: Sealed classes for App-wide events (CaptureEvent, FeedbackEvent).

## app/src/main/cpp/
* `GraffitiJNI.cpp`: JNI interface methods.
* `MobileGS.cpp`: The Gaussian Splatting engine implementation.
* `MobileGS.h`: Header for the engine.
* `glm/`: Math library (headers).

## app/src/main/res/
* `raw/`: Shaders (vertex.glsl, fragment.glsl).