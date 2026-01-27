#include <jni.h>
#include <string>
#include <vector>
#include <cstdint>
#include <opencv2/core.hpp>
#include <android/log.h>
#include "include/MobileGS.h"

#define TAG "GraffitiJNI"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static JavaVM* gVm = nullptr;
static MobileGS* gMobileGS = nullptr;

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    gVm = vm;
    return JNI_VERSION_1_6;
}

JNIEnv* getEnv() {
    JNIEnv* env;
    if (gVm->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        gVm->AttachCurrentThread(&env, nullptr);
    }
    return env;
}

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
Java_com_hereliesaz_graffitixr_slam_SlamManager_updateCamera(JNIEnv *env, jobject thiz,
        jfloatArray view_mtx,
        jfloatArray proj_mtx) {
    if (!gMobileGS) return;
    jfloat* view = env->GetFloatArrayElements(view_mtx, nullptr);
    jfloat* proj = env->GetFloatArrayElements(proj_mtx, nullptr);
    gMobileGS->updateCamera(view, proj);
    env->ReleaseFloatArrayElements(view_mtx, view, 0);
    env->ReleaseFloatArrayElements(proj_mtx, proj, 0);
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_slam_SlamManager_feedDepth(JNIEnv *env, jobject thiz,
        jbyteArray depth_data,
        jint width, jint height) {
    if (!gMobileGS) return;
    jbyte* data = env->GetByteArrayElements(depth_data, nullptr);
    if (data != nullptr) {
        cv::Mat depthMap(height, width, CV_16U, (unsigned char*)data);
        gMobileGS->processDepthFrame(depthMap, width, height);
    }
    env->ReleaseByteArrayElements(depth_data, data, 0);
}

// Consolidated method to match Kotlin
JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_slam_SlamManager_updateCameraImage(JNIEnv *env, jobject thiz,
        jbyteArray image_data,
        jint width, jint height, jlong timestamp) {
    if (!gMobileGS) return;
    jbyte* data = env->GetByteArrayElements(image_data, nullptr);
    if (data != nullptr) {
        cv::Mat img(height, width, CV_8UC1, (unsigned char*)data);
        gMobileGS->processImage(img, width, height, (int64_t)timestamp);
    }
    env->ReleaseByteArrayElements(image_data, data, 0);
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_slam_SlamManager_drawFrame(JNIEnv *env, jobject thiz) {
    if (gMobileGS) {
        gMobileGS->draw();
    }
}

// --- MISSING IO METHODS IMPLEMENTED BELOW ---

JNIEXPORT jboolean JNICALL
Java_com_hereliesaz_graffitixr_slam_SlamManager_saveWorld(JNIEnv *env, jobject thiz, jstring path) {
    if (!gMobileGS) return false;
    const char *nativePath = env->GetStringUTFChars(path, 0);
    bool result = gMobileGS->saveModel(std::string(nativePath));
    env->ReleaseStringUTFChars(path, nativePath);
    return result;
}

JNIEXPORT jboolean JNICALL
Java_com_hereliesaz_graffitixr_slam_SlamManager_loadWorld(JNIEnv *env, jobject thiz, jstring path) {
    if (!gMobileGS) return false;
    const char *nativePath = env->GetStringUTFChars(path, 0);
    bool result = gMobileGS->loadModel(std::string(nativePath));
    env->ReleaseStringUTFChars(path, nativePath);
    return result;
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_slam_SlamManager_clearMap(JNIEnv *env, jobject thiz) {
    if (gMobileGS) {
        gMobileGS->clear();
    }
}

}
