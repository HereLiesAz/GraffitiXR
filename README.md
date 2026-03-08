# GraffitiXR

GraffitiXR is an android app for street artists. There are plenty of apps that overlay an image on your camera view so you can trace it, but when I'm painting a mural based on a sketch I have saved on my phone, using a tripod can really ebb my flow. We're all over the damn place. Me, I put my phone in my pocket. Even the apps that use AR to keep the image steady and in one place can't deal with the abysmal darkness of my pocket.

So I'm making something better by repurposing the lazy grid method. I was always thinking, "Why can't these specific doodles be saved, like a persistent anchor, so the overlay is always just plain in the right spot?" So, now, that's what those doodles do. Progress tracking replaces those marks with the piece itself as you go, like an ever-evolving fingerprinted target.

Just for shits and giggles, I included the non-AR version for image tracing just like you get with those other apps, too. Just in case you cray like that. Or if you cray-cray, there's Mockup mode. Nab a picture of the wall, cuz I got some quick tools for a quick mockup. And if you've got nothing to prove, you just want something copied onto paper perfectly, Trace mode allows you to use your phone as a lightbox, keeping your screen on with the brightness turned up, locking your image into place and blocking all touches until you're finished. 

**GraffitiXR** is a local-first, offline-capable Android application for street artists. It leverages Augmented Reality (AR) and a custom C++ engine to project sketches onto walls using a confidence-based voxel mapping system.

## Key Features
*   **Offline-First:** No cloud dependencies; zero data collected.
*   **Custom Engine (MobileGS):** C++17 native engine for 3D Gaussian Splatting and spatial mapping.
*   **Full ARCore Pipeline:** Live camera feed via `BackgroundRenderer`, color frame relocalization, and ARCore Depth API — all feeding real data to the SLAM engine.
*   **AzNavRail UI:** Thumb-driven navigation for one-handed use in the field.
*   **Single GL Render Path:** `ArRenderer` handles both camera background (`BackgroundRenderer`) and SLAM voxel splats (`slamManager.draw()`) in a single `GLSurfaceView`.
*   **Multi-Lens Support:** Automatically uses dual-camera stereo depth on supported devices; falls back to optical flow.
*   **Teleological Correction:** Automatic map-to-world alignment using OpenCV fingerprinting.

## Architecture
Strictly decoupled multi-module architecture:
*   `:app` — Navigation, `ArViewport` composable, camera ownership orchestration.
*   `:feature:ar` — ARCore session, `ArRenderer`, `BackgroundRenderer`, sensor fusion, SLAM data feeding.
*   `:feature:editor` — Image manipulation, layer management.
*   `:feature:dashboard` — Project library, settings.
*   `:core:nativebridge` — `SlamManager` JNI bridge, `MobileGS` voxel engine, OpenGL ES rendering.
*   `:core:data` / `:core:domain` / `:core:common` — Clean Architecture data layer.

## Setup & Building
1.  **Libraries:** Run `./setup_libs.sh` to fetch OpenCV and GLM.
2.  **NDK:** Ensure NDK 25.x or higher is installed.
3.  **Firebase:** Copy `app/google-services.json.template` → `app/google-services.json` for local builds.
4.  **Build:** `./gradlew assembleDebug`
5.  **Tests:** `./gradlew testDebugUnitTest`

## Documentation
- [Architecture Overview](docs/ARCHITECTURE.md)
- [Native Engine Details](docs/NATIVE_ENGINE.md)
- [SLAM Configuration & Tuning](docs/SLAM_SETUP.md)
- [3D Pipeline Specification](docs/PIPELINE_3D.md)
- [Testing Strategy](docs/testing.md)
- [Screen & Mode Reference](docs/screens.md)
- [Roadmap](docs/TODO.md)
