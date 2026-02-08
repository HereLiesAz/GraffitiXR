# Implementation Plan - Project Structure Refinement

## Phase 1: Analysis & Dependency Graph Verification
- [x] Task: Analyze current Gradle dependency graph to identify violations of the Clean Architecture layering. [de7fc5c]
    - [x] Run `./gradlew :app:dependencies` and visualize the graph.
    - [x] Identify any `feature` -> `feature` dependencies.
    - [x] Identify any circular dependencies involving `core:native`.
- [ ] Task: Document the violations found and update `spec.md` if new constraints are discovered.
- [ ] Task: Conductor - User Manual Verification 'Analysis & Dependency Graph Verification' (Protocol in workflow.md)

## Phase 2: Core Native Isolation
- [ ] Task: Audit `core:native` for JNI boundary leaks.
    - [ ] Ensure `CMakeLists.txt` only exposes necessary symbols.
    - [ ] Verify Kotlin JNI wrapper classes are the only consumers of the native libraries.
- [ ] Task: Refactor any circular dependencies between `core:native` and other `core` modules.
    - [ ] Move shared data structures to `core:common` or `core:domain` if needed by both native wrappers and other core components.
    - [ ] Ensure `core:native` implementation does not depend back on upper-layer Kotlin code (except via defined JNI callbacks).
- [ ] Task: Conductor - User Manual Verification 'Core Native Isolation' (Protocol in workflow.md)

## Phase 3: Feature Module Standardization
- [ ] Task: Decouple any inter-dependent Feature modules.
    - [ ] Refactor direct calls between `feature:ar`, `feature:editor`, and `feature:dashboard`.
    - [ ] Introduce shared interfaces in `core:domain` or `core:common` to handle communication (Dependency Inversion).
- [ ] Task: Conductor - User Manual Verification 'Feature Module Standardization' (Protocol in workflow.md)

## Phase 4: Final Verification
- [ ] Task: Perform a clean build of the entire project.
    - [ ] Run `./gradlew clean assembleDebug`.
- [ ] Task: Run unit tests to ensure refactoring didn't break existing logic.
    - [ ] Run `./gradlew testDebugUnitTest`.
- [ ] Task: Conductor - User Manual Verification 'Final Verification' (Protocol in workflow.md)
