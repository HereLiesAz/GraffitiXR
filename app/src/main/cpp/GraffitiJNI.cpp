#include <jni.h>
#include <string>
#include <vector>
#include <opencv2/core.hpp>
#include <android/log.h>
#include "include/MobileGS.h"

#define TAG "GraffitiJNI"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static JavaVM* gVm = nullptr;
static MobileGS* gMobileGS = nullptr;

#ifdef HAS_ORB_SLAM3
#include "System.h"
static ORB_SLAM3::System* SLAM = nullptr;
#endif

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
Java_com_hereliesaz_graffitixr_slam_SlamManager_initNative(JNIEnv *env, jobject thiz) {
    if (!gMobileGS) {
        gMobileGS = new MobileGS();
        gMobileGS->initialize();
    }
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_slam_SlamManager_destroyNative(JNIEnv *env, jobject thiz) {
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

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_slam_SlamManager_drawFrame(JNIEnv *env, jobject thiz) {
    if (gMobileGS) {
        gMobileGS->draw();
    }
}

}