# GraffitiXR
GraffitiXR is an android app for street artists. There are plenty of apps that overlay an image on your camera view so you can trace it, but when I'm painting a mural based on a sketch I have saved on my phone, using a tripod can really ebb my flow. I'm all over the damn place. Me, I put my phone in my pocket. Even the apps that use AR to keep the image steady and in one place can't deal with abysmal darkness of my pocket.

So I'm making something better by repurposing the grid method. I was always thinking, "Why can't these specific doodles be saved, like a persistent anchor, so the overlay is always just plain in the right spot?" So, now, that's what those doodles do. Progress tracking replaces those marks with the piece itself as you go, like an ever-evolving fingerprinted target.

Just for shits and giggles, I included the non-AR version for image tracing just like you get with those other apps, too. Just in case you cray like that. Or if you cray-cray, there's Mockup mode. Nab a picture of the wall, cuz I got some quick tools for a quick mockup. And if you've got nothing to prove, you just want something copied onto paper perfectly, Trace mode allows you to use your phone as a lightbox, keeping your screen on with the brightness turned up, locking your image into place and blocking all touches until you're finished.

GraffitiXR is an advanced augmented reality application for Android, designed to assist artists in visualizing and planning large-scale artworks such as murals. The app provides a suite of tools to project a digital image onto a real-world surface, offering a seamless workflow from concept to creation. It leverages Augmented Reality (AR) and a custom C++ engine to project sketches onto walls using a confidence-based voxel mapping system.

## Key Features
*   **Offline-First:** No cloud dependencies; zero data collected.
*   **Custom Engine (MobileGS):** C++17 native engine for 3D Gaussian Splatting and spatial mapping.
*   **AzNavRail UI:** Thumb-driven navigation for one-handed use in the field.
*   **Advanced Rendering:** Includes LiDAR-based occlusion, realistic light estimation, and Vulkan-ready architecture.
*   **Multi-Lens Support:** Automatically utilizes dual-camera setups for enhanced depth sensing on supported devices.
*   **Teleological Correction:** Automatic map-to-world alignment using OpenCV fingerprinting.

## Architecture
The project is built with a strictly decoupled multi-module architecture:
*   `:app`: Dependency injection (Hilt) and navigation.
*   `:feature:ar`: ARCore integration, camera handling, and sensor fusion.
*   `:feature:editor`: Image manipulation, mesh warp, and layer management.> Task :app:kspDebugKotlin
*   `:core:nativebridge`: JNI interface and native engine management.
*   `:core:cpp`: C++17 engine source code.

## Setup & Building
1.  **Libraries:** Run `./setup_libs.sh` to fetch OpenCV and GLM.
2.  **NDK:** Ensure NDK 25.x or higher is installed.
3.  **Build:** Run `./gradlew assembleDebug`.

## Documentation
- [Architecture Overview](docs/ARCHITECTURE.md)
- [Phase 4 Roadmap](docs/PHASE_4_PLAN.md)
- [Native Engine Details](docs/NATIVE_ENGINE.md)
- [UI/UX Guidelines](docs/UI_UX.md)
