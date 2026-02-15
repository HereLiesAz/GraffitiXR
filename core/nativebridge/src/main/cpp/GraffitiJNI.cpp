#include <jni.h>
#include <string>
#include "MobileGS.h"
#include "VulkanBackend.h"
#include <android/log.h>
#include <android/native_window_jni.h>
#include <android/asset_manager_jni.h>

#define TAG "GraffitiJNI"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

extern "C" {

// LIFECYCLE
JNIEXPORT jlong JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_create(JNIEnv *env, jobject thiz) {
    auto *engine = new MobileGS();
    return reinterpret_cast<jlong>(engine);
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_destroyJni(JNIEnv *env, jobject thiz, jlong handle) {
    if (handle != 0) {
        auto *engine = reinterpret_cast<MobileGS *>(handle);
        delete engine;
    }
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_initializeJni(JNIEnv *env, jobject thiz, jlong handle) {
    if (handle != 0) {
        auto *engine = reinterpret_cast<MobileGS *>(handle);
        engine->initialize();
    }
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_resetGLStateJni(JNIEnv *env, jobject thiz, jlong handle) {
    // Stub
}

// RENDERING
JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_onSurfaceChangedJni(JNIEnv *env, jobject thiz, jlong handle, jint width, jint height) {
    if (handle != 0) {
        auto *engine = reinterpret_cast<MobileGS *>(handle);
        engine->onSurfaceChanged(width, height);
    }
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_drawJni(JNIEnv *env, jobject thiz, jlong handle) {
    if (handle != 0) {
        auto *engine = reinterpret_cast<MobileGS *>(handle);
        engine->draw();
    }
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_updateCameraJni(JNIEnv *env, jobject thiz, jlong handle, jfloatArray view, jfloatArray proj) {
    if (handle != 0) {
        auto *engine = reinterpret_cast<MobileGS *>(handle);
        jfloat *v = env->GetFloatArrayElements(view, nullptr);
        jfloat *p = env->GetFloatArrayElements(proj, nullptr);

        engine->updateCamera(v, p);

        env->ReleaseFloatArrayElements(view, v, 0);
        env->ReleaseFloatArrayElements(proj, p, 0);
    }
}

// AR STUBS
JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_updateLightJni(JNIEnv *env, jobject thiz, jlong handle, jfloat intensity) {}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_feedDepthDataJni(JNIEnv *env, jobject thiz, jlong handle, jobject image) {}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_updateMeshJni(JNIEnv *env, jobject thiz, jlong handle, jfloatArray vertices) {}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_alignMapJni(JNIEnv *env, jobject thiz, jlong handle, jfloatArray transform) {}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_saveKeyframeJni(JNIEnv *env, jobject thiz, jlong handle) {}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_setVisualizationModeJni(JNIEnv *env, jobject thiz, jlong handle, jint mode) {}

// VULKAN STUBS
JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_initVulkanJni(JNIEnv *env, jobject thiz, jlong handle, jobject surface, jobject assetManager) {}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_resizeVulkanJni(JNIEnv *env, jobject thiz, jlong handle, jint width, jint height) {}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_destroyVulkanJni(JNIEnv *env, jobject thiz, jlong handle) {}

// I/O
JNIEXPORT jboolean JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_saveWorldJni(JNIEnv *env, jobject thiz, jlong handle, jstring path) {
    if (handle != 0) {
        auto *engine = reinterpret_cast<MobileGS *>(handle);
        const char *nativePath = env->GetStringUTFChars(path, 0);
        bool result = engine->saveMap(nativePath);
        env->ReleaseStringUTFChars(path, nativePath);
        return result;
    }
    return false;
}

JNIEXPORT jboolean JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_importModel3DJni(JNIEnv *env, jobject thiz, jlong handle, jstring path) {
    if (handle != 0) {
        auto *engine = reinterpret_cast<MobileGS *>(handle);
        const char *nativePath = env->GetStringUTFChars(path, 0);
        bool result = engine->importModel3D(nativePath);
        env->ReleaseStringUTFChars(path, nativePath);
        return result;
    }
    return false;
}

JNIEXPORT jboolean JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_loadWorldJni(JNIEnv *env, jobject thiz, jlong handle, jstring path) {
    if (handle != 0) {
        auto *engine = reinterpret_cast<MobileGS *>(handle);
        const char *nativePath = env->GetStringUTFChars(path, 0);
        bool result = engine->loadMap(nativePath);
        env->ReleaseStringUTFChars(path, nativePath);
        return result;
    }
    return false;
}

// OPENCV
JNIEXPORT jobject JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_detectEdgesJni(JNIEnv *env, jobject thiz, jlong handle, jobject bitmap) {
    return bitmap;
}

} // extern "C"