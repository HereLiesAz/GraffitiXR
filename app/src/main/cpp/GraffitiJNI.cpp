#include <jni.h>
#include <string>
#include "MobileGS.h"

MobileGS *gMobileGS = nullptr;
std::mutex gPointerMutex;

extern "C" {

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_slam_SlamManager_initNativeJni(JNIEnv *env, jobject thiz) {
    std::lock_guard<std::mutex> lock(gPointerMutex);
    if (!gMobileGS) {
        gMobileGS = new MobileGS();
        gMobileGS->initialize();
    }
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_slam_SlamManager_destroyNativeJni(JNIEnv *env, jobject thiz) {
    std::lock_guard<std::mutex> lock(gPointerMutex);
    if (gMobileGS) {
        delete gMobileGS;
        gMobileGS = nullptr;
    }
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_slam_SlamManager_updateCameraJni(JNIEnv *env, jobject thiz, jfloatArray viewMtx, jfloatArray projMtx) {
    if (!gMobileGS) return;
    jfloat* view = env->GetFloatArrayElements(viewMtx, 0);
    jfloat* proj = env->GetFloatArrayElements(projMtx, 0);
    gMobileGS->updateCamera(view, proj);
    env->ReleaseFloatArrayElements(viewMtx, view, 0);
    env->ReleaseFloatArrayElements(projMtx, proj, 0);
}

// CRITICAL FIX: Receive Depth Buffer directly
JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_slam_SlamManager_feedDepthDataJni(JNIEnv *env, jobject thiz, jobject buffer, jint width, jint height) {
    if (!gMobileGS) return;

    // Direct buffer access for performance (No copy)
    uint16_t* data = (uint16_t*)env->GetDirectBufferAddress(buffer);
    if (!data) return;

    // Create OpenCV wrapper around the buffer (Does not copy data yet)
    cv::Mat depthWrapper(height, width, CV_16UC1, data);

    // Pass to engine (Engine handles internal thread safety and copying if needed)
    gMobileGS->processDepthFrame(depthWrapper, width, height);

    // No explicit release needed for wrapper, but data pointer invalid after Java call returns
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_slam_SlamManager_drawJni(JNIEnv *env, jobject thiz) {
    if (gMobileGS) {
        gMobileGS->draw();
    }
}

JNIEXPORT jint JNICALL
Java_com_hereliesaz_graffitixr_slam_SlamManager_getPointCountJni(JNIEnv *env, jobject thiz) {
    if (gMobileGS) return gMobileGS->getPointCount();
    return 0;
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_slam_SlamManager_onSurfaceChangedJni(JNIEnv *env, jobject thiz, jint width, jint height) {
    // Pass to native if needed
}

JNIEXPORT jboolean JNICALL
Java_com_hereliesaz_graffitixr_slam_SlamManager_saveWorld(JNIEnv *env, jobject thiz, jstring path) {
    if (!gMobileGS) return false;
    const char *nativePath = env->GetStringUTFChars(path, 0);
    bool result = gMobileGS->saveModel(std::string(nativePath));
    env->ReleaseStringUTFChars(path, nativePath);
    return (jboolean)result;
}

JNIEXPORT jboolean JNICALL
Java_com_hereliesaz_graffitixr_slam_SlamManager_loadWorld(JNIEnv *env, jobject thiz, jstring path) {
    if (!gMobileGS) return false;
    const char *nativePath = env->GetStringUTFChars(path, 0);
    bool result = gMobileGS->loadModel(std::string(nativePath));
    env->ReleaseStringUTFChars(path, nativePath);
    return (jboolean)result;
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_slam_SlamManager_alignMapJni(JNIEnv *env, jobject thiz, jfloatArray transformMtx) {
    if (!gMobileGS) return;
    jfloat* transform = env->GetFloatArrayElements(transformMtx, 0);
    gMobileGS->applyTransform(transform);
    env->ReleaseFloatArrayElements(transformMtx, transform, 0);
}

}