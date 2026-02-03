# GraffitiXR Project Context

## Project Overview
**GraffitiXR** is a local-first, offline-capable Android application designed for street artists. It utilizes Augmented Reality (AR) and a custom C++ engine to assist in mural painting by projecting sketches onto walls using a confidence-based voxel mapping system.

### Key Features
*   **Local-First & Offline:** No cloud dependencies; all processing and storage are local.
*   **Custom Engine (MobileGS):** C++17 native engine for confidence-based voxel mapping and "Gaussian Splat" rendering.
*   **AzNavRail UI:** Thumb-driven UI tailored for one-handed use.
*   **Modes:** AR Projection, Mockup, Trace (Lightbox), and Overlay.
*   **Project Container (.gxr):** Zipped container holding maps, fingerprints, and art assets.

## Architecture
The project follows a strict **Multi-Module** architecture to enforce separation of concerns and improve build performance.

### Module Structure
*   **`app`**: Application entry point, DI wiring, and Navigation Graph.
*   **Core Layer** (Platform/Feature agnostic):
    *   `core:common`: Utilities, extensions, math helpers.
    *   `core:domain`: Pure data models, repository interfaces.
    *   `core:data`: Repository implementations, I/O, database.
    *   `core:design`: Design system, generic UI components, theme.
    *   `core:cpp` (Native): C++ code, JNI wrappers, CMake config.
*   **Feature Layer** (Feature independent):
    *   `feature:ar`: AR view, camera, renderer, mapping logic.
    *   `feature:editor`: Image manipulation, Mockup/Trace/Overlay screens.
    *   `feature:dashboard`: Project library, settings, onboarding.

### Tech Stack
*   **Languages:** Kotlin (UI/Logic), C++17 (Native Core/Rendering).
*   **UI Framework:** Jetpack Compose + [AzNavRail](https://github.com/HereLiesAZ/AzNavRail).
*   **Build System:** Gradle (Kotlin DSL) with Version Catalogs (`libs.versions.toml`).
*   **AR/CV:** ARCore (Depth/Pose), OpenCV 4.x (Fingerprinting), OpenGL ES 3.0 (Rendering).

## Building and Running

### Prerequisites
*   **OS:** Linux, macOS, or Windows.
*   **Dependencies:** Requires fetching large libraries (OpenCV, etc.) not stored in the main tree.

### Setup
Run the setup script to download and configure native dependencies:
```bash
# Linux / macOS
chmod +x setup_libs.sh
./setup_libs.sh

# Windows
./setup_libs.ps1
```

### Build Commands
*   **Build Debug APK:**
    ```bash
    ./gradlew assembleDebug
    ```
*   **Run Tests:**
    ```bash
    ./gradlew testDebugUnitTest
    ```

## Development Conventions

### Coding Style
*   **Kotlin:** Follows standard Kotlin conventions. Use `val` over `var` where possible.
*   **Compose:** Use functional components. Prefer `StateFlow` for state management in ViewModels.
*   **JNI:** Strictly use `ByteBuffer` for passing memory between Kotlin and C++. Avoid passing raw pointers directly.

### Refactoring & Architecture
*   **Namespace Mapping:** Modules map to flattened packages (e.g., `:core:common` -> `com.hereliesaz.graffitixr.common`).
*   **Dependency Rule:** Feature modules **must not** depend on other feature modules. They communicate via `app` or `core` interfaces.
*   **Native Code:** All C++ logic resides in `core:cpp` (or `core:native` as referenced in strategy docs) and exposes functionality via JNI.

## Key Documentation
*   `README.md`: General overview and philosophy.
*   `docs/architecture.md`: Detailed system diagram and rendering pipeline.
*   `REFACTORING_STRATEGY.md`: Module boundaries and migration plan.
*   `gradle/libs.versions.toml`: Dependency version management.
