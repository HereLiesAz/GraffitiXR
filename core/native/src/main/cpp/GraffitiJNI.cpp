#include <jni.h>
#include <string>
#include "MobileGS.h"

// REMOVED: Global gMobileGS pointer to prevent single-instance collisions.
// Instead, we cast the jlong handle back to MobileGS* in every call.

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_initNativeJni(JNIEnv *env, jobject thiz) {
    auto *engine = new MobileGS();
    engine->initialize();
    return reinterpret_cast<jlong>(engine);
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_destroyNativeJni(JNIEnv *env, jobject thiz, jlong handle) {
    if (handle != 0) {
        auto *engine = reinterpret_cast<MobileGS*>(handle);
        delete engine;
    }
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_updateCameraJni(JNIEnv *env, jobject thiz, jlong handle, jfloatArray viewMtx, jfloatArray projMtx) {
    if (handle == 0) return;
    auto *engine = reinterpret_cast<MobileGS*>(handle);

    jfloat* view = env->GetFloatArrayElements(viewMtx, 0);
    jfloat* proj = env->GetFloatArrayElements(projMtx, 0);
    engine->updateCamera(view, proj);
    env->ReleaseFloatArrayElements(viewMtx, view, 0);
    env->ReleaseFloatArrayElements(projMtx, proj, 0);
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_feedDepthDataJni(JNIEnv *env, jobject thiz, jlong handle, jobject buffer, jint width, jint height, jint stride) {
    if (handle == 0) return;
    auto *engine = reinterpret_cast<MobileGS*>(handle);

    void* dataAddr = env->GetDirectBufferAddress(buffer);
    if (!dataAddr) return;
    uint16_t* data = static_cast<uint16_t*>(dataAddr);

    // Stride is usually bytes. OpenCV step is bytes.
    // If stride is pixels, convert to bytes. But Image.getPlane().getRowStride() is bytes.
    // CV_16UC1 is 2 bytes per pixel.
    cv::Mat depthWrapper(height, width, CV_16UC1, data, stride);
    engine->processDepthFrame(depthWrapper, width, height);
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_drawJni(JNIEnv *env, jobject thiz, jlong handle) {
    if (handle != 0) {
        auto *engine = reinterpret_cast<MobileGS*>(handle);
        engine->draw();
    }
}

JNIEXPORT jint JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_getPointCountJni(JNIEnv *env, jobject thiz, jlong handle) {
    if (handle != 0) {
        auto *engine = reinterpret_cast<MobileGS*>(handle);
        return engine->getPointCount();
    }
    return 0;
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_onSurfaceChangedJni(JNIEnv *env, jobject thiz, jlong handle, jint width, jint height) {
    // Pass to native if needed
}

JNIEXPORT jboolean JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_saveWorld(JNIEnv *env, jobject thiz, jlong handle, jstring path) {
    if (handle == 0) return false;
    auto *engine = reinterpret_cast<MobileGS*>(handle);
    const char *nativePath = env->GetStringUTFChars(path, 0);
    bool result = engine->saveModel(std::string(nativePath));
    env->ReleaseStringUTFChars(path, nativePath);
    return (jboolean)result;
}

JNIEXPORT jboolean JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_loadWorld(JNIEnv *env, jobject thiz, jlong handle, jstring path) {
    if (handle == 0) return false;
    auto *engine = reinterpret_cast<MobileGS*>(handle);
    const char *nativePath = env->GetStringUTFChars(path, 0);
    bool result = engine->loadModel(std::string(nativePath));
    env->ReleaseStringUTFChars(path, nativePath);
    return (jboolean)result;
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_alignMapJni(JNIEnv *env, jobject thiz, jlong handle, jfloatArray transformMtx) {
    if (handle == 0) return;
    auto *engine = reinterpret_cast<MobileGS*>(handle);
    jfloat* transform = env->GetFloatArrayElements(transformMtx, 0);
    engine->applyTransform(transform);
    env->ReleaseFloatArrayElements(transformMtx, transform, 0);
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_clearMapJni(JNIEnv *env, jobject thiz, jlong handle) {
    if (handle == 0) return;
    auto *engine = reinterpret_cast<MobileGS*>(handle);
    engine->clear();
}

}