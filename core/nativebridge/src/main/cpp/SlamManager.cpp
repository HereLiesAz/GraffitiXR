#include <jni.h>
#include <android/native_window_jni.h>
#include <android/asset_manager_jni.h>
#include <android/bitmap.h>
#include "VulkanBackend.h"
#include "MobileGS.h" // Your Gaussian Splatting Engine

static VulkanBackend* gVulkanBackend = nullptr;
static MobileGS* gSlamEngine = nullptr;

extern "C" {

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_initialize(JNIEnv* env, jobject thiz) {
    if (!gSlamEngine) gSlamEngine = new MobileGS();
    gSlamEngine->init();
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_ensureInitialized(JNIEnv* env, jobject thiz) {
    if (!gSlamEngine) {
        gSlamEngine = new MobileGS();
        gSlamEngine->init();
    }
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_initVulkan(JNIEnv* env, jobject thiz, jobject surface, jobject assetManager, jint width, jint height) {
    if (!gVulkanBackend) gVulkanBackend = new VulkanBackend();

    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    AAssetManager* mgr = AAssetManager_fromJava(env, assetManager);

    gVulkanBackend->initialize(window, mgr);
    gVulkanBackend->resize(width, height);
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_draw(JNIEnv* env, jobject thiz) {
    if (gVulkanBackend && gSlamEngine) {
        std::lock_guard<std::mutex> lock(gSlamEngine->getMutex());
        gVulkanBackend->renderFrame(gSlamEngine->getSplats());
    }
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_updateCamera(JNIEnv* env, jobject thiz, jfloatArray viewMatrix, jfloatArray projectionMatrix) {
    if (!gSlamEngine && !gVulkanBackend) return;

    jfloat* view = env->GetFloatArrayElements(viewMatrix, nullptr);
    jfloat* proj = env->GetFloatArrayElements(projectionMatrix, nullptr);

    if (gSlamEngine) gSlamEngine->updateCamera(view, proj);
    if (gVulkanBackend) gVulkanBackend->updateCamera(view, proj);

    env->ReleaseFloatArrayElements(viewMatrix, view, JNI_ABORT);
    env->ReleaseFloatArrayElements(projectionMatrix, proj, JNI_ABORT);
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_resizeVulkan(JNIEnv* env, jobject thiz, jint width, jint height) {
    if (gVulkanBackend) {
        gVulkanBackend->resize(width, height);
    }
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_destroy(JNIEnv* env, jobject thiz) {
    if (gSlamEngine) {
        delete gSlamEngine;
        gSlamEngine = nullptr;
    }
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_destroyVulkan(JNIEnv* env, jobject thiz) {
    if (gVulkanBackend) {
        delete gVulkanBackend;
        gVulkanBackend = nullptr;
    }
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_applyLut(JNIEnv* env, jobject thiz, jobject bitmap, jintArray lutArray) {
    AndroidBitmapInfo info;
    void* pixels;
    AndroidBitmap_getInfo(env, bitmap, &info);
    AndroidBitmap_lockPixels(env, bitmap, &pixels);

    jint* lut = env->GetIntArrayElements(lutArray, nullptr);

    // Deconstruct: Apply 3D LUT to the bitmap pixels
    // ... Processing Logic ...

    env->ReleaseIntArrayElements(lutArray, lut, JNI_ABORT);
    AndroidBitmap_unlockPixels(env, bitmap);
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_processLiquify(JNIEnv* env, jobject thiz, jobject bitmap, jfloatArray meshData) {
    // Implement mesh-based deformation on the raw pixel buffer
}

// Stub implementations for other declared native methods to prevent UnsatisfiedLinkError
JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_createOnGlThread(JNIEnv* env, jobject thiz) {}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_resetGLState(JNIEnv* env, jobject thiz) {}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_setVisualizationMode(JNIEnv* env, jobject thiz, jint mode) {}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_onSurfaceChanged(JNIEnv* env, jobject thiz, jint width, jint height) {}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_setBitmap(JNIEnv* env, jobject thiz, jobject bitmap) {}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_updateLight(JNIEnv* env, jobject thiz, jfloat intensity) {}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_feedMonocularData(JNIEnv* env, jobject thiz, jobject data, jint width, jint height) {
    // Forward monocular data to gSlamEngine logic if implemented in MobileGS
    // For now, this is a placeholder stub as per request to focus on Vulkan backend wiring
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_feedStereoData(JNIEnv* env, jobject thiz, jbyteArray left, jbyteArray right, jint width, jint height) {}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_feedLocationData(JNIEnv* env, jobject thiz, jdouble latitude, jdouble longitude, jdouble altitude) {}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_processTeleologicalFrame(JNIEnv* env, jobject thiz, jobject buffer, jlong timestamp) {}

JNIEXPORT jboolean JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_saveKeyframe(JNIEnv* env, jobject thiz, jlong timestamp) { return JNI_FALSE; }

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_toggleFlashlight(JNIEnv* env, jobject thiz, jboolean enabled) {}

}
