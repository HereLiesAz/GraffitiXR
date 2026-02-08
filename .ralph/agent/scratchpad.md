## Analysis of Core Native Isolation

I found significant mismatches between the Kotlin JNI wrappers and the C++ implementation in `core:native`.

### Findings
1. **Library Name Mismatch:** `SlamManager.kt` attempts to load `mobile_gs_jni`, but `CMakeLists.txt` builds `graffitixr`.
2. **Function Signature Mismatch:** `GraffitiJNI.cpp` expects a `jlong handle` for stateful operations, but `SlamManager.kt` does not maintain a native handle and uses different method names (`init` vs `initNativeJni`, etc.).
3. **Namespace Inconsistency:** The strategy dictates `com.hereliesaz.graffitixr.native`, but the code uses `com.hereliesaz.graffitixr.natives`.
4. **Unused Files:** `app/CMakeLists.txt` exists but appears unused as there is no `src/main/cpp` in `app`.

### Plan
1. **Standardize Naming:** Rename `com.hereliesaz.graffitixr.natives` to `com.hereliesaz.graffitixr.native`.
2. **Sync JNI Interface:** Align `SlamManager.kt` and `GraffitiJNI.cpp`. I will adopt the handle-based approach in `GraffitiJNI.cpp` as it's better for avoiding global state in native code.
3. **Fix Library Loading:** Change `System.loadLibrary` to use the name defined in `CMakeLists.txt`.
4. **Cleanup:** Remove ghost `app/CMakeLists.txt`.
