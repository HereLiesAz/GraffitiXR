# Implementation Plan - Project Structure Refinement

## Phase 1: Analysis & Dependency Graph Verification [checkpoint: 824f873]
- [x] Task: Analyze current Gradle dependency graph to identify violations of the Clean Architecture layering. [de7fc5c]
    - [x] Run `./gradlew :app:dependencies` and visualize the graph.
    - [x] Identify any `feature` -> `feature` dependencies.
    - [x] Identify any circular dependencies involving `core:native`.
- [x] Task: Document the violations found and update `spec.md` if new constraints are discovered. [de7fc5c]
- [x] Task: Conductor - User Manual Verification 'Analysis & Dependency Graph Verification' (Protocol in workflow.md) [824f873]

## Phase 2: Core Native Isolation
- [x] Task: Audit `core:native` for JNI boundary leaks. [824f873]
    - [x] Ensure `CMakeLists.txt` only exposes necessary symbols.
    - [x] Verify Kotlin JNI wrapper classes are the only consumers of the native libraries.
- [x] Task: Refactor any circular dependencies between `core:native` and other `core` modules. [824f873]
    - [x] Move shared data structures to `core:common` or `core:domain` if needed by both native wrappers and other core components.
    - [x] Ensure `core:native` implementation does not depend back on upper-layer Kotlin code (except via defined JNI callbacks).
- [x] Task: Conductor - User Manual Verification 'Core Native Isolation' (Protocol in workflow.md) [824f873]

## Phase 3: Feature Module Standardization & Cleanup
- [x] Task: Decouple any inter-dependent Feature modules. [824f873]
    - [x] Refactor direct calls between `feature:ar`, `feature:editor`, and `feature:dashboard`.
    - [x] Introduce shared interfaces in `core:domain` or `core:common` to handle communication (Dependency Inversion).
- [x] Task: Cleanup AndroidManifest.xml files. [824f873]
    - [x] Remove deprecated `package` attributes from source manifests (use namespace in build.gradle).
    - [x] Fix namespace collisions (e.g., `org.opencv`).
- [x] Task: Conductor - User Manual Verification 'Feature Module Standardization' (Protocol in workflow.md) [824f873]

## Phase 4: Final Verification & Hardening
- [x] Task: Expand Test Suite. [824f873]
    - [x] Increase unit test coverage for `EditorViewModel` and `ArRenderer`.
    - [x] Add basic instrumentation tests for critical flows if feasible.
- [x] Task: Harden Application. [824f873]
    - [x] Review ProGuard/R8 rules.
    - [x] Verify error handling in JNI bridges.
- [x] Task: Perform a clean build of the entire project. [824f873]
    - [x] Run `./gradlew clean assembleDebug`.
- [x] Task: Run unit tests to ensure refactoring didn't break existing logic. [824f873]
    - [x] Run `./gradlew testDebugUnitTest`.
- [x] Task: Conductor - User Manual Verification 'Final Verification' (Protocol in workflow.md) [824f873]
