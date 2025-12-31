#include <jni.h>
#include <string>
#include <android/log.h>

#define LOG_TAG "GraffitiJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_slam_SlamManager_initNative(JNIEnv *env, jobject thiz) {
    LOGI("initNative called: Initializing SLAM system (Stub)");
    // TODO: Initialize ORB_SLAM3 System here
    // e.g., SLAM = new ORB_SLAM3::System(...)
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_slam_SlamManager_disposeNative(JNIEnv *env, jobject thiz) {
    LOGI("disposeNative called: Shutting down SLAM system (Stub)");
    // TODO: Shutdown ORB_SLAM3 System here
    // e.g., SLAM->Shutdown()
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_slam_SlamManager_saveMapNative(JNIEnv *env, jobject thiz) {
    LOGI("saveMapNative called: Saving map (Stub)");
    // TODO: Save map using ORB_SLAM3 API
    // e.g., SLAM->SaveAtlas(...)
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_slam_SlamManager_processFrameNative(JNIEnv *env, jobject thiz, jint width, jint height, jbyteArray data, jlong timestamp) {
    // LOGI("processFrameNative called: Processing frame (Stub)");
    // TODO: Pass frame to ORB_SLAM3 System
    // e.g., SLAM->TrackMonocular(...)
}

} // extern "C"
