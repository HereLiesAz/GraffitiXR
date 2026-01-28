#include <jni.h>
#include <string>
#include "MobileGS.h"

// FIX: Global pointer definition was missing
MobileGS *gMobileGS = nullptr;

extern "C" {

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_slam_SlamManager_initNativeJni(JNIEnv *env, jobject thiz) {
    if (!gMobileGS) {
        gMobileGS = new MobileGS();
        gMobileGS->initialize();
    }
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_slam_SlamManager_destroyNativeJni(JNIEnv *env, jobject thiz) {
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
Java_com_hereliesaz_graffitixr_slam_SlamManager_saveWorldJni(JNIEnv *env, jobject thiz, jstring path) {
    if (!gMobileGS) return false;
    const char *nativePath = env->GetStringUTFChars(path, 0);
    bool result = gMobileGS->saveModel(std::string(nativePath));
    env->ReleaseStringUTFChars(path, nativePath);
    return (jboolean)result;
}

JNIEXPORT jboolean JNICALL
Java_com_hereliesaz_graffitixr_slam_SlamManager_loadWorldJni(JNIEnv *env, jobject thiz, jstring path) {
    if (!gMobileGS) return false;
    const char *nativePath = env->GetStringUTFChars(path, 0);
    bool result = gMobileGS->loadModel(std::string(nativePath));
    env->ReleaseStringUTFChars(path, nativePath);
    return (jboolean)result;
}

}
