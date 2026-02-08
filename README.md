# GraffitiXR

**GraffitiXR** is a Local-First, Offline-Capable Android application for street artists. It leverages ARCore and a custom Gaussian Splatting engine (MobileGS) to provide robust overlay and tracing tools for large-scale murals.

## 📚 Documentation
This repository is exhaustively documented. Please refer to the `docs/` directory for detailed information.

*   **[Architecture](./docs/architecture.md)**: System design, modules, and rendering pipeline.
*   **[Native Engine](./docs/NATIVE_ENGINE.md)**: Deep dive into the custom C++ SLAM engine.
*   **[API Reference](./docs/API_REFERENCE.md)**: Guide to core modules and data structures.
*   **[File Descriptions](./docs/file_descriptions.md)**: Index of all source files.
*   **[Contributing](./docs/contributing.md)**: Guidelines for developers.

## 🛠️ Build Instructions
1.  **Setup Dependencies**: Run `./setup_libs.sh` to fetch OpenCV and other binaries.
2.  **Build**: Open in Android Studio or run `./gradlew assembleDebug`.

## Features
*   **AR Mode**: Project images onto walls with persistent locking via Gaussian Splats. Includes auto-aging for map stability and manual rescan.
*   **Trace Mode**: Lightbox functionality for tracing on paper.
*   **Mockup Mode**: Visualize art on a static photo of a wall.
*   **Editor**: Adjust transparency, color balance, and layers.

---
*Built with spray paint and code in New Orleans.*
