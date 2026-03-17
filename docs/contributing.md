# Contributing to GraffitiXR

We welcome pull requests, but this is a specialized tool with strict architectural constraints. Read this before writing code.

## ⛔ The Three Hard Rules

1.  **NO CLOUD FEATURES.**
    * Do not add "Social Sharing" features that upload data.
    * This tool is designed for off-grid usage. PRs adding network dependencies will be closed immediately.

2.  **PRESERVE THE RAIL.**
    * Do not add a `Toolbar`, `FloatingActionButton`, or `BottomNavigationView` or make any attempt to bypass the demands of the AzNavRail.
    * All navigation must go through `AzNavRail`. If a feature doesn't fit on the rail, you need to ask for that feature in a custom implementation.
    * All UI elements must be handled by AzHostActivityLayout.
    * Always check for the latest AzNavRail documentation. https://github.com/HereLiesAz/AzNavRail
   
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




---
*Documentation updated on 2026-03-17 during website redesign and Stencil Mode integration phase.*
