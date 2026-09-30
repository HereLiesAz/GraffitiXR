package com.hereliesaz.graffitixr.nativebridge

import com.hereliesaz.graffitixr.common.util.NativeLibLoader

/**
 * JNI bridge to the forked artoolkitX KPM (keypoint matching) tracker, vendored as native source
 * under `third_party/artoolkitx` and built into the `graffitixr` library.
 *
 * This is the non-ARCore tracking base. The plan on top of it: build a metric planar KPM page from
 * each captured view ([nativeKpmGenPageFromLuma], later), tile pages across the wall plane into one
 * mosaic ("fingerprint"), match live frames ([nativeKpmMatch], later) to recover 6-DoF pose including
 * toward/away translation from the planar homography, and grow the mosaic adaptively for overpaint
 * survival.
 *
 * PHASE 1 (this file): a link smoke test only. [smokeTest] creates and destroys a KPM handle to prove
 * the forked KPM actually compiles and links in the NDK build on-device. When the artoolkitX submodule
 * isn't checked out (e.g. CI), the native side is compiled without KPM and every call returns
 * false/no-op — so the app and CI build unchanged.
 */
object KpmBridge {
    init {
        NativeLibLoader.loadAll()
    }

    /** True when the native library was built with the forked KPM present (submodule checked out). */
    fun isAvailable(): Boolean = runCatching { nativeKpmAvailable() }.getOrDefault(false)

    /**
     * Create a KPM homography handle for [width]x[height] frames and immediately destroy it. Returns
     * true if the handle was created — i.e. KPM is linked and callable. No camera, no matching.
     */
    fun smokeTest(width: Int, height: Int): Boolean = nativeKpmSmokeTest(width, height)

    private external fun nativeKpmAvailable(): Boolean
    private external fun nativeKpmSmokeTest(width: Int, height: Int): Boolean
}
