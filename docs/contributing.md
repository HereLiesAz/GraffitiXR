# Contributing to GraffitiXR

We welcome pull requests, but this is a specialized tool with strict architectural constraints. Read this before writing code.

## ⛔ The Three Hard Rules

1.  **NO CLOUD FEATURES.**
    * Do not add "Social Sharing" features that upload data.
    * This tool is designed for off-grid usage. PRs adding network dependencies will be closed immediately.

2.  **PRESERVE THE RAIL.**
    * All navigation must go through `AzNavRail`. If a feature doesn't fit on the rail, you need to ask for that feature in a custom implementation.
    * All UI elements must be handled by `AzHostActivityLayout`.

3.  **NATIVE SAFETY.**
    * The C++ layer manages its own memory.
    * If you touch the JNI bridge, you must verify that `delete` is called on the native side when the Kotlin `ViewModel` clears. Memory leaks here will crash the OS process.

## 🔬 Code Style

* **Kotlin:** Follow the official Android Kotlin Style Guide. Use trailing commas.
* **Linting:** We use **Detekt** for static analysis. Run `./gradlew detekt` before submitting.
* **C++:** Google C++ Style Guide.
    * Use `std::shared_ptr` where possible, but raw pointers are used in the hot render loop for performance.
    * **Comments:** Comment the *why*, not the *what*.

## 🧪 Testing

* **Unit Tests:** Required for all ViewModel logic (`src/test`). See [`testing.md`](testing.md) for
  the current file inventory and mock patterns.
* **UI Tests:** There are no instrumented (`src/androidTest/`) tests in this repository yet — see
  `testing.md` §3. Verify UI changes by running the app.
* **Native changes:** There is no automated C++ test runner and no on-device debug visualization to
  check against — see `testing.md` §2. Verify with the "Wall Test" (`testing.md` §4) and, for JNI
  signature changes, `./gradlew :core:nativebridge:testDebugUnitTest` (`NativeMethodAritySignatureTest`
  catches a drifted parameter count, not a drifted type).

---
*Documentation updated on 2026-09-04: removed the Voxel Memory visualization and "Lens Mode" hardware-stereo verification steps (neither exists — see `NATIVE_ENGINE.md`), pointed at the real test tiers in `testing.md`. Prior update: 2026-04-24, Persistent Voxel Memory and Pocket-Ready recovery implementation — the subsystem that update described was later deleted.*
