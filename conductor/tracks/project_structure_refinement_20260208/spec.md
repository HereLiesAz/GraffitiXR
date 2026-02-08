# Track Specification: Project Structure Refinement

## Goal
Standardize module boundaries and resolve any circular dependencies, particularly in the native-to-Kotlin bridge, to ensure a stable foundation for future AR feature development.

## Context
GraffitiXR follows a multi-module architecture:
- `app`: Application entry point.
- `core`: Shared logic (`common`, `data`, `design`, `domain`, `native`).
- `feature`: Independent features (`ar`, `editor`, `dashboard`).

The project uses a mix of Kotlin and C++ (via `core:native`). Strict separation of concerns is required to prevent "spaghetti code" and build failures.

## Requirements
1.  **Strict Module Layering:**
    -   `feature` modules must NOT depend on other `feature` modules.
    -   `feature` modules depend on `core` modules.
    -   `app` depends on `feature` and `core`.
2.  **Native Interface Isolation:**
    -   All JNI (Java Native Interface) definitions must reside solely within `core:native`.
    -   No C++ headers or implementation details should leak into Kotlin layers except via strict JNI wrappers.
    -   Circular dependencies between `core:native` and other core modules must be eliminated.
3.  **Build Health:**
    -   The project must compile without dependency cycle warnings.
    -   `./gradlew assembleDebug` must succeed.

## Non-Goals
-   Refactoring the entire C++ engine logic (only the interface boundary).
-   Adding new AR features.

## Analysis Findings
-   **Dependency Graph:** Valid layering observed.
    -   `feature` modules do NOT depend on other `feature` modules.
    -   `core:native` depends on `core:domain` and `core:common`.
    -   `core:domain` and `core:common` do NOT depend on `core:native`.
    -   No Gradle-level dependency cycles found.
-   **Build Status:** `dependencies` task reported failures for some modules (e.g., `core:native` FAILED). This warrants further investigation into the `externalNativeBuild` configuration.
