# Technology Stack

## Core Languages
- **Kotlin:** Primary language for Android application logic, Jetpack Compose UI, and data management.
- **C++17:** Native engine for performance-critical tasks including voxel mapping, SLAM, and Gaussian Splat rendering.
- **JNI (Java Native Interface):** Facilitates high-performance communication between Kotlin and the C++ native core.

## Mobile Platform
- **Android:** Targeting modern devices (Min SDK 24, Target SDK 35).
- **Architecture:** Multi-module architecture (Clean Architecture principles) for strict separation of concerns and build efficiency.

## UI/UX Framework
- **Jetpack Compose:** Declarative UI toolkit for building modern, responsive Android interfaces.
- **AzNavRail:** Specialized thumb-driven navigation system designed for one-handed operation.
- **Material Design 3:** Adherence to Material 3 design principles for a polished, modern look.

## AR & Computer Vision
- **ARCore (1.52.0):** Google's platform for building augmented reality experiences, providing pose tracking and environmental understanding.
- **OpenCV (4.13.0):** Comprehensive computer vision library used for image processing and fingerprinting.
- **LiteRT (2.1.1):** AI Edge runtime for specialized machine learning tasks (e.g., subject segmentation).

## Native Engine (MobileGS)
- **Voxel Mapping:** Confidence-based voxel system for high-precision environment reconstruction.
- **Gaussian Splatting:** Custom rendering pipeline for high-fidelity 3D overlays.
- **Math Libraries:** GLM (OpenGL Mathematics) for efficient linear algebra in C++.

## Infrastructure & Tooling
- **Gradle (Kotlin DSL):** Advanced build system with centralized dependency management via Version Catalogs (`libs.versions.toml`).
- **Hilt (Dagger):** Dependency injection framework for modularity and testability.
- **State Management:** Kotlin StateFlow and ViewModel for reactive, lifecycle-aware data handling.

## Key Libraries
- **CameraX:** Robust camera API for image capture and analysis.
- **Coil:** Image loading library for Android, backed by Kotlin Coroutines.
- **Timber:** Extensible logging utility for Android.
- **Gson/Kotlinx Serialization:** JSON parsing and object serialization.
