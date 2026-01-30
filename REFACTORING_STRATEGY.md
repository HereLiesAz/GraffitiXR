# Refactoring Strategy: Modularization

## Objective
Transition the monolithic codebase into a strict multi-module architecture to enforce separation of concerns, improve build times, and prevent circular dependencies.

## Module Architecture

### 1. Core Layer (No Feature Dependencies)
* **`core:common`**
    * **Scope:** Universal utility classes, Kotlin extensions, Math helpers, and Dispatcher providers.
    * **Dependencies:** None.
* **`core:design`**
    * **Scope:** Design system, Theme, Typography, Colors, and shared generic UI components (Knobs, Indicators).
    * **Dependencies:** `core:common`, `androidx.compose`.
* **`core:domain`**
    * **Scope:** Pure data classes, repository interfaces, and domain models.
    * **Dependencies:** `core:common`.
    * **Note:** Must remain platform-agnostic where possible.
* **`core:data`**
    * **Scope:** Repository implementations, File I/O, Serialization, Database access.
    * **Dependencies:** `core:domain`, `core:common`.
* **`core:native`**
    * **Scope:** C++ native code (MobileGS), JNI wrappers, CMake configurations.
    * **Dependencies:** `core:domain`, `OpenCV` (local).

### 2. Feature Layer (Feature Independent)
Feature modules cannot depend on other feature modules. They communicate only via `app` injection or `core` interfaces.

* **`feature:ar`**
    * **Scope:** AR view, Camera handling, Renderer implementation, Target creation.
    * **Dependencies:** `core:native`, `core:design`, `core:domain`.
* **`feature:editor`**
    * **Scope:** Image manipulation, Mockup screen, Overlay screen, Trace screen, OpenCV image processing.
    * **Dependencies:** `core:design`, `core:domain`, `OpenCV`.
* **`feature:dashboard`**
    * **Scope:** Project library, Settings, Onboarding flows.
    * **Dependencies:** `core:design`, `core:data`.

### 3. App Layer (Integration)
* **`app`**
    * **Scope:** `MainActivity`, Dependency Injection wiring, Navigation Graph.
    * **Dependencies:** All `core` and `feature` modules.

## Execution Plan

### Phase 1: Core Extraction
1.  Create `core:common` and move general utilities.
2.  Create `core:domain` and move data models.
3.  Create `core:design` and move theme/UI primitives.

### Phase 2: Logic Extraction
4.  Create `core:native` and migrate C++/JNI code.
5.  Create `core:data` and migrate `ProjectManager` and persistence logic.

### Phase 3: Feature Migration
6.  Create `feature:editor` (Mockup, Overlay, Trace).
7.  Create `feature:ar` (AR Rendering, Mapping).
8.  Create `feature:dashboard` (Library, Settings).

### Phase 4: Cleanup
9.  Clean up `app` module to contain only entry points and navigation.
10. Finalize Gradle dependency graph.

# REFACTORING STRATEGY: THE FLATTENING

## 1. NAMESPACE & DIRECTORY DIRECTIVE
We reject deep nesting. We map modules to flattened packages to minimize path length and cognitive load. The physical directory structure MUST mirror the Gradle module definition.

### Directory Mapping Table
| Gradle Module | Gradle Namespace | Physical Path (Root Relative) |
| :--- | :--- | :--- |
| **`:core:common`** | `com.hereliesaz.graffitixr.common` | `core/common/src/main/java/com/hereliesaz/graffitixr/common/` |
| **`:core:domain`** | `com.hereliesaz.graffitixr.domain` | `core/domain/src/main/java/com/hereliesaz/graffitixr/domain/` |
| **`:core:native`** | `com.hereliesaz.graffitixr.native` | `core/native/src/main/java/com/hereliesaz/graffitixr/native/` |
| **`:core:design`** | `com.hereliesaz.graffitixr.design` | `core/design/src/main/java/com/hereliesaz/graffitixr/design/` |
| **`:core:data`** | `com.hereliesaz.graffitixr.data` | `core/data/src/main/java/com/hereliesaz/graffitixr/data/` |
| **`:feature:ar`** | `com.hereliesaz.graffitixr.feature.ar` | `feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/` |
| **`:feature:editor`** | `com.hereliesaz.graffitixr.feature.editor` | `feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/` |
| **`:feature:dashboard`** | `com.hereliesaz.graffitixr.feature.dashboard` | `feature/dashboard/src/main/java/com/hereliesaz/graffitixr/feature/dashboard/` |
| **`:app`** | `com.hereliesaz.graffitixr` | `app/src/main/java/com/hereliesaz/graffitixr/` |

## 2. JNI PROTOCOL
* **Safety:** Do not pass raw memory addresses from Kotlin. Pass `ByteBuffer` objects.
* **Conversion:** C++ extracts the address via `GetDirectBufferAddress`.
* **Thread Safety:** The Native Engine must handle its own locking if accessed from multiple threads.

## 3. EXECUTION SCRIPT
The `refactor.sh` script is the single source of truth for file migration. If it's not in the script, it doesn't move.