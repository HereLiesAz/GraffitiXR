# GraffitiXR

GraffitiXR is an android app for street artists. There are plenty of apps that overlay an image on your camera view so you can virtually trace it, but when I'm painting a mural based on a sketch that I have saved on my phone, using a tripod can really ebb the flow. We're all over the damn place. Me, I put my phone in my pocket. Even the apps that use AR to keep the image steady and in one place can't deal with the abysmal darkness of the pocket.

So I'm making something better by repurposing (what those in-the-know call) the grid method. I was always thinking, "Why can't these specific doodles be saved, like a persistent anchor, so the overlay is always just plain in the right spot?"

So, now, that's what those doodles do.

I had to invent a custom Gaussian Splatting engine that works on Android without the help of the cloud--because graffiti is, you know, illegal.

And I followed it up with what I call a Teleological Slam--since we know what the result is supposed to look like, I use OpenCV to look for your progress, meaning that the further along you are, the more tightly the overlay sticks to the wall. Without this, you'd cover those marks up with the painting itself, making the app less accurate as you go. That's exactly where other apps like this truly fail.

Just for shirts and goggles, I included the non-AR, image overlay functionality for image tracing, just like you get with those other apps, in case you cray like that. Or if you cray-cray, there's Mockup mode. Nab a picture of the wall, then I got some quick tools for a quick mockup. And if you've got nothing to prove, you just want something copied onto paper perfectly, Trace mode allows you to use your phone as a lightbox, keeping your screen on with the brightness turned up, locking your image into place and blocking all touches until you're finished.

And then, there's a decent suite of pertinent design tools, with support for multi-layer graphical creation. I could go on, but I feel like I already have.

**GraffitiXR** is an offline Android app for street artists. It uses AR to project images onto walls using a confidence-based voxel mapping system.

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

## Documentation
- [Architecture Overview](docs/ARCHITECTURE.md)
- [Native Engine Details](docs/NATIVE_ENGINE.md)
- [SLAM Configuration & Tuning](docs/SLAM_SETUP.md)
- [3D Pipeline Specification](docs/PIPELINE_3D.md)
- [Testing Strategy](docs/testing.md)
- [Screen & Mode Reference](docs/screens.md)
  


---
*Documentation updated on 2026-03-17 during website redesign and Stencil Mode integration phase.*
