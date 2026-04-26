# GraffitiXR

GraffitiXR is an Android app for street artists. While many apps overlay images on your camera for tracing, they often fail when you need to move around or put your phone in your pocket. GraffitiXR is designed for the "interrupted" flow of painting—scan your wall, place your mural, and if you need to put your phone away, it will **snap back** and relocalize instantly when you resume.

## The Spatial Memory
We repurpose the "grid method" into a persistent digital anchor. I had to invent a custom mapping engine that works natively on Android without the cloud—because privacy and offline capability are essential for graffiti.

The app uses a **Persistent Voxel Memory** system. By building a high-performance 3D map of your surroundings in the background, the phone always knows exactly where it is. Even after tracking loss or a screen-off event, the engine uses OpenCV "fingerprints" to realign your mural to the wall in milliseconds.

## Key Features
*   **Offline-First:** Zero cloud dependencies; 100% local processing.
*   **Pocket-Ready:** Built-in relocalization ensures your mural stays "stuck" even after putting the phone in your pocket.
*   **Persistent Voxel Memory:** C++17 native engine for high-performance 3D mapping.
*   **Stochastic Integration:** Optimized depth processing that preserves battery while maintaining dense tracking.
*   **Mandatory Dual-Lens:** Automatically forces and rewards hardware stereo depth for rock-solid stability.
*   **AzNavRail UI:** Thumb-driven, one-handed navigation designed for artists holding a spray can.
*   **Teleological Correction:** Automatic drift correction as your painting progresses.

## Modes
*   **AR Mural:** The core precision instrument for anchoring digital concepts to physical surfaces.
*   **Mockup Mode:** Quick tools for visualizing sketches on top of wall photos.
*   **Trace (Lightbox):** Turn your phone into a lightbox for copying onto paper, with touch-lock and full-brightness.
*   **Design Tools:** Pertinent multi-layer graphical creation suite.

## Architecture
Strictly decoupled multi-module Clean Architecture:
*   `:app` — Navigation and camera ownership orchestration.
*   `:feature:ar` — ARCore session, `ArRenderer`, and SLAM data feeding.
*   `:feature:editor` — Image manipulation and GPU-accelerated Liquify.
*   `:core:nativebridge` — The **Persistent Voxel Memory** engine and relocalization threads.
*   `:core:data` / `:core:domain` / `:core:common` — Unified data layer.

## Documentation
- [Architecture Overview](docs/ARCHITECTURE.md)
- [Native Engine Details](docs/NATIVE_ENGINE.md)
- [SLAM Setup & Relocalization](docs/SLAM_SETUP.md)
- [Performance Guide](docs/performance.md)
- [Data Formats](docs/data_formats.md)
- [Contributing](docs/contributing.md)

---
*Documentation updated on 2026-04-24 during Persistent Voxel Memory and Pocket-Ready recovery implementation.*
