# GraffitiXR

GraffitiXR is an android app for street artists. There are plenty of apps that overlay an image on your camera view so you can virtually trace it, but when I'm painting a mural based on a sketch that I have saved on my phone, using a tripod can really ebb the flow. We're all over the damn place. Me, I put my phone in my pocket. Even the apps that use AR to keep the image steady and in one place can't deal with the abysmal darkness of the pocket.

So I'm making something better by repurposing (what those in-the-know call) the grid method. I was always thinking, "Why can't these specific doodles be saved, like a persistent anchor, so the overlay is always just plain in the right spot?"

So, now, that's what those doodles do.

I had to invent a custom **Persistent Voxel Memory** engine that works on Android without the help of the cloud—because graffiti is, you know, illegal. By building a high-performance 3D map of your surroundings in the background, the phone always knows exactly where it is. Even after tracking loss or a screen-off event, the engine uses OpenCV "fingerprints" to **snap back** and realign your mural to the wall in milliseconds.

And I followed it up with what I call a **Teleological SLAM**—since we know what the result is supposed to look like, I use OpenCV to look for your progress, meaning that the further along you are, the more tightly the overlay sticks to the wall. Without this, you'd cover those marks up with the painting itself, making the app less accurate as you go. That's exactly where other apps like this truly fail.

Just for shirts and goggles, I included the non-AR, image overlay functionality for image tracing, just like you get with those other apps, in case you cray like that. Or if you cray-cray, there's **Mockup mode**. Nab a picture of the wall, then I got some quick tools for a quick mockup. And if you've got nothing to prove, you just want something copied onto paper perfectly, **Trace mode** allows you to use your phone as a lightbox, keeping your screen on with the brightness turned up, locking your image into place and blocking all touches until you're finished.

And then, there's a decent suite of pertinent design tools, with support for multi-layer graphical creation. I could go on, but I feel like I already have.

## Key Features
*   **Offline-First:** Zero cloud dependencies; 100% local processing and zero data collection.
*   **Pocket-Ready:** Built-in relocalization ensures your mural stays "stuck" even after putting the phone in your pocket.
*   **Persistent Voxel Memory:** C++17 native engine using a zero-allocation fixed-size spatial hash table for O(1) discovery speed.
*   **Opaque Surfel Pipeline:** High-performance rendering with hardware Z-buffering, replacing expensive alpha blending.
*   **Stochastic Integration:** Optimized depth integration (2048 random pixels per frame) to reduce CPU overhead by 90%.
*   **Mandatory Dual-Lens:** Automatically leverages hardware stereo depth for rock-solid tracking stability.
*   **AI Glasses Support:** Integrated support for **Meta Ray-Bans** and **Xreal Air/Ultra** via a provider-based abstraction layer.
*   **Co-op Mode:** Robust peer-to-peer AR synchronization for collaborative painting.
*   **AzNavRail UI:** Thumb-driven, one-handed navigation designed for artists holding a spray can.

## Modes
*   **AR Mural:** The core precision instrument for anchoring digital concepts to physical surfaces using confidence-based voxel mapping.
*   **Mockup Mode:** Fast tools for visualizing layers and blend modes on top of static wall photos.
*   **Trace (Lightbox):** Full-brightness surface for copying onto paper with touch-lock and triple-tap exit.
*   **Stencil Tool:** Automated generation of multi-layer printable stencils (1-3 colors) with tiled PDF export.

## Architecture
Strictly decoupled multi-module Clean Architecture:
*   `:app` — Navigation, camera orchestration, and Hilt dependency injection.
*   `:feature:ar` — ARCore session management, `ArRenderer`, and SLAM data processing.
*   `:feature:editor` — Multi-layer image manipulation and GPU-accelerated Liquify.
*   `:core:nativebridge` — Native C++ engine (`MobileGS`), JNI bridge, and relocalization threads.
*   `:android_collaboration_module` — Peer-to-peer networking and project sync.
*   `:opencv` — Static OpenCV SDK for computer vision tasks.
*   `:core:data` / `:core:domain` / `:core:common` — Unified data layer and wearable abstraction.

## Documentation
- [Architecture Overview](docs/ARCHITECTURE.md)
- [Native Engine Details](docs/NATIVE_ENGINE.md)
- [SLAM Setup & Relocalization](docs/SLAM_SETUP.md)
- [Teleological SLAM](docs/TELEOLOGICAL_SLAM.md)
- [Stencil Pipeline](docs/STENCILS.md)
- [Performance Guide](docs/performance.md)
- [Testing Strategy](docs/testing.md)
- [Data Formats](docs/data_formats.md)
- [Contributing](docs/contributing.md)

---
*Documentation updated on 2026-04-26 during multi-glass integration and co-op robustness phase.*
