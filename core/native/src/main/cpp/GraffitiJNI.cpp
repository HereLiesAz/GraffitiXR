#include <jni.h>
#include <string>
#include "include/MobileGS.h"

// Singleton instance
static MobileGS* engine = nullptr;

extern "C" {

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_GraffitiJNI_initialize(JNIEnv *env, jobject thiz) {
    if (engine == nullptr) {
        engine = new MobileGS();
    }
    engine->initialize();
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_GraffitiJNI_destroy(JNIEnv *env, jobject thiz) {
    if (engine != nullptr) {
        delete engine;
        engine = nullptr;
    }
}

// --- UPDATED SIGNATURE FOR SPLATAM ---
// Requires: Depth (float[]), Color (float[] or byte[]), Width, Height, Pose (float[16]), FOV (float)
JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_GraffitiJNI_feedDepthData(
        JNIEnv *env, jobject thiz,
        jfloatArray depthData,
        jfloatArray colorData, // NEW: Required for SplaTAM
        jint width, jint height,
        jfloatArray poseMatrix, // NEW: Required for Global Mapping
        jfloat fov // NEW: Required for Projection
) {

    if (engine == nullptr) return;

    jfloat* dPixels = env->GetFloatArrayElements(depthData, nullptr);
    jfloat* cPixels = colorData ? env->GetFloatArrayElements(colorData, nullptr) : nullptr;
    jfloat* pose = poseMatrix ? env->GetFloatArrayElements(poseMatrix, nullptr) : nullptr;

    // Pass to engine
    // If color/pose are null (older Kotlin call?), the engine handles it but mapping will be poor.
    // The engine's feedDepthData handles the nullptr checks I added in MobileGS.cpp
    if (pose) {
        engine->feedDepthData(dPixels, cPixels, width, height, pose, fov);
    }

    env->ReleaseFloatArrayElements(depthData, dPixels, 0);
    if (cPixels) env->ReleaseFloatArrayElements(colorData, cPixels, 0);
    if (pose) env->ReleaseFloatArrayElements(poseMatrix, pose, 0);
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_GraffitiJNI_updateCamera(
        JNIEnv *env, jobject thiz,
        jfloatArray viewMatrix, jfloatArray projMatrix) {

    if (engine == nullptr) return;

    jfloat* view = env->GetFloatArrayElements(viewMatrix, nullptr);
    jfloat* proj = env->GetFloatArrayElements(projMatrix, nullptr);

    engine->updateCamera(view, proj);

    env->ReleaseFloatArrayElements(viewMatrix, view, 0);
    env->ReleaseFloatArrayElements(projMatrix, proj, 0);
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_GraffitiJNI_draw(JNIEnv *env, jobject thiz) {
    if (engine == nullptr) return;
    engine->draw();
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_GraffitiJNI_onSurfaceChanged(JNIEnv *env, jobject thiz, jint width, jint height) {
    if (engine == nullptr) return;
    engine->onSurfaceChanged(width, height);
}

JNIEXPORT jint JNICALL
Java_com_hereliesaz_graffitixr_GraffitiJNI_getSplatCount(JNIEnv *env, jobject thiz) {
    if (engine == nullptr) return 0;
    return engine->getSplatCount();
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_GraffitiJNI_clear(JNIEnv *env, jobject thiz) {
    if (engine == nullptr) return;
    engine->clear();
}

JNIEXPORT jboolean JNICALL
Java_com_hereliesaz_graffitixr_GraffitiJNI_saveModel(JNIEnv *env, jobject thiz, jstring path) {
    if (engine == nullptr) return false;

    const char *nativePath = env->GetStringUTFChars(path, 0);
    bool result = engine->saveModel(std::string(nativePath));
    env->ReleaseStringUTFChars(path, nativePath);
    return result;
}

JNIEXPORT jboolean JNICALL
Java_com_hereliesaz_graffitixr_GraffitiJNI_loadModel(JNIEnv *env, jobject thiz, jstring path) {
    if (engine == nullptr) return false;

    const char *nativePath = env->GetStringUTFChars(path, 0);
    bool result = engine->loadModel(std::string(nativePath));
    env->ReleaseStringUTFChars(path, nativePath);
    return result;
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_GraffitiJNI_alignMap(JNIEnv *env, jobject thiz, jfloatArray transformMatrix) {
    if (engine == nullptr) return;
    jfloat* transform = env->GetFloatArrayElements(transformMatrix, nullptr);
    engine->alignMap(transform);
    env->ReleaseFloatArrayElements(transformMatrix, transform, 0);
}

} // extern "C"