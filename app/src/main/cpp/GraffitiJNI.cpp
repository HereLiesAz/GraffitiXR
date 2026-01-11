#include <jni.h>
#include <string>
#include <android/log.h>
#include <mutex>

// Standard include for ORB_SLAM3 System
#include "System.h"

#define LOG_TAG "GraffitiJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Global pointer to the SLAM system
ORB_SLAM3::System* SLAM = nullptr;
std::mutex slamMutex;

extern "C" {

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_slam_SlamManager_initNative(JNIEnv *env, jobject thiz) {
    LOGI("initNative called: Initializing SLAM system");

    std::lock_guard<std::mutex> lock(slamMutex);
    if (SLAM == nullptr) {
        // Initialize the SLAM system.
        // Note: Paths to vocabulary and settings files must be provided correctly in a real deployment.
        // Assuming standard constructor: System(vocabFile, settingsFile, sensorMode, useViewer)
        // These paths are placeholders.
        const char* vocabPath = "/data/data/com.hereliesaz.graffitixr/files/ORBvoc.txt";
        const char* settingsPath = "/data/data/com.hereliesaz.graffitixr/files/EuRoC.yaml";

        SLAM = new ORB_SLAM3::System(vocabPath, settingsPath, ORB_SLAM3::System::MONOCULAR, false);
    } else {
        LOGI("SLAM system already initialized");
    }
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_slam_SlamManager_disposeNative(JNIEnv *env, jobject thiz) {
    LOGI("disposeNative called: Shutting down SLAM system");

    std::lock_guard<std::mutex> lock(slamMutex);

    if (SLAM != nullptr) {
        SLAM->Shutdown();
        delete SLAM;
        SLAM = nullptr;
    } else {
        LOGI("SLAM system was not initialized or already disposed");
    }
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_slam_SlamManager_saveMapNative(JNIEnv *env, jobject thiz) {
    LOGI("saveMapNative called: Saving map (Stub)");
    // TODO: Implement map saving using ORB_SLAM3 API
    // if (SLAM != nullptr) {
    //     SLAM->SaveAtlas(ORB_SLAM3::System::BINARY);
    // }
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_graffitixr_slam_SlamManager_processFrameNative(JNIEnv *env, jobject thiz, jint width, jint height, jbyteArray data, jlong timestamp) {
    // LOGI("processFrameNative called: Processing frame (Stub)");
    // TODO: Pass frame to ORB_SLAM3 System
}

} // extern "C"
