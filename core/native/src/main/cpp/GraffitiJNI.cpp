#include <jni.h>
#include <string>
#include "MobileGS.h"

inline MobileGS* getEngine(jlong handle) {
    return reinterpret_cast<MobileGS*>(handle);
}

extern "C" {

/**
 * Initializes the C++ engine instance.
 * @return A raw pointer (handle) to the MobileGS instance.
 */
JNIEXPORT jlong JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_initNativeJni(JNIEnv *env, jobject thiz) {
    auto *engine = new MobileGS();
    engine->initialize();
    return reinterpret_cast<jlong>(engine);
}

/**
 * Destroys the C++ engine instance to free memory.
 */
JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_destroyNativeJni(JNIEnv *env, jobject thiz, jlong handle) {
    if (handle != 0) {
        delete getEngine(handle);
    }
}

/**
 * Updates the camera view and projection matrices.
 */
JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_updateCameraJni(
        JNIEnv *env, jobject thiz, jlong handle, jfloatArray viewMtx, jfloatArray projMtx) {
    if (handle == 0) return;

    jfloat* view = env->GetFloatArrayElements(viewMtx, nullptr);
    jfloat* proj = env->GetFloatArrayElements(projMtx, nullptr);

    getEngine(handle)->updateCamera(view, proj);

    env->ReleaseFloatArrayElements(viewMtx, view, 0);
    env->ReleaseFloatArrayElements(projMtx, proj, 0);
}

/**
 * Feeds raw depth data from the camera into the mapping engine.
 * Handles the ByteBuffer locking and type conversion.
 */
JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_feedDepthDataJni(
        JNIEnv *env, jobject thiz,
        jlong handle,
        jobject depthBuffer,
        jobject colorBuffer,
        jint width, jint height,
        jint stride,
        jfloatArray poseMatrix,
        jfloat fov) {

    if (handle == 0) return;

    // CHANGED: Cast to uint16_t* (unsigned short) instead of float*
    // ARCore DEPTH16 is 16-bit integers
    auto* depthData = (uint16_t*)env->GetDirectBufferAddress(depthBuffer);

    float* colorData = nullptr;
    if (colorBuffer != nullptr) {
        colorData = (float*)env->GetDirectBufferAddress(colorBuffer);
    }

    jfloat* pose = env->GetFloatArrayElements(poseMatrix, nullptr);

    if (depthData && pose) {
        getEngine(handle)->feedDepthData(depthData, colorData, width, height, stride, pose, fov);
    }

    env->ReleaseFloatArrayElements(poseMatrix, pose, 0);
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_drawJni(JNIEnv *env, jobject thiz, jlong handle) {
    if (handle == 0) return;
    getEngine(handle)->draw();
}

JNIEXPORT jint JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_getPointCountJni(JNIEnv *env, jobject thiz, jlong handle) {
    if (handle == 0) return 0;
    return getEngine(handle)->getSplatCount();
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_onSurfaceChangedJni(JNIEnv *env, jobject thiz, jlong handle, jint width, jint height) {
    if (handle == 0) return;
    getEngine(handle)->onSurfaceChanged(width, height);
}

JNIEXPORT jboolean JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_saveWorld(JNIEnv *env, jobject thiz, jlong handle, jstring path) {
    if (handle == 0) return false;
    const char *nativePath = env->GetStringUTFChars(path, 0);
    bool result = getEngine(handle)->saveModel(std::string(nativePath));
    env->ReleaseStringUTFChars(path, nativePath);
    return result;
}

JNIEXPORT jboolean JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_loadWorld(JNIEnv *env, jobject thiz, jlong handle, jstring path) {
    if (handle == 0) return false;
    const char *nativePath = env->GetStringUTFChars(path, 0);
    bool result = getEngine(handle)->loadModel(std::string(nativePath));
    env->ReleaseStringUTFChars(path, nativePath);
    return result;
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_alignMapJni(JNIEnv *env, jobject thiz, jlong handle, jfloatArray transformMtx) {
    if (handle == 0) return;
    jfloat* transform = env->GetFloatArrayElements(transformMtx, nullptr);
    getEngine(handle)->alignMap(transform);
    env->ReleaseFloatArrayElements(transformMtx, transform, 0);
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_nativebridge_SlamManager_clearMapJni(JNIEnv *env, jobject thiz, jlong handle) {
    if (handle == 0) return;
    getEngine(handle)->clear();
}

} // extern "C"
