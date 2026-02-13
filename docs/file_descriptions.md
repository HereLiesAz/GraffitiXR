# File Registry

This document lists all files in the repository and their purposes.

## Root
*   `README.md`: Project overview and setup instructions.
*   `ARCHITECTURAL_ANALYSIS.md`: Post-refactoring analysis of the project structure.
*   `build.gradle.kts`: Root build configuration.
*   `settings.gradle.kts`: Module inclusion settings.
*   `gradle.properties`: Gradle properties.
*   `setup_libs.sh`: Script to download native libraries.
*   `setup_ndk.sh`: Script to setup Android NDK.

## Application (`:app`)
*   `src/main/java/com/hereliesaz/graffitixr/GraffitiApplication.kt`: Application class, Hilt entry point.
*   `src/main/java/com/hereliesaz/graffitixr/MainActivity.kt`: Main Activity, sets up the NavGraph.
*   `src/main/java/com/hereliesaz/graffitixr/MainViewModel.kt`: Global app state (touch lock, navigation).
*   `src/main/java/com/hereliesaz/graffitixr/MainScreen.kt`: The main scaffolding (AzNavRail) and navigation host.
*   `src/main/java/com/hereliesaz/graffitixr/CrashHandler.kt`: Global exception handler.

## Core Modules

### `:core:common`
*   `src/main/java/com/hereliesaz/graffitixr/common/LocationTracker.kt`: GPS tracking utility.
*   `src/main/java/com/hereliesaz/graffitixr/common/DispatcherProvider.kt`: Coroutine dispatcher injection helper.
*   `src/main/java/com/hereliesaz/graffitixr/common/model/UiState.kt`: Shared UI state models.
*   `src/main/java/com/hereliesaz/graffitixr/common/util/ImageProcessor.kt`: OpenCV image processing wrappers.

### `:core:domain`
*   `src/main/java/com/hereliesaz/graffitixr/domain/repository/ProjectRepository.kt`: Interface for project data access.
*   `src/main/java/com/hereliesaz/graffitixr/domain/repository/SettingsRepository.kt`: Interface for settings.

### `:core:data`
*   `src/main/java/com/hereliesaz/graffitixr/data/repository/ProjectRepositoryImpl.kt`: Implementation of ProjectRepository.
*   `src/main/java/com/hereliesaz/graffitixr/data/ProjectManager.kt`: File system I/O for projects.

### `:core:design`
*   `src/main/java/com/hereliesaz/graffitixr/design/theme/Theme.kt`: Compose theme definition.
*   `src/main/java/com/hereliesaz/graffitixr/design/components/`: Reusable UI components (Knob, InfoDialog, etc.).

### `:core:native`
*   `src/main/cpp/MobileGS.cpp`: Native engine implementation (C++).
*   `src/main/cpp/GraffitiJNI.cpp`: JNI bridge.

## Feature Modules

### `:feature:ar`
*   `src/main/java/com/hereliesaz/graffitixr/feature/ar/ArView.kt`: AR camera view composable.
*   `src/main/java/com/hereliesaz/graffitixr/feature/ar/ArViewModel.kt`: AR state management.
*   `src/main/java/com/hereliesaz/graffitixr/feature/ar/rendering/ArRenderer.kt`: OpenGL renderer for AR.
*   `src/main/java/com/hereliesaz/graffitixr/feature/ar/TargetCreationFlow.kt`: UI flow for creating new AR targets.

### `:feature:editor`
*   `src/main/java/com/hereliesaz/graffitixr/feature/editor/EditorViewModel.kt`: Editor state management.
*   `src/main/java/com/hereliesaz/graffitixr/feature/editor/MockupScreen.kt`: 2D/3D mockup editing screen.

### `:feature:dashboard`
*   `src/main/java/com/hereliesaz/graffitixr/feature/dashboard/DashboardViewModel.kt`: Dashboard state management.
*   `src/main/java/com/hereliesaz/graffitixr/feature/dashboard/ProjectLibraryScreen.kt`: Project list UI.

## Documentation (`docs/`)
*   `ARCHITECTURE.md`: High-level architecture guide.
*   `TODO.md`: Roadmap and backlog.
*   And various other specific documentation files.
