# API Reference

## Core Modules
* [Architecture Overview](ARCHITECTURE.md) — module layout, `:core:common` (shared models),
  `:core:domain` (repository interfaces), `:core:data`, `:core:design`.
* [Native Engine](NATIVE_ENGINE.md) — `:core:nativebridge`, the C++17 `MobileGS` relocalization
  engine and its JNI boundary.
* [File Registry](file_descriptions.md) — key files per module.

(Neither `core/common/README.md` nor `core/native/README.md` exist — there is no `:core:native`
module; the native code lives in `:core:nativebridge`, described in `NATIVE_ENGINE.md` above.)

## Utilities
* **`ImageUtils`** (`com.hereliesaz.graffitixr.common.util.ImageUtils`, `:core:common`) — bitmap
  loading/decoding used by the editor and AR pickers.
* **`YuvConverter`** (`com.hereliesaz.graffitixr.nativebridge.YuvConverter`, `:core:nativebridge`) —
  direct native YUV→RGBA JNI binding for AR target capture. (There is no `ImageProcessor` class with
  Canny edge detection or perspective unwarping — that description did not survive the current code;
  outline extraction is `EditorViewModel.onToggleOutline` via `SketchProcessor`, an OpenCV-backed
  Kotlin utility, not a dedicated `ImageProcessor`.)

---
*Documentation updated on 2026-09-04: replaced two dangling links to nonexistent module READMEs with
links to the docs that actually describe those modules, and corrected the `ImageProcessor`/Canny/
perspective-unwarp claim against current source. Prior update: 2026-03-17, website redesign and
Stencil generation integration phase.*
