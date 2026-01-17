# File Descriptions

## Root
* `build.gradle.kts`: Project-level build config.
* `local.properties`: SDK paths (not in git).

## app/src/main/java/com/hereliesaz/graffitixr/
* `MainActivity.kt`: Entry point. Permission handling.
* `GraffitiApplication.kt`: App context, loads OpenCV/Native libs.
* `MainViewModel.kt`: Global state holder.
* `ArRenderer.kt`: GLSurfaceView renderer, ARCore lifecycle.
* `SlamManager.kt`: JNI Bridge to C++.

## app/src/main/cpp/
* `GraffitiJNI.cpp`: JNI interface methods.
* `MobileGS.cpp`: The Gaussian Splatting engine implementation.
* `MobileGS.h`: Header for the engine.
* `glm/`: Math library (headers).

## app/src/main/res/
* `raw/`: Shaders (vertex.glsl, fragment.glsl).