#include <jni.h>
#include <string>
#include "MobileGS.h"

extern "C" {

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_slam_SlamManager_initNative(JNIEnv *env, jobject thiz) {
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
Java_com_hereliesaz_graffitixr_slam_SlamManager_updateCamera(JNIEnv *env, jobject thiz, jfloatArray viewMtx, jfloatArray projMtx) {
    if (!gMobileGS) return;
    jfloat* view = env->GetFloatArrayElements(viewMtx, 0);
    jfloat* proj = env->GetFloatArrayElements(projMtx, 0);
    gMobileGS->updateCamera(view, proj);
    env->ReleaseFloatArrayElements(viewMtx, view, 0);
    env->ReleaseFloatArrayElements(projMtx, proj, 0);
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_slam_SlamManager_draw(JNIEnv *env, jobject thiz) {
    if (gMobileGS) {
        gMobileGS->draw();
    }
}

JNIEXPORT jint JNICALL
Java_com_hereliesaz_graffitixr_slam_SlamManager_getPointCount(JNIEnv *env, jobject thiz) {
    if (gMobileGS) return gMobileGS->getPointCount();
    return 0;
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_slam_SlamManager_onSurfaceChanged(JNIEnv *env, jobject thiz, jint width, jint height) {
    // Pass to native if needed
}

// FIX: Return jboolean to indicate success/failure
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

}
