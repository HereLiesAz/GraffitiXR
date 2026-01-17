# Contributing to GraffitiXR

We welcome pull requests, but this is a specialized tool with strict architectural constraints. Read this before writing code.

## ⛔ The Three Hard Rules

1.  **NO CLOUD FEATURES.**
    * Do not add Firebase, Analytics, Crashlytics, or any library that "phones home."
    * Do not add "Social Sharing" features that upload data.
    * This tool is designed for off-grid usage. PRs adding network dependencies will be closed immediately.

2.  **PRESERVE THE RAIL.**
    * Do not add a `Toolbar`, `FloatingActionButton`, or `BottomNavigationView`.
    * All navigation must go through `AzNavRail`. If a feature doesn't fit on the rail, it's too complex for this app.

3.  **NATIVE SAFETY.**
    * The C++ layer (`MobileGS.cpp`) manages its own memory.
    * If you touch the JNI bridge, you must verify that `delete` is called on the native side when the Kotlin `ViewModel` clears. Memory leaks here will crash the OS process.

## 🔬 Code Style

* **Kotlin:** Follow the official Android Kotlin Style Guide. Use trailing commas.
* **C++:** Google C++ Style Guide.
    * Use `std::shared_ptr` where possible, but raw pointers are used in the hot render loop for performance.
    * **Comments:** Comment the *why*, not the *what*.

## 🧪 Testing

* **Unit Tests:** Required for all ViewModel logic (`src/test`).
* **UI Tests:** We use simple Compose rule tests.
* **Native Tests:** There are currently no unit tests for the C++ layer (it's hard). Test visually on a device with `Debug` build variant enabled to see the "Splat Debug" colors.